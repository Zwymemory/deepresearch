"""Invoked by AgentRuntimePostgresIT with actual Flyway migrations, never production DB."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import httpx
import psycopg
import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg.types.json import Jsonb

from deepresearch_workflow.agent_budget import AgentBudgetGateway, SqlAgentLedger
from deepresearch_workflow.agent_model import AgentModelFailure, OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.domain import ClaimedRun, WorkflowStatus
from deepresearch_workflow.graph import (
    ModelCallError,
    RunBudgetExceededError,
    WorkflowExecutionError,
)
from deepresearch_workflow.ports import BudgetClaimConflictError, RepositoryEventSink
from deepresearch_workflow.repository import PostgresWorkflowRepository, checkpoint_pool
from deepresearch_workflow.runner import WorkflowRunner
from deepresearch_workflow.settings import Settings

from .test_agent_identity import install_observer
from .test_agent_json_transport import REQUEST as JSON_REQUEST
from .test_agent_json_transport import envelope, fixture_model, json_settings, oracle_transport
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


def _read_version_fixture():
    path = (
        Path(__file__).resolve().parents[2]
        / "testdata/agent-foundation/evidence/version-difference.json"
    )
    return json.loads(path.read_text(encoding="utf-8"))


async def seed(budget):
    run = "agent-it-" + uuid4().hex
    claim = str(uuid4())
    session = "session-it-" + uuid4().hex
    grant = "grant-it-" + uuid4().hex
    async with await psycopg.AsyncConnection.connect(URL) as conn:
        async with conn.transaction():
            await conn.execute("SET CONSTRAINTS ALL DEFERRED")
            await conn.execute(
                (
                    "INSERT INTO agent_session(session_id,user_id,title) VALUES (%s,'tenant:owner',"
                    "'isolated test')"
                ),
                (session,),
            )
            await conn.execute(
                (
                    "INSERT INTO agent_workflow_run\n"
                    "                (run_id,session_id,user_id,question,context_snapshot,endpoint,"
                    "idempotency_key,request_fingerprint,\n"
                    "                 graph_thread_id,status,stage,deadline_at,requested_scopes,"
                    "grant_id,claim_token,lease_until,budget)\n"
                    "                VALUES (%s,%s,'tenant:owner','question','{}',"
                    "'/api/research/agents',%s,%s,%s,'WORKING','WORKING',\n"
                    "                    now()+interval '180 seconds',ARRAY['web_search',"
                    "'read_source','check_claims'],%s,%s::uuid,now()+interval '60 seconds',%s)"
                ),
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
                (
                    "INSERT INTO agent_workflow_grant(grant_id,run_id,subject,scopes,expires_at) "
                    "VALUES (%s,%s,'tenant:owner',ARRAY['web_search','read_source','check_claims'],"
                    "now()+interval '180 seconds')"
                ),
                (grant, run),
            )
            await conn.execute(
                (
                    "INSERT INTO research_project(project_id,tenant_id,owner_id,session_id) VALUES "
                    "(%s,'tenant','owner',%s)"
                ),
                (run, session),
            )
            await conn.execute(
                (
                    "INSERT INTO agent_research_run(run_id,project_id,tenant_id,owner_id) VALUES "
                    "(%s,%s,'tenant','owner')"
                ),
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


async def test_explicit_rate_limit_retry_is_charged_and_known_replay_never_dispatches():
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)

    class Model:
        calls = 0

        async def invoke(self, request):
            self.calls += 1
            if self.calls == 1:
                raise AgentModelFailure(
                    "http_rate_limit",
                    "RATE_LIMIT",
                    retryable=True,
                    status_code=429,
                    input_tokens=11,
                    output_tokens=3,
                )
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
            usage["modelCalls"] == 2 and usage["inputTokens"] == 111 and usage["outputTokens"] == 13
        )
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            statuses = await (
                await conn.execute(
                    "SELECT status,actual_usage,safe_result FROM agent_research_operation "
                    "WHERE run_id=%s ORDER BY attempt",
                    (run,),
                )
            ).fetchall()
            assert [row[0] for row in statuses] == ["UNKNOWN", "SETTLED"]
            assert statuses[0][1]["input_tokens"] == 11
            assert statuses[0][1]["model_failure"] == {
                "failure_kind": "RATE_LIMIT",
                "error_class": "http_rate_limit",
                "retryable": True,
                "status_code": 429,
            }
            assert statuses[0][2] is None
    finally:
        await repo.close()


async def test_invalid_model_result_is_unknown_with_known_usage_and_never_reissued():
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)

    class Model:
        calls = 0

        async def invoke(self, _):
            self.calls += 1
            raise AgentModelFailure(
                "function_json",
                "SCHEMA",
                input_tokens=23,
                output_tokens=8,
            )

    async def guard():
        assert (await repo.assert_active_claim(run, claim))[0]

    model = Model()
    gateway = AgentBudgetGateway(
        run_id=run,
        claim_token=claim,
        budget=budget,
        ledger=ledger,
        model=model,
        guard=guard,
    )
    request = ModelRequest(
        name="Check",
        instruction="fixture",
        payload={},
        schema={"type": "object"},
    )
    try:
        with pytest.raises(ModelCallError) as rejected:
            await gateway.model_call("model:invalid", "CHECK", request)
        assert rejected.value.error_class == "function_json"
        assert rejected.value.retryable is False
        with pytest.raises(WorkflowExecutionError) as replay:
            await gateway.model_call("model:invalid", "CHECK", request)
        assert replay.value.error_code == "AGENT_MODEL_NOT_RETRYABLE"
        assert model.calls == 1
        usage = await ledger.summary(run, claim)
        assert usage["modelCalls"] == 1
        assert usage["inputTokens"] == 23 and usage["outputTokens"] == 8
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            row = await (
                await conn.execute(
                    "SELECT status,safe_result,actual_usage FROM agent_research_operation "
                    "WHERE run_id=%s AND operation_key='model:invalid'",
                    (run,),
                )
            ).fetchone()
        assert row[0] == "UNKNOWN" and row[1] is None
        assert row[2]["model_failure"] == {
            "failure_kind": "SCHEMA",
            "error_class": "function_json",
            "retryable": False,
        }
    finally:
        await repo.close()


async def test_observer_identity_rejection_persists_usage_once_and_cannot_replay(
    tmp_path, monkeypatch
):
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch)
    calls = []

    def transport(request):
        calls.append(request)
        return httpx.Response(
            200,
            json={
                "model": "deepseek-v4-pro",
                "choices": "PRIVATE-REJECTED-CONTENT",
                "usage": {
                    "prompt_tokens": 47,
                    "completion_tokens": 5,
                    "total_tokens": 52,
                    "prompt_cache_hit_tokens": 40,
                    "completion_tokens_details": {"reasoning_tokens": 4},
                },
            },
        )

    async def guard():
        assert (await repo.assert_active_claim(run, claim))[0]

    request = ModelRequest(
        name="Check", instruction="fixture", payload={}, schema={"type": "object"}
    )
    try:
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
            model = OpenAIAgentModel(
                Settings(
                    openai_key="fixture-only",
                    model_name="deepseek-v4-flash",
                    openai_base_url="https://api.deepseek.com",
                ),
                client,
            )
            gateway = AgentBudgetGateway(
                run_id=run,
                claim_token=claim,
                budget=budget,
                ledger=ledger,
                model=model,
                guard=guard,
            )
            with pytest.raises(ModelCallError) as rejected:
                await gateway.model_call("model:identity", "CHECK", request)
            assert rejected.value.error_code == "MODEL_IDENTITY_INVALID"
            with pytest.raises(WorkflowExecutionError) as replay:
                await gateway.model_call("model:identity", "CHECK", request)
            assert replay.value.error_code == "AGENT_MODEL_NOT_RETRYABLE"
        assert len(calls) == 1
        usage = await ledger.summary(run, claim)
        assert usage["modelCalls"] == 1
        assert usage["inputTokens"] == 47 and usage["outputTokens"] == 5
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            row = await (
                await conn.execute(
                    "SELECT status,safe_result,actual_usage FROM agent_research_operation "
                    "WHERE run_id=%s AND operation_key='model:identity'",
                    (run,),
                )
            ).fetchone()
        assert row[0] == "UNKNOWN" and row[1] is None
        assert row[2]["input_tokens"] == 47 and row[2]["output_tokens"] == 5
        failure = row[2]["model_failure"]
        assert failure["error_class"] == "identity_validation" and failure["retryable"] is False
        assert failure["status_code"] == 200
        assert failure["identity"]["reason"] == "response_model_mismatch"
        assert "PRIVATE" not in json.dumps(row[2])
    finally:
        await repo.close()


@pytest.mark.parametrize("wire_transport", [False, True])
async def test_real_role_postgres_checkpoint_recovery_reuses_authorized_calls(
    tmp_path, monkeypatch, wire_transport,
):
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
    fixture = await asyncio.to_thread(_read_version_fixture)
    async with await psycopg.AsyncConnection.connect(URL) as conn:
        await conn.execute(
            "UPDATE agent_workflow_run SET question=%s WHERE run_id=%s", (fixture["question"], run)
        )
    records = [r for r in fixture["records"] if r["record_type"] == "Evidence"]
    oracle = ObservationDrivenModel()
    calls = []
    client = None
    model = oracle
    if wire_transport:
        install_observer(tmp_path / "identity.json", monkeypatch, "deepseek-flash")
        client = httpx.AsyncClient(transport=oracle_transport(oracle, calls))
        model = fixture_model(client)
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
                (
                    "UPDATE agent_workflow_run SET claim_token=%s::uuid,lease_until=now()+interval "
                    "'60 seconds' WHERE run_id=%s"
                ),
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
                update={
                    "claim_token": recovered,
                    "status": WorkflowStatus.WORKING,
                    "stage": "WORKING",
                }
            )
        )
        assert (
            finalizer.requests[-1].status == "SUCCEEDED"
            and len(tools.calls) == 1
            and len(evidence.publications) == 1
        )
        assert finalizer.requests[-1].usage["modelCalls"] == 5
        if wire_transport:
            assert len(calls) == len(oracle.requests) == 5
            assert len(oracle.requests[1].payload["candidates"]) >= 2
    finally:
        if client is not None:
            await client.aclose()
        await repository.close()
        await connections.close()


async def test_json_sql_replay_is_bound_to_actual_wire_and_contract(tmp_path, monkeypatch):
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)
    identity_path = tmp_path / "identity.json"
    install_observer(identity_path, monkeypatch, "deepseek-flash")
    calls = []

    async def guard():
        assert (await repo.assert_active_claim(run, claim))[0]

    try:
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda req: calls.append(req) or httpx.Response(200, json=envelope())
        )) as client:
            settings = json_settings()
            model = OpenAIAgentModel(settings, client)
            gateway = AgentBudgetGateway(run_id=run, claim_token=claim, budget=budget,
                                         ledger=ledger, model=model, guard=guard)
            result = await gateway.model_call("model:wire", "DECISION", JSON_REQUEST)
            assert await gateway.model_call("model:wire", "DECISION", JSON_REQUEST) == result
            assert len(calls) == 1
            async with await psycopg.AsyncConnection.connect(URL) as conn:
                row = await (await conn.execute(
                    "SELECT status,input_reserved,request_hash,safe_result "
                    "FROM agent_research_operation WHERE run_id=%s", (run,),
                )).fetchone()
            assert row[0] == "SETTLED" and row[1] == len(calls[0].content) + 1024
            assert row[2] == hashlib.sha256(model.prepare(JSON_REQUEST).identity).hexdigest()
            assert row[3]["value"] == result.value
            changes = [
                JSON_REQUEST.model_copy(update={"instruction": "New trusted instruction"}),
                JSON_REQUEST.model_copy(update={"payload": {"candidates": []}}),
                JSON_REQUEST.model_copy(update={"result_schema": {"type": "object"}}),
                JSON_REQUEST.model_copy(update={"request_binding": {"run_scope": "other"}}),
            ]
            for changed in changes:
                with pytest.raises(WorkflowExecutionError) as error:
                    await gateway.model_call("model:wire", "DECISION", changed)
                assert error.value.error_code == "AGENT_REPLAY_MISMATCH"
            # Explicitly change transport: a previously settled decision must not be reused.
            settings.agent_result_transport = "function_call"
            with pytest.raises(WorkflowExecutionError) as error:
                await gateway.model_call("model:wire", "DECISION", JSON_REQUEST)
            assert error.value.error_code == "AGENT_REPLAY_MISMATCH"
            assert len(calls) == 1
        usage = await ledger.summary(run, claim)
        assert usage["modelCalls"] == 1 and usage["toolCalls"] == 0
        assert usage["inputTokens"] == 41 and usage["outputTokens"] == 7
        assert len(json.loads(identity_path.read_text())["receipts"]) == 1
    finally:
        await repo.close()


@pytest.mark.parametrize("content,measured", [
    ('{"action":"read_source"}', {"prompt_tokens": 41, "completion_tokens": 7}),
    ('{"action":"read_source","source_id":"primary-a","action":"finish"}',
     {"prompt_tokens": 41, "completion_tokens": 7}),
    ("", {"prompt_tokens": 0, "completion_tokens": -1}),
    ("[]", {}),
])
async def test_json_sql_invalid_results_preserve_partial_usage_and_no_tools(
    tmp_path, monkeypatch, content, measured,
):
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)
    path = tmp_path / "identity.json"
    install_observer(path, monkeypatch, "deepseek-flash")
    calls = []

    async def guard():
        assert (await repo.assert_active_claim(run, claim))[0]

    try:
        async with httpx.AsyncClient(transport=httpx.MockTransport(
            lambda req: calls.append(req) or httpx.Response(
                200, json=envelope(content, usage=measured),
            )
        )) as client:
            gateway = AgentBudgetGateway(run_id=run, claim_token=claim, budget=budget,
                                         ledger=ledger, model=fixture_model(client), guard=guard)
            with pytest.raises(ModelCallError):
                await gateway.model_call("model:invalid-json", "DECISION", JSON_REQUEST)
            with pytest.raises(WorkflowExecutionError) as replay:
                await gateway.model_call("model:invalid-json", "DECISION", JSON_REQUEST)
            assert replay.value.error_code == "AGENT_MODEL_NOT_RETRYABLE"
        assert len(calls) == 1
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            rows = await (await conn.execute(
                "SELECT kind,status,safe_result,actual_usage FROM agent_research_operation "
                "WHERE run_id=%s", (run,),
            )).fetchall()
        assert len(rows) == 1 and rows[0][:3] == ("MODEL", "UNKNOWN", None)
        receipt = rows[0][3]
        assert receipt["model_failure"]["retryable"] is False
        assert receipt.get("input_tokens") == measured.get("prompt_tokens")
        assert receipt.get("output_tokens") == (measured.get("completion_tokens")
                                                if measured.get("completion_tokens", -1) >= 0
                                                else None)
        assert "source_id" not in json.dumps(receipt)
        assert (await ledger.summary(run, claim))["toolCalls"] == 0
        assert json.loads(path.read_text())["receipts"][0]["identity_matches"] is True
    finally:
        await repo.close()


async def test_json_sql_inflight_cancellation_leaves_reserved_not_reissued(tmp_path, monkeypatch):
    budget = AgentRunBudget(runtime="agent")
    repo, ledger, run, claim = await seed(budget)
    install_observer(tmp_path / "identity.json", monkeypatch, "deepseek-flash")
    entered = asyncio.Event()
    calls = []

    async def transport(wire):
        calls.append(wire)
        entered.set()
        await asyncio.Event().wait()

    async def guard():
        assert (await repo.assert_active_claim(run, claim))[0]

    try:
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
            gateway = AgentBudgetGateway(run_id=run, claim_token=claim, budget=budget,
                                         ledger=ledger, model=fixture_model(client), guard=guard)
            operation = asyncio.create_task(
                gateway.model_call("model:cancel", "DECISION", JSON_REQUEST)
            )
            await asyncio.wait_for(entered.wait(), timeout=2)
            operation.cancel()
            with pytest.raises(asyncio.CancelledError):
                await operation
            with pytest.raises(WorkflowExecutionError) as error:
                await gateway.model_call("model:cancel", "DECISION", JSON_REQUEST)
            assert error.value.error_code == "AGENT_OPERATION_IN_PROGRESS"
        async with await psycopg.AsyncConnection.connect(URL) as conn:
            row = await (await conn.execute(
                "SELECT status,safe_result,actual_usage FROM agent_research_operation "
                "WHERE run_id=%s",
                (run,),
            )).fetchone()
        assert row[0] == "RESERVED" and row[1] is None
        assert len(calls) == 1 and (await ledger.summary(run, claim))["toolCalls"] == 0
    finally:
        await repo.close()
