from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from typing import Any

import pytest

from deepresearch_workflow.domain import (
    ClaimedRun,
    EventRecord,
    ModelBudgetReservation,
    RunBudget,
    ToolExecutionResult,
    UsageDelta,
    WorkflowStatus,
)
from deepresearch_workflow.ports import (
    BudgetClaimConflictError,
    BudgetLimitExceededError,
    BudgetOperationInProgressError,
)


class FakeRepository:
    def __init__(self) -> None:
        self.events: list[EventRecord] = []
        self.progress: list[tuple[str, str, UsageDelta]] = []
        self.active = True
        self.cancelled = False
        self.locked = False
        self.receipts: dict[tuple[str, str], dict[str, Any]] = {}
        self.budget_lock = asyncio.Lock()
        self.model_budget_rows: dict[str, list[dict[str, Any]]] = {}
        self.tool_budget_rows: dict[str, dict[str, Any]] = {}

    async def claim_next(self, instance_id: str, lease_seconds: int) -> None:
        return None

    @asynccontextmanager
    async def run_lock(self, run_id: str):
        self.locked = True
        try:
            yield
        finally:
            self.locked = False

    async def heartbeat(
        self, run_id: str, claim_token: str, instance_id: str, lease_seconds: int
    ) -> bool:
        return self.active and not self.cancelled

    async def assert_active_claim(self, run_id: str, claim_token: str) -> tuple[bool, bool]:
        return self.active, self.cancelled

    async def update_progress(
        self,
        run_id: str,
        claim_token: str,
        *,
        status: str,
        stage: str,
        usage: UsageDelta,
    ) -> bool:
        if not self.active or self.cancelled:
            return False
        self.progress.append((status, stage, usage))
        return True

    async def current_usage(self, run_id: str, claim_token: str) -> UsageDelta:
        return self.progress[-1][2] if self.progress else UsageDelta()

    async def reserve_model_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        budget: RunBudget,
    ) -> ModelBudgetReservation:
        async with self.budget_lock:
            self._assert_budget_claim()
            rows = self.model_budget_rows.setdefault(operation_key, [])
            latest = rows[-1] if rows else None
            if latest and latest["status"] == "SETTLED":
                return ModelBudgetReservation(
                    attempt=latest["attempt"],
                    unknown_attempts=sum(row["status"] == "UNKNOWN" for row in rows),
                    replay_value=latest["value"],
                    replay_usage=latest["usage"],
                )
            if latest and latest["status"] == "RESERVED":
                if latest["claim_token"] == claim_token:
                    raise BudgetOperationInProgressError("model operation is in progress")
                latest["status"] = "UNKNOWN"
                latest["claim_token"] = claim_token
            call_count = sum(len(existing) for existing in self.model_budget_rows.values())
            if call_count >= budget.max_model_calls:
                raise BudgetLimitExceededError("durable model-call budget exhausted")
            attempt = int(latest["attempt"]) + 1 if latest else 1
            rows.append(
                {
                    "attempt": attempt,
                    "status": "RESERVED",
                    "claim_token": claim_token,
                }
            )
            return ModelBudgetReservation(
                attempt=attempt,
                unknown_attempts=sum(row["status"] == "UNKNOWN" for row in rows),
            )

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
        async with self.budget_lock:
            self._assert_budget_claim()
            row = self.model_budget_rows[operation_key][attempt - 1]
            if row["status"] == "SETTLED":
                return
            if row["status"] != "RESERVED" or row["claim_token"] != claim_token:
                raise BudgetClaimConflictError("model reservation is stale")
            row.update(status="SETTLED", value=value, usage=usage)

    async def mark_model_call_unknown(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        async with self.budget_lock:
            self._assert_budget_claim()
            row = self.model_budget_rows[operation_key][attempt - 1]
            if row["status"] == "UNKNOWN":
                return
            if row["status"] != "RESERVED" or row["claim_token"] != claim_token:
                raise BudgetClaimConflictError("model reservation is stale")
            row["status"] = "UNKNOWN"

    async def reserve_tool_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        budget: RunBudget,
    ) -> int:
        async with self.budget_lock:
            self._assert_budget_claim()
            existing = self.tool_budget_rows.get(operation_key)
            if existing:
                if existing["status"] == "RESERVED":
                    existing["claim_token"] = claim_token
                return 1
            if len(self.tool_budget_rows) >= budget.max_tool_calls:
                raise BudgetLimitExceededError("durable tool-call budget exhausted")
            self.tool_budget_rows[operation_key] = {
                "status": "RESERVED",
                "claim_token": claim_token,
            }
            return 1

    async def settle_tool_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        async with self.budget_lock:
            self._assert_budget_claim()
            row = self.tool_budget_rows[operation_key]
            if row["status"] == "SETTLED":
                return
            if row["claim_token"] != claim_token:
                raise BudgetClaimConflictError("tool reservation is stale")
            row["status"] = "SETTLED"

    async def reconcile_call_usage(
        self,
        run_id: str,
        claim_token: str,
        usage: UsageDelta,
    ) -> UsageDelta:
        self._assert_budget_claim()
        settled_usage = UsageDelta()
        for rows in self.model_budget_rows.values():
            for row in rows:
                if row["status"] == "SETTLED" and row.get("usage") is not None:
                    settled_usage = settled_usage.plus(UsageDelta.model_validate(row["usage"]))
        return UsageDelta(
            model_calls=max(
                usage.model_calls,
                sum(len(rows) for rows in self.model_budget_rows.values()),
            ),
            tool_calls=max(usage.tool_calls, len(self.tool_budget_rows)),
            input_tokens=max(usage.input_tokens, settled_usage.input_tokens),
            output_tokens=max(usage.output_tokens, settled_usage.output_tokens),
            cost_cny=max(usage.cost_cny, settled_usage.cost_cny),
        )

    def _assert_budget_claim(self) -> None:
        if not self.active or self.cancelled:
            raise BudgetClaimConflictError("budget reservation claim is stale")

    async def write_event(self, event: EventRecord, claim_token: str = "claim") -> bool:
        if not self.active or self.cancelled:
            return False
        if any(existing.event_key == event.event_key for existing in self.events):
            return False
        self.events.append(event)
        return True

    async def get_tool_receipt(self, run_id: str, call_id: str) -> dict[str, Any] | None:
        return self.receipts.get((run_id, call_id))

    async def begin_tool_receipt(self, **kwargs: Any) -> None:
        return None

    async def complete_tool_receipt(
        self,
        *,
        run_id: str,
        claim_token: str,
        call_id: str,
        result: ToolExecutionResult,
    ) -> None:
        self.receipts[(run_id, call_id)] = {
            "status": "COMPLETED",
            "safe_result": result.model_dump(mode="json"),
        }


class FakeEventSink:
    def __init__(self, repository: FakeRepository) -> None:
        self.repository = repository

    async def emit(self, event: EventRecord, claim_token: str) -> None:
        await self.repository.write_event(event, claim_token)


@pytest.fixture
def repository() -> FakeRepository:
    return FakeRepository()


@pytest.fixture
def claimed_run() -> ClaimedRun:
    return ClaimedRun(
        run_id="run-001",
        session_id="session-001",
        user_id="tenant:user",
        question="What is the supported answer?",
        context_snapshot={},
        endpoint="/api/research/workflows",
        graph_thread_id="run-001",
        requested_scopes=["kb_search", "calculator"],
        grant_id="grant-001",
        budget=RunBudget(),
        claim_token="00000000-0000-0000-0000-000000000001",
        deadline_at=datetime.now(UTC) + timedelta(seconds=60),
        status=WorkflowStatus.QUEUED,
        stage="QUEUED",
    )


class FakeSnapshot(SimpleNamespace):
    values: dict[str, Any]
    next: tuple[str, ...]
