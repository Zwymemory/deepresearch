from __future__ import annotations

import asyncio
import os
import secrets
from datetime import UTC, datetime, timedelta
from uuid import uuid4

import psycopg
import pytest
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
from psycopg import sql
from psycopg.conninfo import make_conninfo
from psycopg.types.json import Jsonb

from deepresearch_workflow.domain import (
    PlannedTaskDraft,
    PlanOutput,
    ReviewOutput,
    RunBudget,
    ToolExecutionResult,
    ToolName,
    UsageDelta,
)
from deepresearch_workflow.graph import DurableResearchGraph, GraphRuntime
from deepresearch_workflow.ports import (
    BudgetClaimConflictError,
    BudgetLimitExceededError,
    RepositoryEventSink,
)
from deepresearch_workflow.repository import (
    PostgresWorkflowRepository,
    ReceiptConflictError,
    checkpoint_pool,
)
from deepresearch_workflow.runner import WorkflowRunner
from deepresearch_workflow.settings import Settings

from .test_graph import FakeTools, HappyModel

DATABASE_URL = os.getenv("TEST_DATABASE_URL", "")
pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(not DATABASE_URL, reason="TEST_DATABASE_URL is not configured"),
]


DDL = """
DROP SCHEMA IF EXISTS langgraph CASCADE;
CREATE SCHEMA langgraph;
DROP TABLE IF EXISTS agent_workflow_budget_reservation,
    agent_workflow_tool_receipt, agent_workflow_event,
    agent_workflow_grant, agent_workflow_run CASCADE;
CREATE TABLE agent_workflow_run (
    run_id varchar(64) PRIMARY KEY,
    session_id varchar(64) NOT NULL,
    user_id varchar(160) NOT NULL,
    question text NOT NULL,
    context_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    endpoint varchar(120) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    graph_thread_id varchar(64) NOT NULL,
    status varchar(32) NOT NULL,
    stage varchar(32) NOT NULL,
    deadline_at timestamptz NOT NULL,
    cancel_requested boolean NOT NULL DEFAULT false,
    requested_scopes text[] NOT NULL DEFAULT ARRAY[]::text[],
    grant_id varchar(64) NOT NULL,
    claim_token uuid,
    claimed_by varchar(128),
    lease_until timestamptz,
    heartbeat_at timestamptz,
    final_response jsonb,
    usage jsonb NOT NULL DEFAULT '{}'::jsonb,
    budget jsonb NOT NULL DEFAULT
        '{"maxTasks":4,"maxConcurrency":2,"maxRevisionRounds":1,"maxModelCalls":24,"maxToolCalls":16,"maxTokens":100000,"maxCostCny":1.0,"deadlineSeconds":120}'::jsonb,
    error_code varchar(64),
    error_message text,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, endpoint, idempotency_key)
);
CREATE TABLE agent_workflow_grant (
    grant_id varchar(64) PRIMARY KEY,
    run_id varchar(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    subject varchar(160) NOT NULL,
    scopes text[] NOT NULL,
    policy_version integer NOT NULL DEFAULT 1,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE agent_workflow_event (
    event_id bigserial PRIMARY KEY,
    run_id varchar(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    event_key varchar(160) NOT NULL,
    role varchar(32),
    task_id varchar(64),
    type varchar(64) NOT NULL,
    safe_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    claim_token uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, event_key)
);
CREATE TABLE agent_workflow_tool_receipt (
    run_id varchar(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    call_id varchar(160) NOT NULL,
    task_id varchar(64) NOT NULL,
    tool_name varchar(64) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    safe_result jsonb,
    error_code varchar(64),
    claim_token uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    PRIMARY KEY (run_id, call_id)
);
CREATE TABLE agent_workflow_budget_reservation (
    run_id varchar(64) NOT NULL REFERENCES agent_workflow_run(run_id) ON DELETE CASCADE,
    operation_key varchar(160) NOT NULL,
    attempt integer NOT NULL CHECK (attempt > 0),
    kind varchar(16) NOT NULL CHECK (kind IN ('MODEL','TOOL')),
    CHECK (
        (kind = 'MODEL' AND operation_key LIKE 'model:%')
        OR (kind = 'TOOL' AND operation_key LIKE 'tool-%')
    ),
    status varchar(16) NOT NULL CHECK (status IN ('RESERVED','SETTLED','UNKNOWN')),
    claim_token uuid NOT NULL,
    origin_claim_token uuid NOT NULL,
    actual_usage jsonb,
    safe_result jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    settled_at timestamptz,
    PRIMARY KEY (run_id, operation_key, attempt)
);
"""


async def reset_database() -> None:
    async with await psycopg.AsyncConnection.connect(DATABASE_URL, autocommit=True) as connection:
        await connection.execute(DDL)


async def insert_run(run_id: str, budget: dict[str, object] | None = None) -> None:
    async with await psycopg.AsyncConnection.connect(DATABASE_URL, autocommit=True) as connection:
        await connection.execute(
            """
            INSERT INTO agent_workflow_run(
                run_id, session_id, user_id, question, endpoint, idempotency_key,
                request_fingerprint, graph_thread_id, status, stage, deadline_at,
                requested_scopes, grant_id, budget
            ) VALUES (
                %s, 'session', 'tenant:user', 'question', '/api/research/workflows',
                %s, repeat('a', 64), %s, 'QUEUED', 'QUEUED',
                %s, ARRAY['kb_search','calculator'], %s, %s
            )
            """,
            (
                run_id,
                f"idempotency-{run_id}",
                run_id,
                datetime.now(UTC) + timedelta(minutes=2),
                f"grant-{run_id}",
                Jsonb(budget or RunBudget().model_dump(mode="json", by_alias=True)),
            ),
        )


@pytest.mark.asyncio
async def test_expired_lease_reclaims_with_fencing_and_session_lock() -> None:
    await reset_database()
    run_budget = RunBudget(
        max_tasks=2,
        max_concurrency=1,
        max_revision_rounds=0,
        max_model_calls=12,
        max_tool_calls=8,
        max_tokens=50_000,
        max_cost_cny=0.5,
        deadline_seconds=90,
    )
    await insert_run("run-lock", run_budget.model_dump(mode="json", by_alias=True))

    first_repository = PostgresWorkflowRepository(DATABASE_URL)
    second_repository = PostgresWorkflowRepository(DATABASE_URL)
    await first_repository.open()
    await second_repository.open()
    try:
        first = await first_repository.claim_next("runner-1", 30)
        assert first is not None
        assert first.budget == run_budget
        acquired_by_second = asyncio.Event()

        async def acquire_second_lock() -> None:
            async with second_repository.run_lock("run-lock"):
                acquired_by_second.set()

        async with first_repository.run_lock("run-lock"):
            async with first_repository.pool.connection() as connection:
                await connection.execute(
                    "UPDATE agent_workflow_run SET lease_until = now() - interval '1 second' "
                    "WHERE run_id = 'run-lock'"
                )
            second = await second_repository.claim_next("runner-2", 30)
            assert second is not None
            assert second.budget == run_budget
            assert second.claim_token != first.claim_token
            assert await first_repository.assert_active_claim(first.run_id, first.claim_token) == (
                False,
                False,
            )
            assert not await first_repository.update_progress(
                first.run_id,
                first.claim_token,
                status="WORKING",
                stage="WORKING",
                usage=await second_repository.current_usage(second.run_id, second.claim_token),
            )
            with pytest.raises(ReceiptConflictError):
                await first_repository.begin_tool_receipt(
                    run_id=first.run_id,
                    claim_token=first.claim_token,
                    call_id="stale-call",
                    task_id="task-01",
                    tool_name="kb_search",
                    request_fingerprint="a" * 64,
                )
            await second_repository.begin_tool_receipt(
                run_id=second.run_id,
                claim_token=second.claim_token,
                call_id="active-call",
                task_id="task-01",
                tool_name="kb_search",
                request_fingerprint="b" * 64,
            )
            with pytest.raises(ReceiptConflictError):
                await first_repository.complete_tool_receipt(
                    run_id=first.run_id,
                    claim_token=first.claim_token,
                    call_id="active-call",
                    result=ToolExecutionResult(call_id="active-call"),
                )

            waiting = asyncio.create_task(acquire_second_lock())
            await asyncio.sleep(0.2)
            assert not acquired_by_second.is_set()

        await asyncio.wait_for(waiting, timeout=2)
        assert acquired_by_second.is_set()
    finally:
        await first_repository.close()
        await second_repository.close()


@pytest.mark.asyncio
async def test_durable_budget_reservation_serializes_parallel_model_calls() -> None:
    await reset_database()
    budget = RunBudget(max_model_calls=1)
    await insert_run("run-budget-race", budget.model_dump(mode="json", by_alias=True))
    repository = PostgresWorkflowRepository(DATABASE_URL)
    await repository.open()
    try:
        claimed = await repository.claim_next("runner-budget-race", 30)
        assert claimed is not None

        async def reserve(operation_key: str):
            return await repository.reserve_model_call(
                run_id=claimed.run_id,
                claim_token=claimed.claim_token,
                operation_key=operation_key,
                budget=budget,
            )

        results = await asyncio.gather(
            reserve("model:worker:task-01:prepare"),
            reserve("model:worker:task-02:prepare"),
            return_exceptions=True,
        )

        assert sum(not isinstance(result, BaseException) for result in results) == 1
        assert sum(isinstance(result, BudgetLimitExceededError) for result in results) == 1
        async with repository.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT count(*) AS count
                FROM agent_workflow_budget_reservation
                WHERE run_id = 'run-budget-race' AND kind = 'MODEL'
                """
            )
            row = await cursor.fetchone()
        assert row is not None and row["count"] == 1
    finally:
        await repository.close()


@pytest.mark.asyncio
async def test_durable_budget_recovery_charges_unknown_model_and_reuses_tool_call() -> None:
    await reset_database()
    budget = RunBudget(max_model_calls=2, max_tool_calls=1)
    await insert_run("run-budget-recovery", budget.model_dump(mode="json", by_alias=True))
    repository = PostgresWorkflowRepository(DATABASE_URL)
    await repository.open()
    try:
        first = await repository.claim_next("runner-budget-first", 30)
        assert first is not None
        first_model = await repository.reserve_model_call(
            run_id=first.run_id,
            claim_token=first.claim_token,
            operation_key="model:planner",
            budget=budget,
        )
        assert first_model.attempt == 1
        assert (
            await repository.reserve_tool_call(
                run_id=first.run_id,
                claim_token=first.claim_token,
                operation_key="tool-deterministic-call",
                budget=budget,
            )
            == 1
        )

        async with repository.pool.connection() as connection:
            await connection.execute(
                """
                UPDATE agent_workflow_run
                SET lease_until = now() - interval '1 second'
                WHERE run_id = 'run-budget-recovery'
                """
            )
        second = await repository.claim_next("runner-budget-second", 30)
        assert second is not None
        assert second.claim_token != first.claim_token

        with pytest.raises(BudgetClaimConflictError):
            await repository.settle_model_call(
                run_id=first.run_id,
                claim_token=first.claim_token,
                operation_key="model:planner",
                attempt=1,
                value={"ignored": True},
                usage=UsageDelta(model_calls=1),
            )

        retry = await repository.reserve_model_call(
            run_id=second.run_id,
            claim_token=second.claim_token,
            operation_key="model:planner",
            budget=budget,
        )
        assert retry.attempt == 2
        assert retry.unknown_attempts == 1
        planned = PlanOutput(
            tasks=[
                PlannedTaskDraft(
                    objective="Find durable evidence",
                    query="durable budget",
                    tool=ToolName.KB_SEARCH,
                )
            ],
            summary="Recovered typed result",
        )
        usage = UsageDelta(
            model_calls=1,
            input_tokens=10,
            output_tokens=5,
            cost_cny=0.02,
        )
        await repository.settle_model_call(
            run_id=second.run_id,
            claim_token=second.claim_token,
            operation_key="model:planner",
            attempt=retry.attempt,
            value=planned.model_dump(mode="json"),
            usage=usage,
        )
        replay = await repository.reserve_model_call(
            run_id=second.run_id,
            claim_token=second.claim_token,
            operation_key="model:planner",
            budget=budget,
        )
        assert replay.replay
        assert replay.attempt == 2
        assert replay.unknown_attempts == 1
        assert replay.replay_value == planned.model_dump(mode="json")
        assert replay.replay_usage == usage
        with pytest.raises(BudgetLimitExceededError):
            await repository.reserve_model_call(
                run_id=second.run_id,
                claim_token=second.claim_token,
                operation_key="model:reviewer:round-0",
                budget=budget,
            )

        reconciled = await repository.reconcile_call_usage(
            second.run_id,
            second.claim_token,
            UsageDelta(),
        )
        assert reconciled.model_calls == 2
        assert reconciled.tool_calls == 1
        assert reconciled.total_tokens == 15
        assert reconciled.cost_cny == 0.02

        # V9 makes this call_id replayable in Java. The durable budget row is rebound
        # to the new claim instead of charging a second logical tool call.
        assert (
            await repository.reserve_tool_call(
                run_id=second.run_id,
                claim_token=second.claim_token,
                operation_key="tool-deterministic-call",
                budget=budget,
            )
            == 1
        )
        await repository.settle_tool_call(
            run_id=second.run_id,
            claim_token=second.claim_token,
            operation_key="tool-deterministic-call",
            attempt=1,
        )
        with pytest.raises(BudgetLimitExceededError):
            await repository.reserve_tool_call(
                run_id=second.run_id,
                claim_token=second.claim_token,
                operation_key="tool-another-call",
                budget=budget,
            )

        async with repository.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT kind, attempt, status
                FROM agent_workflow_budget_reservation
                WHERE run_id = 'run-budget-recovery'
                ORDER BY kind, attempt
                """
            )
            rows = await cursor.fetchall()
        assert [(row["kind"], row["attempt"], row["status"]) for row in rows] == [
            ("MODEL", 1, "UNKNOWN"),
            ("MODEL", 2, "SETTLED"),
            ("TOOL", 1, "SETTLED"),
        ]
    finally:
        await repository.close()


@pytest.mark.asyncio
async def test_model_retry_marks_unknown_before_new_attempt_on_same_claim() -> None:
    await reset_database()
    budget = RunBudget(max_model_calls=2)
    await insert_run("run-model-retry", budget.model_dump(mode="json", by_alias=True))
    repository = PostgresWorkflowRepository(DATABASE_URL)
    await repository.open()
    try:
        claimed = await repository.claim_next("runner-model-retry", 30)
        assert claimed is not None
        first = await repository.reserve_model_call(
            run_id=claimed.run_id,
            claim_token=claimed.claim_token,
            operation_key="model:reviewer:round-0",
            budget=budget,
        )
        assert first.attempt == 1

        await repository.mark_model_call_unknown(
            run_id=claimed.run_id,
            claim_token=claimed.claim_token,
            operation_key="model:reviewer:round-0",
            attempt=first.attempt,
        )
        # The transition is idempotent, but never changes a settled receipt.
        await repository.mark_model_call_unknown(
            run_id=claimed.run_id,
            claim_token=claimed.claim_token,
            operation_key="model:reviewer:round-0",
            attempt=first.attempt,
        )

        second = await repository.reserve_model_call(
            run_id=claimed.run_id,
            claim_token=claimed.claim_token,
            operation_key="model:reviewer:round-0",
            budget=budget,
        )
        assert second.attempt == 2
        assert second.unknown_attempts == 1

        review = ReviewOutput(sufficient=True, summary="retry succeeded")
        usage = UsageDelta(model_calls=1, input_tokens=8, output_tokens=2)
        await repository.settle_model_call(
            run_id=claimed.run_id,
            claim_token=claimed.claim_token,
            operation_key="model:reviewer:round-0",
            attempt=second.attempt,
            value=review.model_dump(mode="json"),
            usage=usage,
        )

        replay = await repository.reserve_model_call(
            run_id=claimed.run_id,
            claim_token=claimed.claim_token,
            operation_key="model:reviewer:round-0",
            budget=budget,
        )
        assert replay.replay
        assert replay.attempt == 2
        assert replay.unknown_attempts == 1
        assert replay.replay_value == review.model_dump(mode="json")

        async with repository.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT attempt, status
                FROM agent_workflow_budget_reservation
                WHERE run_id = 'run-model-retry'
                ORDER BY attempt
                """
            )
            rows = await cursor.fetchall()
        assert [(row["attempt"], row["status"]) for row in rows] == [
            (1, "UNKNOWN"),
            (2, "SETTLED"),
        ]
    finally:
        await repository.close()


@pytest.mark.asyncio
async def test_langgraph_sync_checkpoint_is_persisted_and_readable() -> None:
    await reset_database()
    await insert_run("run-checkpoint")
    repository = PostgresWorkflowRepository(DATABASE_URL)
    await repository.open()
    checkpointer_connections = None
    try:
        claimed = await repository.claim_next("runner-checkpoint", 30)
        assert claimed is not None
        await repository.check_checkpoint_schema("langgraph")
        checkpointer_connections = checkpoint_pool(DATABASE_URL, "langgraph")
        await checkpointer_connections.open(wait=True)
        saver = AsyncPostgresSaver(checkpointer_connections)
        await saver.setup()
        graph = DurableResearchGraph(
            model=HappyModel(),
            tools=FakeTools(),
            repository=repository,
            events=RepositoryEventSink(repository),
            settings=Settings(runner_enabled=False, model_provider="disabled"),
            budget=claimed.budget,
            runtime=GraphRuntime(claim_token=claimed.claim_token),
        ).compile(checkpointer=saver)
        config = {
            "configurable": {"thread_id": claimed.graph_thread_id},
            "max_concurrency": 2,
        }

        result = await graph.ainvoke(
            WorkflowRunner._initial_state(claimed),
            config,
            durability="sync",
        )
        snapshot = await graph.aget_state(config)

        assert result["final_status"] == "SUCCEEDED"
        assert snapshot.values["final_status"] == "SUCCEEDED"
        assert snapshot.next == ()
        async with await psycopg.AsyncConnection.connect(
            DATABASE_URL, autocommit=True
        ) as connection:
            cursor = await connection.execute("SELECT count(*) AS count FROM langgraph.checkpoints")
            row = await cursor.fetchone()
        assert row is not None and row[0] > 0
    finally:
        if checkpointer_connections is not None:
            await checkpointer_connections.close()
        await repository.close()


@pytest.mark.asyncio
async def test_restricted_role_can_setup_checkpoints_without_database_create() -> None:
    suffix = uuid4().hex[:12]
    role = f"checkpoint_role_{suffix}"
    schema = f"checkpoint_schema_{suffix}"
    password = secrets.token_hex(32)
    restricted_url = make_conninfo(DATABASE_URL, user=role, password=password)
    repository = None
    checkpointer_connections = None

    try:
        async with await psycopg.AsyncConnection.connect(DATABASE_URL, autocommit=True) as admin:
            database_cursor = await admin.execute("SELECT current_database()")
            database_row = await database_cursor.fetchone()
            assert database_row is not None
            database = database_row[0]
            await admin.execute(
                sql.SQL("CREATE ROLE {} LOGIN PASSWORD {}").format(
                    sql.Identifier(role), sql.Literal(password)
                )
            )
            await admin.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(schema)))
            await admin.execute(
                sql.SQL("REVOKE ALL ON SCHEMA {} FROM PUBLIC").format(sql.Identifier(schema))
            )
            await admin.execute(
                sql.SQL("GRANT USAGE, CREATE ON SCHEMA {} TO {}").format(
                    sql.Identifier(schema), sql.Identifier(role)
                )
            )
            await admin.execute(
                sql.SQL("GRANT CONNECT ON DATABASE {} TO {}").format(
                    sql.Identifier(database), sql.Identifier(role)
                )
            )
            await admin.execute(
                sql.SQL("REVOKE CREATE ON DATABASE {} FROM {}").format(
                    sql.Identifier(database), sql.Identifier(role)
                )
            )

        repository = PostgresWorkflowRepository(restricted_url)
        await repository.open()
        await repository.check_checkpoint_schema(schema)

        async with repository.pool.connection() as connection:
            privilege_cursor = await connection.execute(
                """
                SELECT has_database_privilege(
                    current_user, current_database(), 'CREATE'
                ) AS has_database_create
                """
            )
            privilege_row = await privilege_cursor.fetchone()
        assert privilege_row is not None
        assert privilege_row["has_database_create"] is False

        checkpointer_connections = checkpoint_pool(restricted_url, schema)
        await checkpointer_connections.open(wait=True)
        saver = AsyncPostgresSaver(checkpointer_connections)
        await saver.setup()

        async with repository.pool.connection() as connection:
            table_cursor = await connection.execute(
                """
                SELECT to_regclass(%s) IS NOT NULL AS checkpoints_exist
                """,
                (f"{schema}.checkpoints",),
            )
            table_row = await table_cursor.fetchone()
        assert table_row is not None
        assert table_row["checkpoints_exist"] is True
    finally:
        if checkpointer_connections is not None:
            await checkpointer_connections.close()
        if repository is not None:
            await repository.close()
        async with await psycopg.AsyncConnection.connect(DATABASE_URL, autocommit=True) as admin:
            await admin.execute(
                sql.SQL("DROP SCHEMA IF EXISTS {} CASCADE").format(sql.Identifier(schema))
            )
            await admin.execute(sql.SQL("DROP OWNED BY {}").format(sql.Identifier(role)))
            await admin.execute(sql.SQL("DROP ROLE IF EXISTS {}").format(sql.Identifier(role)))
