from __future__ import annotations

from contextlib import AbstractAsyncContextManager
from typing import Any, Protocol

from .domain import (
    ClaimedRun,
    EventRecord,
    FinalizeRequest,
    ModelBudgetReservation,
    ModelCall,
    PlanOutput,
    ReviewOutput,
    RunBudget,
    SynthesisOutput,
    ToolExecutionRequest,
    ToolExecutionResult,
    UsageDelta,
    WorkerPreparation,
    WorkItem,
)


class BudgetReservationError(RuntimeError):
    pass


class BudgetLimitExceededError(BudgetReservationError):
    pass


class BudgetClaimConflictError(BudgetReservationError):
    pass


class BudgetOperationInProgressError(BudgetReservationError):
    pass


class BudgetOperationInvalidError(BudgetReservationError):
    pass


class ToolReceiptStateError(RuntimeError):
    """A Java receipt control state that must not become a cached tool result."""

    def __init__(self, error_code: str) -> None:
        super().__init__(error_code)
        self.error_code = error_code


class WorkflowModel(Protocol):
    async def plan(
        self,
        *,
        question: str,
        context: dict[str, Any],
        allowed_tools: list[str],
        max_tasks: int,
    ) -> ModelCall[PlanOutput]: ...

    async def prepare_worker(
        self,
        *,
        question: str,
        task: WorkItem,
    ) -> ModelCall[WorkerPreparation]: ...

    async def review(
        self,
        *,
        question: str,
        tasks: list[WorkItem],
        evidence: list[dict[str, Any]],
        may_revise: bool,
        max_revision_tasks: int,
    ) -> ModelCall[ReviewOutput]: ...

    async def synthesize(
        self,
        *,
        question: str,
        evidence: list[dict[str, Any]],
        review_summary: str,
    ) -> ModelCall[SynthesisOutput]: ...


class ToolClient(Protocol):
    async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult: ...


class WorkflowRepository(Protocol):
    async def claim_next(self, instance_id: str, lease_seconds: int) -> ClaimedRun | None: ...

    def run_lock(self, run_id: str) -> AbstractAsyncContextManager[None]:
        """Hold a dedicated PostgreSQL session advisory lock for the whole graph run."""
        ...

    async def heartbeat(
        self, run_id: str, claim_token: str, instance_id: str, lease_seconds: int
    ) -> bool: ...

    async def assert_active_claim(self, run_id: str, claim_token: str) -> tuple[bool, bool]:
        """Return (claim_is_current, cancel_requested)."""
        ...

    async def current_usage(self, run_id: str, claim_token: str) -> UsageDelta: ...

    async def update_progress(
        self,
        run_id: str,
        claim_token: str,
        *,
        status: str,
        stage: str,
        usage: UsageDelta,
    ) -> bool: ...

    async def reserve_model_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        budget: RunBudget,
    ) -> ModelBudgetReservation: ...

    async def settle_model_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
        value: dict[str, Any],
        usage: UsageDelta,
    ) -> None: ...

    async def mark_model_call_unknown(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        """Close a failed/ambiguous provider attempt before a bounded retry."""
        ...

    async def reserve_tool_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        budget: RunBudget,
    ) -> int: ...

    async def settle_tool_call(
        self,
        *,
        run_id: str,
        claim_token: str,
        operation_key: str,
        attempt: int,
    ) -> None: ...

    async def reconcile_call_usage(
        self,
        run_id: str,
        claim_token: str,
        usage: UsageDelta,
    ) -> UsageDelta:
        """Raise displayed call counts to the conservative durable reservation totals."""
        ...

    async def write_event(self, event: EventRecord, claim_token: str) -> bool: ...

    async def get_tool_receipt(self, run_id: str, call_id: str) -> dict[str, Any] | None: ...

    async def begin_tool_receipt(
        self,
        *,
        run_id: str,
        claim_token: str,
        call_id: str,
        task_id: str,
        tool_name: str,
        request_fingerprint: str,
    ) -> None: ...

    async def complete_tool_receipt(
        self,
        *,
        run_id: str,
        claim_token: str,
        call_id: str,
        result: ToolExecutionResult,
    ) -> None: ...


class ControlPlaneClient(Protocol):
    async def finalize(self, run_id: str, request: FinalizeRequest) -> None: ...


class EventSink(Protocol):
    async def emit(self, event: EventRecord, claim_token: str) -> None: ...


class RepositoryEventSink:
    def __init__(self, repository: WorkflowRepository) -> None:
        self._repository = repository

    async def emit(self, event: EventRecord, claim_token: str) -> None:
        await self._repository.write_event(event, claim_token)
