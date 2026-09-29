"""Invoked by AgentRuntimePostgresIT with actual Flyway migrations, never production DB."""

from __future__ import annotations

import asyncio
import json
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import psycopg
import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg.types.json import Jsonb

from deepresearch_workflow.agent_budget import AgentBudgetGateway, SqlAgentLedger
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.domain import ClaimedRun, WorkflowStatus
from deepresearch_workflow.graph import RunBudgetExceededError, WorkflowExecutionError
from deepresearch_workflow.ports import BudgetClaimConflictError, RepositoryEventSink
from deepresearch_workflow.repository import PostgresWorkflowRepository, checkpoint_pool
from deepresearch_workflow.runner import WorkflowRunner
from deepresearch_workflow.settings import Settings

from .test_agent_runtime import (
    EvidenceSubstitute,
    Finalizer,
    ObservationDrivenModel,
    SourceTransport,
)

URL = os.getenv("TEST_AGENT_DATABASE_URL", "")
pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(
        not URL or os.getenv("AGENT_TEST_ISOLATED") != "1",
        reason="disposable Flyway database required",
    ),
]


async def seed(budget):
    run = "agent-it-" + uuid4().hex
    claim = str(uuid4())
    session = "session-it-" + uuid4().hex
    grant = "grant-it-" + uuid4().hex
    async with await psycopg.AsyncConnection.connect(URL) as conn:
        async with conn.transaction():
            await conn.execute("SET CONSTRAINTS ALL DEFERRED")
            await conn.execute(
                "INSERT INTO agent_session(session_id,user_id,title) VALUES (%s,'tenant:owner','isolated test')",
                (session,),
            )
            await conn.execute(
                """INSERT INTO agent_workflow_run
                (run_id,session_id,user_id,question,context_snapshot,endpoint,idempotency_key,request_fingerprint,
                 graph_thread_id,status,stage,deadline_at,requested_scopes,grant_id,claim_token,lease_until,budget)
                VALUES (%s,%s,'tenant:owner','question','{}','/api/research/agents',%s,%s,%s,'WORKING','WORKING',
                    now()+interval '180 seconds',ARRAY['web_search','read_source','check_claims'],%s,%s::uuid,now()+interval '60 seconds',%s)""",
                (
                    run,
                    session,
                    run,
                    "f" * 64,
                    run,
                    grant,
                    claim,
                    Jsonb(budget.model_dump(mode="json", by_alias=True)),
                ),
            )
            await conn.execute(
                "INSERT INTO agent_workflow_grant(grant_id,run_id,subject,scopes,expires_at) VALUES (%s,%s,'tenant:owner',ARRAY['web_search','read_source','check_claims'],now()+interval '180 seconds')",
                (grant, run),
            )
            await conn.execute(
                "INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES (%s,'tenant','owner',%s)",
                (run, session),
            )
            await conn.execute(
                "INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES (%s,%s,'tenant','owner')",
                (run, run),
            )
    repo = PostgresWorkflowRepository(URL)
    await repo.open()
    return repo, SqlAgentLedger(repo), run, claim


async def test_concurrent_process_ledgers_share_model_cap():
    budget = AgentRunBudget(runtime="agent", maxModelCalls=1)
    repo, ledger, run, claim = await seed(budget)
    second = PostgresWorkflowRepository(URL)
    await second.open()
    try:
        results = await asyncio.gather(
            ledger.reserve(
                run, claim, "model:first", "MODEL", "DECISION", "a" * 64, 100, 100, budget
            ),
            SqlAgentLedger(second).reserve(
                run, claim, "model:second", "MODEL", "CHECK", "b" * 64, 100, 100, budget
            ),
            return_exceptions=True,
        )
        assert sum(isinstance(r, dict) for r in results) == 1
        assert sum(isinstance(r, RunBudgetExceededError) for r in results) == 1
        assert (await ledger.summary(run, claim))["modelCalls"] == 1
    finally:
        await repo.close()
        await second.close()


async def test_ambiguous_attempts_keep_admission_and_known_result_replays_without_charge():
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)
    try:
        first = await ledger.reserve(
            run, claim, "model:known", "MODEL", "CHECK", "a" * 64, 500, 100, budget
        )
        value = {
            "value": {"result": "bounded"},
            "input_tokens": 120,
            "output_tokens": 30,
            "cost_cny": None,
            "request_binding": {},
        }
        await ledger.settle(
            run,
            claim,
            "model:known",
            first["attempt"],
            value,
            {"input_tokens": 120, "output_tokens": 30, "cost_cny": None},
        )
        replay = await ledger.reserve(
            run, claim, "model:known", "MODEL", "CHECK", "a" * 64, 500, 100, budget
        )
        assert replay["replay"] == value and (await ledger.summary(run, claim))["modelCalls"] == 1
        with pytest.raises(WorkflowExecutionError):
            await ledger.reserve(
                run, claim, "model:known", "MODEL", "CHECK", "b" * 64, 500, 100, budget
            )
        unknown = await ledger.reserve(
            run, claim, "model:unknown", "MODEL", "DECISION", "c" * 64, 800, 200, budget
        )
        await ledger.settle(run, claim, "model:unknown", unknown["attempt"], {}, {}, unknown=True)
        usage = await ledger.summary(run, claim)
        assert usage["inputTokens"] is None and usage["estimatedCost"] is None
        assert usage["inputAdmissionTokens"] == 920 and usage["outputAdmissionTokens"] == 230
    finally:
        await repo.close()


async def test_token_admission_prevents_dispatch_and_cancel_invalidates_claim():
    budget = AgentRunBudget(runtime="agent", maxInputTokens=100, maxOutputTokens=100)
    repo, ledger, run, claim = await seed(budget)
    try:
        with pytest.raises(RunBudgetExceededError):
            await ledger.reserve(
                run, claim, "model:over", "MODEL", "DECISION", "a" * 64, 101, 50, budget
            )
        assert (await ledger.summary(run, claim))["modelCalls"] == 0
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            await conn.execute(
                "UPDATE agent_workflow_run SET cancel_requested=true WHERE run_id=%s", (run,)
            )
        with pytest.raises(BudgetClaimConflictError):
            await ledger.reserve(
                run, claim, "model:cancelled", "MODEL", "DECISION", "a" * 64, 1, 1, budget
            )
    finally:
        await repo.close()


async def test_claim_recovery_never_repeats_ambiguous_tool():
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)
    try:
        await ledger.reserve(run, claim, "tool-ambiguous", "TOOL", "TOOL", "a" * 64, 0, 0, budget)
        new_claim = str(uuid4())
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            await conn.execute(
                "UPDATE agent_workflow_run SET claim_token=%s::uuid WHERE run_id=%s",
                (new_claim, run),
            )
        with pytest.raises(BudgetClaimConflictError):
            await ledger.reserve(run, claim, "tool-other", "TOOL", "TOOL", "b" * 64, 0, 0, budget)
        with pytest.raises(WorkflowExecutionError) as failure:
            await ledger.reserve(
                run, new_claim, "tool-ambiguous", "TOOL", "TOOL", "a" * 64, 0, 0, budget
            )
        assert failure.value.error_code == "AGENT_OPERATION_UNKNOWN"
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            row = await (
                await conn.execute(
                    "SELECT status FROM agent_research_operation WHERE run_id=%s", (run,)
                )
            ).fetchone()
            assert row[0] == "UNKNOWN"
    finally:
        await repo.close()


async def test_database_trigger_rejects_direct_overallocation_and_role_cannot_rewrite_identity():
    budget = AgentRunBudget(runtime="agent", maxToolCalls=1)
    repo, ledger, run, claim = await seed(budget)
    try:
        await ledger.reserve(run, claim, "tool-first", "TOOL", "TOOL", "a" * 64, 0, 0, budget)
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            with pytest.raises(psycopg.errors.RaiseException):
                await conn.execute(
                    """INSERT INTO agent_research_operation
                    (run_id,operation_key,attempt,kind,purpose,request_hash,status,input_reserved,output_reserved,claim_token)
                    VALUES (%s,'tool-bypass',1,'TOOL','TOOL',%s,'RESERVED',0,0,%s::uuid)""",
                    (run, "b" * 64, claim),
                )
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            await conn.execute("SET ROLE deepresearch_workflow")
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                await conn.execute(
                    "UPDATE agent_research_run SET owner_id='foreign' WHERE run_id=%s", (run,)
                )
    finally:
        await repo.close()


async def test_bad_model_json_retry_is_charged_and_known_replay_never_dispatches():
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)

    class Model:
        calls = 0

        async def invoke(self, request):
            self.calls += 1
            if self.calls == 1:
                raise ValueError("bad provider JSON")
            return ModelResult(value={"accepted": True}, input_tokens=100, output_tokens=10)

    async def guard():
        if not (await repo.assert_active_claim(run, claim))[0]:
            raise RuntimeError("inactive")

    model = Model()
    gateway = AgentBudgetGateway(
        run_id=run, claim_token=claim, budget=budget, ledger=ledger, model=model, guard=guard
    )
    request = ModelRequest(
        name="Check",
        instruction="fixture",
        payload={},
        schema={
            "type": "object",
            "properties": {"accepted": {"type": "boolean"}},
            "required": ["accepted"],
            "additionalProperties": False,
        },
    )
    try:
        result = await gateway.model_call("model:retry", "CHECK", request)
        assert (
            await gateway.model_call("model:retry", "CHECK", request)
        ) == result and model.calls == 2
        usage = await ledger.summary(run, claim)
        assert (
            usage["modelCalls"] == 2
            and usage["inputTokens"] is None
            and usage["outputTokens"] is None
        )
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            statuses = await (
                await conn.execute(
                    "SELECT status FROM agent_research_operation WHERE run_id=%s ORDER BY attempt",
                    (run,),
                )
            ).fetchall()
            assert [row[0] for row in statuses] == ["UNKNOWN", "SETTLED"]
    finally:
        await repo.close()


async def test_real_role_postgres_checkpoint_recovery_reuses_authorized_calls():
    budget = AgentRunBudget(runtime="agent")
    admin, _, run, claim = await seed(budget)
    await admin.close()
    async with await psycopg.AsyncConnection.connect(URL) as conn:
        await conn.execute(
            "UPDATE agent_workflow_run SET status='QUEUED',stage='QUEUED' WHERE run_id=%s", (run,)
        )
    role_url = psycopg.conninfo.make_conninfo(
        URL, user="deepresearch_workflow", password="workflow-integration-test-password"
    )
    repository = PostgresWorkflowRepository(role_url)
    await repository.open()
    connections = checkpoint_pool(role_url, "langgraph")
    await connections.open(wait=True)
    saver = AsyncPostgresSaver(connections)
    await saver.setup()
    fixture = json.loads(
        (
            Path(__file__).resolve().parents[2]
            / "testdata/agent-foundation/evidence/version-difference.json"
        ).read_text()
    )
    records = [r for r in fixture["records"] if r["record_type"] == "Evidence"]
    model = ObservationDrivenModel()
    tools = SourceTransport(records, repository)
    evidence = EvidenceSubstitute(records)
    finalizer = Finalizer()

    class CrashOnce:
        fired = False

        async def emit(self, event, token):
            if event.event_type == "AGENT_OBSERVATION" and not self.fired:
                self.fired = True
                operation.cancel()
                await asyncio.Event().wait()
            await RepositoryEventSink(repository).emit(event, token)

    events = CrashOnce()

    def factory(token, effective):
        return AutonomousResearchGraph(
            model=model,
            tools=tools,
            repository=repository,
            ledger=SqlAgentLedger(repository),
            evidence=evidence,
            events=events,
            budget=effective,
            claim_token=token,
        ).compile(checkpointer=saver)

    runner = WorkflowRunner(
        repository=repository,
        control_plane=finalizer,
        graph_factory=factory,
        settings=Settings(runner_enabled=False),
    )
    claimed = ClaimedRun(
        run_id=run,
        session_id="isolated",
        user_id="tenant:owner",
        question=fixture["question"],
        endpoint="/api/research/agents",
        graph_thread_id=run,
        requested_scopes=["web_search", "read_source", "check_claims"],
        grant_id="fixture",
        budget=budget,
        claim_token=claim,
        deadline_at=datetime.now(UTC) + timedelta(seconds=180),
        status="QUEUED",
        stage="QUEUED",
    )
    try:
        operation = asyncio.create_task(runner.run_claimed(claimed))
        with pytest.raises(asyncio.CancelledError):
            await operation
        assert not finalizer.requests and len(tools.calls) == 1
        await repository.close()
        await connections.close()
        recovered = str(uuid4())
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            await conn.execute(
                "UPDATE agent_workflow_run SET claim_token=%s::uuid,lease_until=now()+interval '60 seconds' WHERE run_id=%s",
                (recovered, run),
            )
        # Fresh pools and saver read the committed checkpoint, with no in-memory ledger.
        repository = PostgresWorkflowRepository(role_url)
        await repository.open()
        connections = checkpoint_pool(role_url, "langgraph")
        await connections.open(wait=True)
        saver = AsyncPostgresSaver(connections)
        runner = WorkflowRunner(
            repository=repository,
            control_plane=finalizer,
            graph_factory=factory,
            settings=Settings(runner_enabled=False),
        )
        await runner.run_claimed(
            claimed.model_copy(
                update={"claim_token": recovered, "status": WorkflowStatus.WORKING, "stage": "WORKING"}
            )
        )
        assert (
            finalizer.requests[-1].status == "SUCCEEDED"
            and len(tools.calls) == 1
            and len(evidence.publications) == 1
        )
        assert finalizer.requests[-1].usage["modelCalls"] == 6
    finally:
        await repository.close()
        await connections.close()
