from __future__ import annotations

import asyncio
import json
import re
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any
from uuid import uuid4

from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import AsyncConnectionPool

from .domain import (
    ClaimedRun,
    EventRecord,
    ModelBudgetReservation,
    RunBudget,
    ToolExecutionResult,
    UsageDelta,
)
from .ports import (
    BudgetClaimConflictError,
    BudgetLimitExceededError,
    BudgetOperationInProgressError,
    BudgetOperationInvalidError,
)

REQUIRED_TABLES = (
    "agent_workflow_run",
    "agent_workflow_event",
    "agent_workflow_grant",
    "agent_workflow_budget_reservation",
    "agent_workflow_tool_receipt",
)

BUDGET_OPERATION_KEY_PATTERN = re.compile(r"^[a-z0-9][a-z0-9:._-]{0,159}$")
MAX_MODEL_SAFE_RESULT_BYTES = 120_000


class SchemaNotReadyError(RuntimeError):
    pass


class ReceiptConflictError(RuntimeError):
    pass


class PostgresWorkflowRepository:
    """All mutating run operations are fenced by the current random claim token."""

    def __init__(self, database_url: str) -> None:
        self.pool = AsyncConnectionPool(
            conninfo=database_url,
            min_size=1,
            max_size=8,
            open=False,
            kwargs={"autocommit": True, "row_factory": dict_row},
        )

    async def open(self) -> None:
        await self.pool.open(wait=True)

    async def close(self) -> None:
        await self.pool.close()

    async def check_checkpoint_schema(self, schema: str) -> None:
        """Require Flyway to provision the checkpoint schema and least privileges."""

        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT schema_oid IS NOT NULL AS schema_exists,
                       COALESCE(
                           has_schema_privilege(current_user, schema_oid, 'USAGE'),
                           FALSE
                       ) AS has_usage,
                       COALESCE(
                           has_schema_privilege(current_user, schema_oid, 'CREATE'),
                           FALSE
                       ) AS has_create
                FROM (SELECT to_regnamespace(%s) AS schema_oid) AS resolved
                """,
                (schema,),
            )
            row = await cursor.fetchone()

        if not row or not row["schema_exists"]:
            raise SchemaNotReadyError(
                f"missing checkpoint schema: {schema}; run Flyway migrations first"
            )
        missing_privileges = [
            privilege
            for privilege, present in (
                ("USAGE", row["has_usage"]),
                ("CREATE", row["has_create"]),
            )
            if not present
        ]
        if missing_privileges:
            raise SchemaNotReadyError(
                "workflow database role lacks "
                f"{', '.join(missing_privileges)} on checkpoint schema: {schema}"
            )

    async def check_schema(self) -> None:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name = ANY(%s)
                """,
                (list(REQUIRED_TABLES),),
            )
            rows = await cursor.fetchall()
        present = {row["table_name"] for row in rows}
        missing = sorted(set(REQUIRED_TABLES) - present)
        if missing:
            raise SchemaNotReadyError(f"missing workflow tables: {', '.join(missing)}")

    async def ping(self) -> bool:
        try:
            async with self.pool.connection() as connection:
                cursor = await connection.execute("SELECT 1 AS ok")
                row = await cursor.fetchone()
            return bool(row and row["ok"] == 1)
        except Exception:
            return False

    async def claim_next(self, instance_id: str, lease_seconds: int) -> ClaimedRun | None:
        claim_token = uuid4()
        query = """
            WITH candidate AS (
                SELECT run_id
                FROM agent_workflow_run
                WHERE status IN (
                    'QUEUED', 'PLANNING', 'WORKING', 'REVIEWING',
                    'SYNTHESIZING', 'FINALIZING'
                )
                  AND cancel_requested = FALSE
                  AND (claim_token IS NULL OR lease_until IS NULL OR lease_until < now())
                ORDER BY created_at, run_id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE agent_workflow_run AS run
            SET claim_token = %s,
                claimed_by = %s,
                lease_until = now() + (%s * interval '1 second'),
                heartbeat_at = now(),
                version = version + 1,
                updated_at = now()
            FROM candidate
            WHERE run.run_id = candidate.run_id
            RETURNING run.run_id, run.session_id, run.user_id, run.question,
                      run.context_snapshot, run.endpoint, run.graph_thread_id,
                      run.requested_scopes, run.grant_id, run.budget, run.claim_token,
                      run.deadline_at, run.status, run.stage
        """
        async with self.pool.connection() as connection:
            async with connection.transaction():
                cursor = await connection.execute(
                    query,
                    (claim_token, instance_id, lease_seconds),
                )
                row = await cursor.fetchone()
        if row is None:
            return None
        row["claim_token"] = str(row["claim_token"])
        row["context_snapshot"] = row.get("context_snapshot") or {}
        row["requested_scopes"] = row.get("requested_scopes") or []
        row["budget"] = row.get("budget") or {}
        row["graph_thread_id"] = row.get("graph_thread_id") or row["run_id"]
        return ClaimedRun.model_validate(row)

    @asynccontextmanager
    async def run_lock(self, run_id: str) -> AsyncIterator[None]:
        """Keep a session-level lock on a dedicated connection until execution exits.

        Fencing tokens protect each write.  This lock additionally prevents a reclaimed
        run from starting while the old process is still returning from an in-flight
        model/tool request.
        """

        async with self.pool.connection() as connection:
            acquired = False
            try:
                while not acquired:
                    cursor = await connection.execute(
                        "SELECT pg_try_advisory_lock(hashtextextended(%s, 0)) AS acquired",
                        (run_id,),
                    )
                    row = await cursor.fetchone()
                    acquired = bool(row and row["acquired"])
                    if not acquired:
                        await asyncio.sleep(0.1)
                yield
            finally:
                if acquired:

                    async def release() -> None:
                        try:
                            cursor = await connection.execute(
                                "SELECT pg_advisory_unlock(hashtextextended(%s, 0)) AS released",
                                (run_id,),
                            )
                            row = await cursor.fetchone()
                            if not row or not row["released"]:
                                raise RuntimeError("session advisory lock was not held")
                        except BaseException:
                            # Closing is the only safe pool reset if explicit unlock fails.
                            await connection.close()
                            raise

                    release_task = asyncio.create_task(release())
                    try:
                        await asyncio.shield(release_task)
                    except asyncio.CancelledError:
                        await asyncio.gather(release_task, return_exceptions=True)
                        raise

    async def heartbeat(
        self,
        run_id: str,
        claim_token: str,
        instance_id: str,
        lease_seconds: int,
    ) -> bool:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                UPDATE agent_workflow_run
                SET heartbeat_at = now(),
                    lease_until = now() + (%s * interval '1 second'),
                    updated_at = now(),
                    version = version + 1
                WHERE run_id = %s
                  AND claim_token = %s::uuid
                  AND claimed_by = %s
                  AND lease_until >= now()
                  AND status IN (
                    'QUEUED', 'PLANNING', 'WORKING', 'REVIEWING',
                    'SYNTHESIZING', 'FINALIZING'
                  )
                """,
                (lease_seconds, run_id, claim_token, instance_id),
            )
        return cursor.rowcount == 1

    async def assert_active_claim(self, run_id: str, claim_token: str) -> tuple[bool, bool]:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT claim_token::text AS claim_token, cancel_requested, status,
                       lease_until >= now() AS lease_active
                FROM agent_workflow_run
                WHERE run_id = %s
                """,
                (run_id,),
            )
            row = await cursor.fetchone()
        if (
            row is None
            or row["claim_token"] != claim_token
            or not row["lease_active"]
            or row["status"]
            in {
                "SUCCEEDED",
                "INSUFFICIENT_EVIDENCE",
                "FAILED",
                "CANCELLED",
                "TIMED_OUT",
                "BUDGET_EXCEEDED",
            }
        ):
            return False, False
        return True, bool(row["cancel_requested"])

    async def update_progress(
        self,
        run_id: str,
        claim_token: str,
        *,
        status: str,
        stage: str,
        usage: UsageDelta,
    ) -> bool:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                UPDATE agent_workflow_run
                SET status = %s,
                    stage = %s,
                    usage = %s,
                    updated_at = now(),
                    version = version + 1
                WHERE run_id = %s
                  AND claim_token = %s::uuid
                  AND lease_until >= now()
                  AND status IN (
                    'QUEUED', 'PLANNING', 'WORKING', 'REVIEWING',
                    'SYNTHESIZING', 'FINALIZING'
                  )
                """,
                (status, stage, Jsonb(usage.model_dump(mode="json")), run_id, claim_token),
            )
        return cursor.rowcount == 1

    async def current_usage(self, run_id: str, claim_token: str) -> UsageDelta:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT usage
                FROM agent_workflow_run
                WHERE run_id = %s
                  AND claim_token = %s::uuid
                  AND lease_until >= now()
                """,
                (run_id, claim_token),
            )
            row = await cursor.fetchone()
        if row is None:
            raise ReceiptConflictError("workflow claim expired while reading usage")
        return UsageDelta.model_validate(row.get("usage") or {})

    async def reserve_model_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        budget: RunBudget,
    ) -> ModelBudgetReservation:
        """Atomically reserve one model attempt or replay its settled typed result.

        A RESERVED attempt from a previous claim is ambiguous: the provider may have
        accepted the request before the process died. It is converted to UNKNOWN and
        remains charged before a new attempt is considered.
        """

        self._validate_budget_operation_key(operation_key, "MODEL")

        exhausted = False
        async with self.pool.connection() as connection:
            async with connection.transaction():
                stored_budget = await self._lock_active_budget_run(connection, run_id, claim_token)
                max_calls = min(stored_budget.max_model_calls, budget.max_model_calls)
                cursor = await connection.execute(
                    """
                    SELECT attempt, status, claim_token::text AS claim_token,
                           actual_usage, safe_result
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND operation_key = %s AND kind = 'MODEL'
                    ORDER BY attempt DESC
                    LIMIT 1
                    """,
                    (run_id, operation_key),
                )
                latest = await cursor.fetchone()
                if latest and latest["status"] == "SETTLED":
                    unknown_attempts = await self._unknown_model_attempts(
                        connection, run_id, operation_key
                    )
                    return ModelBudgetReservation(
                        attempt=latest["attempt"],
                        unknown_attempts=unknown_attempts,
                        replay_value=latest["safe_result"],
                        replay_usage=UsageDelta.model_validate(latest["actual_usage"]),
                    )
                if latest and latest["status"] == "RESERVED":
                    if latest["claim_token"] == claim_token:
                        raise BudgetOperationInProgressError(
                            "model operation already has an in-flight reservation"
                        )
                    await connection.execute(
                        """
                        UPDATE agent_workflow_budget_reservation
                        SET status = 'UNKNOWN',
                            claim_token = %s::uuid,
                            settled_at = now()
                        WHERE run_id = %s AND operation_key = %s AND attempt = %s
                          AND kind = 'MODEL' AND status = 'RESERVED'
                        """,
                        (claim_token, run_id, operation_key, latest["attempt"]),
                    )

                cursor = await connection.execute(
                    """
                    SELECT count(*) AS call_count
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND kind = 'MODEL'
                    """,
                    (run_id,),
                )
                totals = await cursor.fetchone()
                if totals is None or totals["call_count"] >= max_calls:
                    exhausted = True
                else:
                    attempt = int(latest["attempt"]) + 1 if latest else 1
                    await connection.execute(
                        """
                        INSERT INTO agent_workflow_budget_reservation(
                            run_id, operation_key, attempt, kind, status,
                            claim_token, origin_claim_token
                        ) VALUES (%s, %s, %s, 'MODEL', 'RESERVED', %s::uuid, %s::uuid)
                        """,
                        (run_id, operation_key, attempt, claim_token, claim_token),
                    )
                    unknown_attempts = await self._unknown_model_attempts(
                        connection, run_id, operation_key
                    )
                    return ModelBudgetReservation(
                        attempt=attempt,
                        unknown_attempts=unknown_attempts,
                    )
        if exhausted:
            raise BudgetLimitExceededError("durable model-call budget exhausted")
        raise BudgetOperationInProgressError("model reservation did not produce a decision")

    async def settle_model_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
        value: dict[str, Any],
        usage: UsageDelta,
    ) -> None:
        self._validate_budget_operation_key(operation_key, "MODEL")
        self._validate_model_safe_result(value)
        async with self.pool.connection() as connection:
            async with connection.transaction():
                await self._lock_active_budget_run(connection, run_id, claim_token)
                cursor = await connection.execute(
                    """
                    SELECT status, claim_token::text AS claim_token,
                           actual_usage, safe_result
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND operation_key = %s AND attempt = %s
                      AND kind = 'MODEL'
                    """,
                    (run_id, operation_key, attempt),
                )
                row = await cursor.fetchone()
                actual = usage.model_dump(mode="json")
                if row and row["status"] == "SETTLED":
                    if row["actual_usage"] == actual and row["safe_result"] == value:
                        return
                    raise BudgetOperationInProgressError(
                        "settled model operation was reused with a different result"
                    )
                if row is None or row["status"] != "RESERVED" or row["claim_token"] != claim_token:
                    raise BudgetClaimConflictError(
                        "model reservation is not owned by the active claim"
                    )
                cursor = await connection.execute(
                    """
                    UPDATE agent_workflow_budget_reservation
                    SET status = 'SETTLED', actual_usage = %s, safe_result = %s,
                        settled_at = now()
                    WHERE run_id = %s AND operation_key = %s AND attempt = %s
                      AND kind = 'MODEL' AND status = 'RESERVED'
                      AND claim_token = %s::uuid
                    """,
                    (
                        Jsonb(actual),
                        Jsonb(value),
                        run_id,
                        operation_key,
                        attempt,
                        claim_token,
                    ),
                )
                if cursor.rowcount != 1:
                    raise BudgetClaimConflictError("model reservation changed before settlement")

    async def mark_model_call_unknown(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        """Conservatively charge an ambiguous provider attempt before retrying it.

        UNKNOWN is deliberately terminal for an individual attempt: the provider may
        have accepted work even though no typed result was returned. A later attempt
        receives a new row, while an already SETTLED result is never overwritten.
        """

        self._validate_budget_operation_key(operation_key, "MODEL")
        async with self.pool.connection() as connection:
            async with connection.transaction():
                await self._lock_active_budget_run(connection, run_id, claim_token)
                cursor = await connection.execute(
                    """
                    SELECT status, claim_token::text AS claim_token
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND operation_key = %s AND attempt = %s
                      AND kind = 'MODEL'
                    """,
                    (run_id, operation_key, attempt),
                )
                row = await cursor.fetchone()
                if row and row["status"] == "UNKNOWN":
                    return
                if row and row["status"] == "SETTLED":
                    raise BudgetOperationInProgressError(
                        "settled model attempt cannot become unknown"
                    )
                if row is None or row["status"] != "RESERVED" or row["claim_token"] != claim_token:
                    raise BudgetClaimConflictError(
                        "model reservation is not owned by the active claim"
                    )
                cursor = await connection.execute(
                    """
                    UPDATE agent_workflow_budget_reservation
                    SET status = 'UNKNOWN', settled_at = now()
                    WHERE run_id = %s AND operation_key = %s AND attempt = %s
                      AND kind = 'MODEL' AND status = 'RESERVED'
                      AND claim_token = %s::uuid
                    """,
                    (run_id, operation_key, attempt, claim_token),
                )
                if cursor.rowcount != 1:
                    raise BudgetClaimConflictError(
                        "model reservation changed before unknown settlement"
                    )

    async def reserve_tool_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        budget: RunBudget,
    ) -> int:
        """Reserve a deterministic tool call exactly once across claim changes."""

        self._validate_budget_operation_key(operation_key, "TOOL")

        async with self.pool.connection() as connection:
            async with connection.transaction():
                stored_budget = await self._lock_active_budget_run(connection, run_id, claim_token)
                max_calls = min(stored_budget.max_tool_calls, budget.max_tool_calls)
                cursor = await connection.execute(
                    """
                    SELECT attempt, status, claim_token::text AS claim_token
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND operation_key = %s AND kind = 'TOOL'
                    """,
                    (run_id, operation_key),
                )
                existing = await cursor.fetchone()
                if existing:
                    if existing["status"] == "RESERVED" and existing["claim_token"] != claim_token:
                        await connection.execute(
                            """
                            UPDATE agent_workflow_budget_reservation
                            SET claim_token = %s::uuid
                            WHERE run_id = %s AND operation_key = %s
                              AND attempt = 1 AND kind = 'TOOL' AND status = 'RESERVED'
                            """,
                            (claim_token, run_id, operation_key),
                        )
                    return int(existing["attempt"])

                cursor = await connection.execute(
                    """
                    SELECT count(*) AS call_count
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND kind = 'TOOL'
                    """,
                    (run_id,),
                )
                totals = await cursor.fetchone()
                if totals is None or totals["call_count"] >= max_calls:
                    raise BudgetLimitExceededError("durable tool-call budget exhausted")
                await connection.execute(
                    """
                    INSERT INTO agent_workflow_budget_reservation(
                        run_id, operation_key, attempt, kind, status,
                        claim_token, origin_claim_token
                    ) VALUES (%s, %s, 1, 'TOOL', 'RESERVED', %s::uuid, %s::uuid)
                    """,
                    (run_id, operation_key, claim_token, claim_token),
                )
                return 1

    async def settle_tool_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        self._validate_budget_operation_key(operation_key, "TOOL")
        async with self.pool.connection() as connection:
            async with connection.transaction():
                await self._lock_active_budget_run(connection, run_id, claim_token)
                cursor = await connection.execute(
                    """
                    SELECT status, claim_token::text AS claim_token
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s AND operation_key = %s AND attempt = %s
                      AND kind = 'TOOL'
                    """,
                    (run_id, operation_key, attempt),
                )
                row = await cursor.fetchone()
                if row and row["status"] == "SETTLED":
                    return
                if row is None or row["status"] != "RESERVED" or row["claim_token"] != claim_token:
                    raise BudgetClaimConflictError(
                        "tool reservation is not owned by the active claim"
                    )
                cursor = await connection.execute(
                    """
                    UPDATE agent_workflow_budget_reservation
                    SET status = 'SETTLED', actual_usage = %s, settled_at = now()
                    WHERE run_id = %s AND operation_key = %s AND attempt = %s
                      AND kind = 'TOOL' AND status = 'RESERVED'
                      AND claim_token = %s::uuid
                    """,
                    (
                        Jsonb(UsageDelta(tool_calls=1).model_dump(mode="json")),
                        run_id,
                        operation_key,
                        attempt,
                        claim_token,
                    ),
                )
                if cursor.rowcount != 1:
                    raise BudgetClaimConflictError("tool reservation changed before settlement")

    async def reconcile_call_usage(
        self,
        run_id: str,
        claim_token: str,
        usage: UsageDelta,
    ) -> UsageDelta:
        async with self.pool.connection() as connection:
            async with connection.transaction():
                await self._lock_active_budget_run(connection, run_id, claim_token)
                cursor = await connection.execute(
                    """
                    SELECT count(*) FILTER (WHERE kind = 'MODEL') AS model_calls,
                           count(*) FILTER (WHERE kind = 'TOOL') AS tool_calls,
                           COALESCE(sum(
                               CASE WHEN status = 'SETTLED'
                                   THEN (actual_usage->>'input_tokens')::bigint ELSE 0 END
                           ), 0) AS input_tokens,
                           COALESCE(sum(
                               CASE WHEN status = 'SETTLED'
                                   THEN (actual_usage->>'output_tokens')::bigint ELSE 0 END
                           ), 0) AS output_tokens,
                           COALESCE(sum(
                               CASE WHEN status = 'SETTLED'
                                   THEN (actual_usage->>'cost_cny')::numeric ELSE 0 END
                           ), 0) AS cost_cny
                    FROM agent_workflow_budget_reservation
                    WHERE run_id = %s
                    """,
                    (run_id,),
                )
                row = await cursor.fetchone()
        return UsageDelta(
            model_calls=max(usage.model_calls, int(row["model_calls"] if row else 0)),
            tool_calls=max(usage.tool_calls, int(row["tool_calls"] if row else 0)),
            input_tokens=max(usage.input_tokens, int(row["input_tokens"] if row else 0)),
            output_tokens=max(usage.output_tokens, int(row["output_tokens"] if row else 0)),
            cost_cny=max(usage.cost_cny, float(row["cost_cny"] if row else 0.0)),
        )

    @staticmethod
    def _validate_budget_operation_key(operation_key: str, kind: str) -> None:
        expected_prefix = "model:" if kind == "MODEL" else "tool-"
        if not BUDGET_OPERATION_KEY_PATTERN.fullmatch(
            operation_key
        ) or not operation_key.startswith(expected_prefix):
            raise BudgetOperationInvalidError("invalid durable budget operation key")

    @staticmethod
    def _validate_model_safe_result(value: dict[str, Any]) -> None:
        try:
            encoded = json.dumps(
                value,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
        except (TypeError, ValueError) as failure:
            raise BudgetOperationInvalidError(
                "model safe result is not JSON serializable"
            ) from failure
        if len(encoded) > MAX_MODEL_SAFE_RESULT_BYTES:
            raise BudgetOperationInvalidError("model safe result exceeds durable size limit")

    @staticmethod
    async def _unknown_model_attempts(
        connection: Any,
        run_id: str,
        operation_key: str,
    ) -> int:
        cursor = await connection.execute(
            """
            SELECT count(*) AS count
            FROM agent_workflow_budget_reservation
            WHERE run_id = %s AND operation_key = %s
              AND kind = 'MODEL' AND status = 'UNKNOWN'
            """,
            (run_id, operation_key),
        )
        row = await cursor.fetchone()
        return int(row["count"] if row else 0)

    @staticmethod
    async def _lock_active_budget_run(
        connection: Any,
        run_id: str,
        claim_token: str,
    ) -> RunBudget:
        cursor = await connection.execute(
            """
            SELECT claim_token::text AS claim_token, lease_until >= now() AS lease_active,
                   cancel_requested, status, budget
            FROM agent_workflow_run
            WHERE run_id = %s
            FOR UPDATE
            """,
            (run_id,),
        )
        row = await cursor.fetchone()
        if (
            row is None
            or row["claim_token"] != claim_token
            or not row["lease_active"]
            or row["cancel_requested"]
            or row["status"]
            not in {
                "QUEUED",
                "PLANNING",
                "WORKING",
                "REVIEWING",
                "SYNTHESIZING",
                "FINALIZING",
            }
        ):
            raise BudgetClaimConflictError("budget reservation claim is stale")
        return RunBudget.model_validate(row.get("budget") or {})

    async def write_event(self, event: EventRecord, claim_token: str) -> bool:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                INSERT INTO agent_workflow_event(
                    run_id, event_key, role, task_id, type, safe_payload, claim_token, created_at
                )
                SELECT %s, %s, %s, %s, %s, %s, %s::uuid, now()
                FROM agent_workflow_run
                WHERE run_id = %s
                  AND claim_token = %s::uuid
                  AND lease_until >= now()
                  AND status IN (
                    'QUEUED', 'PLANNING', 'WORKING', 'REVIEWING',
                    'SYNTHESIZING', 'FINALIZING'
                  )
                ON CONFLICT (run_id, event_key) DO NOTHING
                """,
                (
                    event.run_id,
                    event.event_key,
                    event.role,
                    event.task_id,
                    event.event_type,
                    Jsonb(event.safe_payload),
                    claim_token,
                    event.run_id,
                    claim_token,
                ),
            )
        return cursor.rowcount == 1

    async def get_tool_receipt(self, run_id: str, call_id: str) -> dict[str, Any] | None:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                SELECT status, request_fingerprint, safe_result, error_code
                FROM agent_workflow_tool_receipt
                WHERE run_id = %s AND call_id = %s
                """,
                (run_id, call_id),
            )
            row = await cursor.fetchone()
        return dict(row) if row else None

    async def begin_tool_receipt(
        self,
        *,
        run_id: str,
        claim_token: str,
        call_id: str,
        task_id: str,
        tool_name: str,
        request_fingerprint: str,
    ) -> None:
        async with self.pool.connection() as connection:
            await connection.execute(
                """
                INSERT INTO agent_workflow_tool_receipt(
                    run_id, call_id, task_id, tool_name, request_fingerprint,
                    status, claim_token, created_at
                )
                SELECT %s, %s, %s, %s, %s, 'STARTED', %s::uuid, now()
                FROM agent_workflow_run
                WHERE run_id = %s
                  AND claim_token = %s::uuid
                  AND lease_until >= now()
                  AND status IN (
                    'QUEUED', 'PLANNING', 'WORKING', 'REVIEWING',
                    'SYNTHESIZING', 'FINALIZING'
                  )
                ON CONFLICT (run_id, call_id) DO UPDATE
                SET claim_token = EXCLUDED.claim_token
                WHERE agent_workflow_tool_receipt.status = 'STARTED'
                  AND agent_workflow_tool_receipt.request_fingerprint = EXCLUDED.request_fingerprint
                """,
                (
                    run_id,
                    call_id,
                    task_id,
                    tool_name,
                    request_fingerprint,
                    claim_token,
                    run_id,
                    claim_token,
                ),
            )
            cursor = await connection.execute(
                """
                SELECT receipt.request_fingerprint
                FROM agent_workflow_tool_receipt AS receipt
                JOIN agent_workflow_run AS run ON run.run_id = receipt.run_id
                WHERE receipt.run_id = %s AND receipt.call_id = %s
                  AND run.claim_token = %s::uuid
                  AND run.lease_until >= now()
                """,
                (run_id, call_id, claim_token),
            )
            row = await cursor.fetchone()
        if row is None or row["request_fingerprint"] != request_fingerprint:
            raise ReceiptConflictError("tool receipt call_id was reused with different arguments")

    async def complete_tool_receipt(
        self,
        *,
        run_id: str,
        claim_token: str,
        call_id: str,
        result: ToolExecutionResult,
    ) -> None:
        async with self.pool.connection() as connection:
            cursor = await connection.execute(
                """
                UPDATE agent_workflow_tool_receipt
                SET status = 'COMPLETED',
                    safe_result = %s,
                    error_code = %s,
                    completed_at = now(),
                    claim_token = %s::uuid
                WHERE run_id = %s AND call_id = %s
                  AND EXISTS (
                    SELECT 1 FROM agent_workflow_run AS run
                    WHERE run.run_id = agent_workflow_tool_receipt.run_id
                      AND run.claim_token = %s::uuid
                      AND run.lease_until >= now()
                      AND run.status IN (
                        'QUEUED', 'PLANNING', 'WORKING', 'REVIEWING',
                        'SYNTHESIZING', 'FINALIZING'
                      )
                  )
                """,
                (
                    Jsonb(result.model_dump(mode="json")),
                    result.error_code,
                    claim_token,
                    run_id,
                    call_id,
                    claim_token,
                ),
            )
        if cursor.rowcount != 1:
            raise ReceiptConflictError("tool receipt disappeared before completion")


def checkpoint_pool(database_url: str, schema: str) -> AsyncConnectionPool:
    """Create the pool required by AsyncPostgresSaver.

    autocommit and dict_row are mandatory for saver setup and row decoding.
    """

    return AsyncConnectionPool(
        conninfo=database_url,
        min_size=1,
        max_size=6,
        open=False,
        kwargs={
            "autocommit": True,
            "row_factory": dict_row,
            "options": f"-c search_path={schema}",
        },
    )
