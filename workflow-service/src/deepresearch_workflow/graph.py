from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from langgraph.graph import END, START, StateGraph
from langgraph.types import Send
from pydantic import BaseModel

from .domain import (
    EventRecord,
    EvidenceRecord,
    ModelCall,
    PlanOutput,
    ReviewOutput,
    RunBudget,
    SynthesisOutput,
    ToolExecutionRequest,
    UsageDelta,
    WorkerInput,
    WorkerPreparation,
    WorkflowStage,
    WorkflowState,
    WorkflowStatus,
    WorkItem,
    deterministic_call_id,
)
from .mcp import arguments_for
from .model import SafeModelFailure, classify_model_failure
from .ports import (
    BudgetClaimConflictError,
    BudgetLimitExceededError,
    BudgetOperationInProgressError,
    BudgetOperationInvalidError,
    EventSink,
    ToolClient,
    ToolReceiptStateError,
    WorkflowModel,
    WorkflowRepository,
)
from .query_fidelity import preserve_search_query_identifiers
from .settings import Settings

logger = logging.getLogger("uvicorn.error.deepresearch_workflow.graph")
_MODEL_FAILURE_CODES = {
    "TIMEOUT": "MODEL_TIMEOUT",
    "RATE_LIMIT": "MODEL_RATE_LIMITED",
    "SCHEMA": "MODEL_SCHEMA_INVALID",
    "PROVIDER": "MODEL_PROVIDER_FAILED",
    "INTERNAL": "AGENT_MODEL_INTERNAL_ERROR",
}


class WorkflowExecutionError(RuntimeError):
    error_code = "WORKFLOW_FAILED"

    def __init__(self, message: str, *, error_code: str | None = None):
        super().__init__(message)
        if error_code is not None:
            self.error_code = error_code


class ModelCallError(WorkflowExecutionError):
    error_code = "MODEL_CALL_FAILED"

    def __init__(
        self,
        operation_key: str,
        *,
        failure_kind: str | None = None,
        attempt: int | None = None,
        error_class: str | None = None,
        retryable: bool | None = None,
        validation_stage: str | None = None,
        domain_error_code: str | None = None,
        json_diagnostic: dict | None = None,
    ) -> None:
        from .agent_diagnostics import safe_domain_code, safe_validation_stage

        self.operation_key = operation_key
        self.failure_kind = failure_kind
        self.attempt = attempt
        self.error_class = error_class
        self.retryable = retryable
        self.validation_stage = safe_validation_stage(validation_stage)
        self.domain_error_code = safe_domain_code(domain_error_code)
        from .agent_json import safe_json_diagnostic

        self.json_diagnostic = safe_json_diagnostic(json_diagnostic)
        if failure_kind is not None:
            self.error_code = _MODEL_FAILURE_CODES.get(failure_kind, "MODEL_CALL_FAILED")
        if error_class == "identity_validation":
            self.error_code = "MODEL_IDENTITY_INVALID"
        message = f"model call failed during {operation_key}"
        if self.validation_stage is not None:
            message += f"; validation_stage={self.validation_stage}"
        if self.domain_error_code is not None:
            message += f"; domain_error_code={self.domain_error_code}"
        if self.json_diagnostic is not None:
            message += f"; json_category={self.json_diagnostic['category']}"
            if "decoder_code" in self.json_diagnostic:
                message += f"; json_decoder={self.json_diagnostic['decoder_code']}"
        super().__init__(message)


class StaleClaimError(WorkflowExecutionError):
    error_code = "STALE_CLAIM"


class RunCancelledError(WorkflowExecutionError):
    error_code = "CANCELLED"


class RunTimedOutError(WorkflowExecutionError):
    error_code = "TIMED_OUT"


class RunBudgetExceededError(WorkflowExecutionError):
    error_code = "BUDGET_EXCEEDED"


class ScopeViolationError(WorkflowExecutionError):
    error_code = "SCOPE_DENIED"


_CITATION_MARKER = re.compile(r"\[来源(\d+)]")


@dataclass(frozen=True)
class CitationContractResolution:
    valid: bool
    answer: str
    citations: tuple[str, ...]
    code: str
    marker_count: int
    max_marker: int | None
    normalized: bool = False


def validate_citation_contract(
    answer: str,
    citations: list[str],
    valid_sources: set[str],
) -> tuple[bool, str]:
    """Validate the durable answer-to-source mapping without guessing from display text."""

    if not citations:
        return False, "MISSING_CITATIONS"
    if len(citations) != len(set(citations)):
        return False, "DUPLICATE_CITATIONS"
    if any(source_id not in valid_sources for source_id in citations):
        return False, "UNKNOWN_SOURCE"

    marker_indexes = [int(match) for match in _CITATION_MARKER.findall(answer)]
    if not marker_indexes:
        return False, "MISSING_MARKERS"
    if any(index < 1 or index > len(citations) for index in marker_indexes):
        return False, "MARKER_OUT_OF_RANGE"

    first_appearance = list(dict.fromkeys(marker_indexes))
    expected_order = list(range(1, len(citations) + 1))
    if first_appearance != expected_order:
        return False, "MARKER_ORDER_MISMATCH"
    return True, "VALID"


def resolve_citation_contract(
    answer: str,
    citations: list[str],
    evidence_source_ids: list[str],
) -> CitationContractResolution:
    """Resolve compact or evidence-indexed markers without guessing source mappings.

    The public INDEXED_V1 contract uses compact markers into ``citations``. Some
    providers instead emit markers into the full evidence array while returning
    only the cited source IDs. That form is accepted only when the marker-derived
    source sequence exactly equals the model-declared citations; the server then
    compacts markers deterministically into INDEXED_V1.
    """

    marker_indexes = [int(match) for match in _CITATION_MARKER.findall(answer)]
    marker_count = len(marker_indexes)
    max_marker = max(marker_indexes, default=None)
    valid, code = validate_citation_contract(answer, citations, set(evidence_source_ids))
    if valid:
        return CitationContractResolution(
            valid=True,
            answer=answer,
            citations=tuple(citations),
            code=code,
            marker_count=marker_count,
            max_marker=max_marker,
        )

    # A provider can repeat the same exact source ID in ``citations`` even when
    # instructed not to. This is not an ambiguous mapping: every duplicate
    # index still names the same source. Compact it by first appearance and
    # rewrite only markers that are valid indexes into the declared list.
    unique_citations = list(dict.fromkeys(citations))
    duplicate_citations = len(unique_citations) != len(citations)
    if duplicate_citations:
        if any(source_id not in set(evidence_source_ids) for source_id in unique_citations):
            return CitationContractResolution(
                valid=False,
                answer="",
                citations=(),
                code="UNKNOWN_SOURCE",
                marker_count=marker_count,
                max_marker=max_marker,
            )
        if not marker_indexes:
            return CitationContractResolution(
                valid=False,
                answer="",
                citations=(),
                code="MISSING_MARKERS",
                marker_count=marker_count,
                max_marker=max_marker,
            )
        if any(index < 1 or index > len(citations) for index in marker_indexes):
            return CitationContractResolution(
                valid=False,
                answer="",
                citations=(),
                code="MARKER_OUT_OF_RANGE",
                marker_count=marker_count,
                max_marker=max_marker,
            )
        if list(dict.fromkeys(marker_indexes)) != list(range(1, len(citations) + 1)):
            return CitationContractResolution(
                valid=False,
                answer="",
                citations=(),
                code="MARKER_ORDER_MISMATCH",
                marker_count=marker_count,
                max_marker=max_marker,
            )
        source_public_indexes = {
            source_id: public_index
            for public_index, source_id in enumerate(unique_citations, start=1)
        }
        declared_index_to_public = {
            declared_index: source_public_indexes[source_id]
            for declared_index, source_id in enumerate(citations, start=1)
        }
        compact_answer = _CITATION_MARKER.sub(
            lambda match: f"[来源{declared_index_to_public[int(match.group(1))]}]",
            answer,
        )
        compact_valid, compact_code = validate_citation_contract(
            compact_answer,
            unique_citations,
            set(evidence_source_ids),
        )
        if compact_valid:
            return CitationContractResolution(
                valid=True,
                answer=compact_answer,
                citations=tuple(unique_citations),
                code="VALID_DUPLICATE_CITATIONS_NORMALIZED",
                marker_count=marker_count,
                max_marker=max_marker,
                normalized=True,
            )
        return CitationContractResolution(
            valid=False,
            answer="",
            citations=(),
            code=compact_code,
            marker_count=marker_count,
            max_marker=max_marker,
        )

    # Missing citations/markers and unknown source IDs are never repairable by
    # interpreting a different index space.
    if code not in {"MARKER_OUT_OF_RANGE", "MARKER_ORDER_MISMATCH"}:
        return CitationContractResolution(
            valid=False,
            answer="",
            citations=(),
            code=code,
            marker_count=marker_count,
            max_marker=max_marker,
        )
    if any(index < 1 or index > len(evidence_source_ids) for index in marker_indexes):
        return CitationContractResolution(
            valid=False,
            answer="",
            citations=(),
            code="MARKER_OUT_OF_RANGE",
            marker_count=marker_count,
            max_marker=max_marker,
        )

    marker_source_ids = [evidence_source_ids[index - 1] for index in marker_indexes]
    derived_citations = list(dict.fromkeys(marker_source_ids))
    if derived_citations != citations:
        return CitationContractResolution(
            valid=False,
            answer="",
            citations=(),
            code="EVIDENCE_CITATION_MISMATCH",
            marker_count=marker_count,
            max_marker=max_marker,
        )

    public_indexes = {
        source_id: public_index for public_index, source_id in enumerate(derived_citations, start=1)
    }
    normalized_answer = _CITATION_MARKER.sub(
        lambda match: f"[来源{public_indexes[evidence_source_ids[int(match.group(1)) - 1]]}]",
        answer,
    )
    return CitationContractResolution(
        valid=True,
        answer=normalized_answer,
        citations=tuple(derived_citations),
        code="VALID_EVIDENCE_INDEXED_NORMALIZED",
        marker_count=marker_count,
        max_marker=max_marker,
        normalized=True,
    )


@dataclass(frozen=True)
class GraphRuntime:
    claim_token: str


class DurableResearchGraph:
    """Builds one claim-bound graph while keeping secrets out of checkpoint state."""

    def __init__(
        self,
        *,
        model: WorkflowModel,
        tools: ToolClient,
        repository: WorkflowRepository,
        events: EventSink,
        settings: Settings,
        budget: RunBudget,
        runtime: GraphRuntime,
    ) -> None:
        self._model = model
        self._tools = tools
        self._repository = repository
        self._events = events
        self._settings = settings
        self._budget = budget
        self._runtime = runtime
        # LangGraph fan-out branches receive the same state snapshot. Keep a run-scoped,
        # concurrency-safe ledger outside checkpoint state so two workers cannot both spend
        # the same remaining call budget before their deltas reach the reducer.
        self._budget_lock = asyncio.Lock()
        self._budget_run_id: str | None = None
        self._budget_usage = UsageDelta()

    def compile(self, *, checkpointer: Any | None = None) -> Any:
        graph = StateGraph(WorkflowState)
        graph.add_node("planner", self._planner)
        graph.add_node("worker", self._worker)
        graph.add_node("reviewer", self._reviewer)
        graph.add_node("synthesizer", self._synthesizer)
        graph.add_node("insufficient", self._insufficient)
        graph.add_edge(START, "planner")
        graph.add_conditional_edges("planner", self._dispatch_workers, ["worker"])
        graph.add_edge("worker", "reviewer")
        graph.add_conditional_edges(
            "reviewer",
            self._after_review,
            ["worker", "synthesizer", "insufficient"],
        )
        graph.add_edge("synthesizer", END)
        graph.add_edge("insufficient", END)
        return graph.compile(checkpointer=checkpointer)

    async def _planner(self, state: WorkflowState) -> dict[str, Any]:
        await self._progress(state, WorkflowStatus.PLANNING, WorkflowStage.PLANNING, "PLANNER")
        call = await self._budgeted_model_call(
            state["run_id"],
            self._usage(state),
            "model:planner",
            PlanOutput,
            lambda: self._model.plan(
                question=state["question"],
                context=state.get("context_snapshot", {}),
                allowed_tools=state.get("requested_scopes", []),
                max_tasks=self._budget.max_tasks,
            ),
        )

        allowed = set(state.get("requested_scopes", []))
        tasks: list[WorkItem] = []
        for index, draft in enumerate(call.value.tasks[: self._budget.max_tasks], start=1):
            if draft.tool.value not in allowed:
                raise ScopeViolationError("planner requested a tool outside the run scope")
            tasks.append(
                WorkItem(
                    task_id=f"task-{index:02d}",
                    objective=draft.objective,
                    query=draft.query,
                    tool=draft.tool,
                )
            )
        if not tasks:
            raise WorkflowExecutionError("planner produced no executable task")
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key="planner:completed",
                role="PLANNER",
                event_type="PLAN_COMPLETED",
                safe_payload={
                    "summary": call.value.summary,
                    "taskCount": len(tasks),
                    "tools": [task.tool.value for task in tasks],
                },
            )
        )
        await self._persist_progress(
            state,
            WorkflowStatus.WORKING,
            WorkflowStage.WORKING,
            "SYSTEM",
            usage=self._usage(state).plus(call.usage),
        )
        return {
            "status": WorkflowStatus.WORKING.value,
            "stage": WorkflowStage.WORKING.value,
            "plan_summary": call.value.summary,
            "tasks": [task.model_dump(mode="json") for task in tasks],
            "worker_task_count": len(tasks),
            "usage": call.usage.model_dump(mode="json"),
            "revision_round": 0,
            "should_revise": False,
            "revision_blocked_reason": None,
        }

    def _dispatch_workers(self, state: WorkflowState) -> list[Send]:
        return [
            Send(
                "worker",
                WorkerInput(
                    run_id=state["run_id"],
                    grant_id=state["grant_id"],
                    user_id=state["user_id"],
                    question=state["question"],
                    requested_scopes=state.get("requested_scopes", []),
                    deadline_at=state["deadline_at"],
                    base_usage=state.get("usage", {}),
                    task=task,
                ),
            )
            for task in state.get("tasks", [])
        ]

    async def _worker(self, state: WorkerInput) -> dict[str, Any]:
        await self._guard(state["run_id"], state["deadline_at"])
        task = WorkItem.model_validate(state["task"])
        if task.tool.value not in state.get("requested_scopes", []):
            raise ScopeViolationError("worker tool is outside the run scope")

        base_usage = UsageDelta.model_validate(state.get("base_usage", {}))
        preparation = await self._budgeted_model_call(
            state["run_id"],
            base_usage,
            f"model:worker:{task.task_id}:prepare",
            WorkerPreparation,
            lambda: self._model.prepare_worker(
                question=state["question"],
                task=task,
            ),
        )
        await self._guard(state["run_id"], state["deadline_at"])
        focused_query = preserve_search_query_identifiers(
            preparation.value.focused_query,
            question=state["question"],
            task=task,
        )

        call_id = deterministic_call_id(state["run_id"], task)
        # One logical tool attempt is charged even if transport or the remote tool fails.
        # Reserving before the started event and execute closes the parallel fan-out race:
        # an over-budget worker neither claims to have started nor reaches Java/MCP.
        tool_attempt = await self._reserve_tool_call(state["run_id"], base_usage, call_id)
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key=f"worker:{task.task_id}:started",
                role="WORKER",
                task_id=task.task_id,
                event_type="TASK_STARTED",
                safe_payload={
                    "tool": task.tool.value,
                    "summary": preparation.value.safe_summary,
                },
            )
        )
        try:
            result = await self._tools.execute(
                ToolExecutionRequest(
                    run_id=state["run_id"],
                    grant_id=state["grant_id"],
                    claim_token=self._runtime.claim_token,
                    task=task,
                    requested_scopes=state.get("requested_scopes", []),
                    arguments=arguments_for(task.tool, focused_query),
                    call_id=call_id,
                    timeout_seconds=min(self._settings.http_timeout_seconds, 60.0),
                )
            )
            records = [
                EvidenceRecord(
                    task_id=task.task_id,
                    tool=task.tool,
                    call_id=call_id,
                    source_id=item.source_id,
                    content=item.content,
                    source_uri=item.source_uri,
                    confidence=item.confidence,
                    error_code=result.error_code,
                )
                for item in result.evidence
            ]
            if not records:
                records = [
                    EvidenceRecord(
                        task_id=task.task_id,
                        tool=task.tool,
                        call_id=call_id,
                        source_id=f"{task.task_id}-empty",
                        content="",
                        confidence=0,
                        error_code=result.error_code or "NO_EVIDENCE",
                    )
                ]
            error_code = result.error_code
        except WorkflowExecutionError:
            raise
        except ToolReceiptStateError as failure:
            records = [
                EvidenceRecord(
                    task_id=task.task_id,
                    tool=task.tool,
                    call_id=call_id,
                    source_id=f"{task.task_id}-receipt-state",
                    content="",
                    confidence=0,
                    error_code=failure.error_code,
                )
            ]
            error_code = failure.error_code
        except Exception:
            records = [
                EvidenceRecord(
                    task_id=task.task_id,
                    tool=task.tool,
                    call_id=call_id,
                    source_id=f"{task.task_id}-error",
                    content="",
                    confidence=0,
                    error_code="TOOL_EXECUTION_FAILED",
                )
            ]
            error_code = "TOOL_EXECUTION_FAILED"
        await self._settle_tool_call(state["run_id"], call_id, tool_attempt)
        await self._guard(state["run_id"], state["deadline_at"])
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key=f"worker:{task.task_id}:completed",
                role="WORKER",
                task_id=task.task_id,
                event_type="TASK_COMPLETED",
                safe_payload={
                    "tool": task.tool.value,
                    "evidenceCount": sum(record.usable for record in records),
                    "errorCode": error_code,
                },
            )
        )
        return {
            "evidence": [record.model_dump(mode="json") for record in records],
            "usage": preparation.usage.plus(UsageDelta(tool_calls=1)).model_dump(mode="json"),
        }

    async def _reviewer(self, state: WorkflowState) -> dict[str, Any]:
        await self._progress(state, WorkflowStatus.REVIEWING, WorkflowStage.REVIEWING, "REVIEWER")
        tasks = [WorkItem.model_validate(task) for task in state.get("tasks", [])]
        scheduled_task_count = self._scheduled_worker_task_count(state)
        remaining_task_capacity = max(0, self._budget.max_tasks - scheduled_task_count)
        revision_round_available = (
            state.get("revision_round", 0) < self._budget.max_revision_rounds
        )
        may_revise = revision_round_available and remaining_task_capacity > 0
        call = await self._budgeted_model_call(
            state["run_id"],
            self._usage(state),
            f"model:reviewer:round-{state.get('revision_round', 0)}",
            ReviewOutput,
            lambda: self._model.review(
                question=state["question"],
                tasks=tasks,
                evidence=state.get("evidence", []),
                may_revise=may_revise,
                max_revision_tasks=(remaining_task_capacity if may_revise else 0),
            ),
        )

        revision_tasks: list[WorkItem] = []
        if not call.value.sufficient and may_revise:
            allowed = set(state.get("requested_scopes", []))
            for index, draft in enumerate(
                call.value.revision_tasks[:remaining_task_capacity], start=1
            ):
                if draft.tool.value not in allowed:
                    continue
                revision_tasks.append(
                    WorkItem(
                        task_id=f"revision-{state.get('revision_round', 0) + 1}-{index:02d}",
                        objective=draft.objective,
                        query=draft.query,
                        tool=draft.tool,
                        revision_of=draft.revision_of,
                    )
                )
        should_revise = bool(revision_tasks)
        scheduled_task_count += len(revision_tasks)
        remaining_after_review = max(0, self._budget.max_tasks - scheduled_task_count)
        revision_blocked_reason: str | None = None
        if not call.value.sufficient and not should_revise:
            if remaining_task_capacity == 0:
                revision_blocked_reason = "TASK_BUDGET_EXHAUSTED"
            elif not revision_round_available:
                revision_blocked_reason = "REVISION_ROUND_LIMIT_REACHED"
            elif call.value.revision_tasks:
                revision_blocked_reason = "NO_AUTHORIZED_REVISION_TASKS"
            else:
                revision_blocked_reason = "NO_REVISION_TASKS_PROPOSED"
        round_number = state.get("revision_round", 0) + (1 if should_revise else 0)
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key=f"reviewer:round-{state.get('revision_round', 0)}:completed",
                role="REVIEWER",
                event_type="REVIEW_COMPLETED",
                safe_payload={
                    "sufficient": call.value.sufficient,
                    "revisionTaskCount": len(revision_tasks),
                    "scheduledTaskCount": scheduled_task_count,
                    "remainingTaskCapacity": remaining_after_review,
                    "revisionBlockedReason": revision_blocked_reason,
                    "summary": call.value.summary,
                },
            )
        )
        update: dict[str, Any] = {
            "review": call.value.model_dump(mode="json"),
            "usage": call.usage.model_dump(mode="json"),
            "should_revise": should_revise,
            "revision_round": round_number,
            "worker_task_count": scheduled_task_count,
            "revision_blocked_reason": revision_blocked_reason,
        }
        if should_revise:
            await self._emit(
                EventRecord(
                    run_id=state["run_id"],
                    event_key=f"revision:round-{round_number}:started",
                    role="REVIEWER",
                    event_type="REVISION_STARTED",
                    safe_payload={"round": round_number, "taskCount": len(revision_tasks)},
                )
            )
            await self._persist_progress(
                state,
                WorkflowStatus.WORKING,
                WorkflowStage.WORKING,
                "SYSTEM",
                usage=self._usage(state).plus(call.usage),
                event_round=round_number,
            )
            update.update(
                status=WorkflowStatus.WORKING.value,
                stage=WorkflowStage.WORKING.value,
                tasks=[task.model_dump(mode="json") for task in revision_tasks],
            )
        return update

    @staticmethod
    def _scheduled_worker_task_count(state: WorkflowState) -> int:
        """Read the durable run-level task counter, with old-checkpoint recovery.

        Checkpoints created before ``worker_task_count`` existed can still resume. Every
        completed Worker emits at least one evidence record, so the union of current task
        IDs and evidence task IDs is a conservative count of already scheduled work.
        """

        persisted = state.get("worker_task_count")
        if isinstance(persisted, int) and not isinstance(persisted, bool) and persisted >= 0:
            return persisted

        task_ids = {
            WorkItem.model_validate(task).task_id for task in state.get("tasks", [])
        }
        task_ids.update(
            EvidenceRecord.model_validate(item).task_id for item in state.get("evidence", [])
        )
        return len(task_ids)

    def _after_review(self, state: WorkflowState) -> list[Send] | str:
        review = state.get("review", {})
        if state.get("should_revise"):
            return self._dispatch_workers(state)
        if bool(review.get("sufficient")):
            return "synthesizer"
        return "insufficient"

    async def _synthesizer(self, state: WorkflowState) -> dict[str, Any]:
        await self._progress(
            state, WorkflowStatus.SYNTHESIZING, WorkflowStage.SYNTHESIZING, "SYNTHESIZER"
        )
        evidence = [
            EvidenceRecord.model_validate(item)
            for item in state.get("evidence", [])
            if EvidenceRecord.model_validate(item).usable
        ]
        call = await self._budgeted_model_call(
            state["run_id"],
            self._usage(state),
            "model:synthesizer",
            SynthesisOutput,
            lambda: self._model.synthesize(
                question=state["question"],
                evidence=[item.model_dump(mode="json") for item in evidence],
                review_summary=str(state.get("review", {}).get("summary", "")),
            ),
        )
        requested_citations = list(call.value.citations)
        citation_contract = resolve_citation_contract(
            call.value.answer,
            requested_citations,
            [item.source_id for item in evidence],
        )
        if call.value.citation_contract_error is not None:
            # Provider markers were already checked against the exact evidence array by
            # the model adapter. Preserve the precise fail-closed reason while keeping
            # the legacy compact-contract validator as defense in depth.
            citation_contract = CitationContractResolution(
                valid=False,
                answer="",
                citations=(),
                code=call.value.citation_contract_error,
                marker_count=citation_contract.marker_count,
                max_marker=citation_contract.max_marker,
            )
        grounded = call.value.grounded and bool(evidence) and citation_contract.valid
        citation_failed = call.value.grounded and bool(evidence) and not citation_contract.valid
        citations = list(citation_contract.citations) if grounded else []
        if grounded:
            status = WorkflowStatus.SUCCEEDED
            answer = citation_contract.answer
            error_code = None
            error_message = None
        elif citation_failed:
            status = WorkflowStatus.FAILED
            answer = ""
            error_code = "CITATION_VALIDATION_FAILED"
            error_message = f"citation validation failed: {citation_contract.code}"
        else:
            status = WorkflowStatus.INSUFFICIENT_EVIDENCE
            answer = self._insufficient_answer()
            error_code = "INSUFFICIENT_EVIDENCE"
            error_message = None
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key="synthesizer:completed",
                role="SYNTHESIZER",
                event_type="SYNTHESIS_COMPLETED",
                safe_payload={
                    "grounded": grounded,
                    "citationCount": len(citations),
                    "candidateCitationCount": len(requested_citations),
                    "uniqueCandidateCitationCount": len(set(requested_citations)),
                    "duplicateCandidateCitationCount": (
                        len(requested_citations) - len(set(requested_citations))
                    ),
                    "citationContract": citation_contract.code,
                    "citationContractNormalized": citation_contract.normalized,
                    "citationNormalizationMode": (
                        "CITATION_POSITIONAL_DEDUP"
                        if citation_contract.code == "VALID_DUPLICATE_CITATIONS_NORMALIZED"
                        else "EVIDENCE_POSITIONAL_COMPACTION"
                        if citation_contract.code == "VALID_EVIDENCE_INDEXED_NORMALIZED"
                        else "NONE"
                    ),
                    "evidenceCount": len(evidence),
                    "markerCount": citation_contract.marker_count,
                    "maxMarker": citation_contract.max_marker,
                },
            )
        )
        return {
            "usage": call.usage.model_dump(mode="json"),
            "final_answer": answer,
            "citations": citations,
            "final_status": status.value,
            "error_code": error_code,
            "error_message": error_message,
        }

    async def _insufficient(self, state: WorkflowState) -> dict[str, Any]:
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key="workflow:insufficient-evidence",
                role="SYSTEM",
                event_type="INSUFFICIENT_EVIDENCE",
                safe_payload={
                    "revisionRounds": state.get("revision_round", 0),
                    "scheduledTaskCount": self._scheduled_worker_task_count(state),
                    "revisionBlockedReason": state.get("revision_blocked_reason"),
                },
            )
        )
        return {
            "final_answer": self._insufficient_answer(),
            "citations": [],
            "final_status": WorkflowStatus.INSUFFICIENT_EVIDENCE.value,
            "error_code": "INSUFFICIENT_EVIDENCE",
        }

    async def _progress(
        self,
        state: WorkflowState,
        status: WorkflowStatus,
        stage: WorkflowStage,
        role: str,
    ) -> None:
        await self._persist_progress(state, status, stage, role)

    async def _persist_progress(
        self,
        state: WorkflowState,
        status: WorkflowStatus,
        stage: WorkflowStage,
        role: str,
        *,
        usage: UsageDelta | None = None,
        event_round: int | None = None,
    ) -> None:
        await self._guard(state["run_id"], state["deadline_at"])
        current_usage = usage or self._usage(state)
        changed = await self._repository.update_progress(
            state["run_id"],
            self._runtime.claim_token,
            status=status.value,
            stage=stage.value,
            usage=current_usage,
        )
        if not changed:
            raise StaleClaimError("claim changed while updating progress")
        round_number = state.get("revision_round", 0) if event_round is None else event_round
        await self._emit(
            EventRecord(
                run_id=state["run_id"],
                event_key=f"stage:{stage.value.lower()}:round-{round_number}",
                role=role,
                event_type="STAGE_CHANGED",
                safe_payload={"status": status.value, "stage": stage.value},
            )
        )

    async def _emit(self, event: EventRecord) -> None:
        await self._events.emit(event, self._runtime.claim_token)

    async def _guard(self, run_id: str, deadline_at: str) -> None:
        deadline = datetime.fromisoformat(deadline_at.replace("Z", "+00:00"))
        if deadline.tzinfo is None:
            deadline = deadline.replace(tzinfo=UTC)
        if datetime.now(UTC) >= deadline:
            raise RunTimedOutError("workflow deadline elapsed")
        active, cancelled = await self._repository.assert_active_claim(
            run_id, self._runtime.claim_token
        )
        if not active:
            raise StaleClaimError("workflow claim is stale")
        if cancelled:
            raise RunCancelledError("workflow cancellation requested")

    async def _budgeted_model_call(
        self,
        run_id: str,
        observed_usage: UsageDelta,
        operation_key: str,
        result_type: type[BaseModel],
        invoke: Callable[[], Awaitable[ModelCall[Any]]],
    ) -> ModelCall[Any]:
        """Serialize the check/call/charge sequence shared by fan-out branches.

        Token and cost usage are only authoritative after the provider responds. Holding
        the gate for the complete call ensures there is at most one unaccounted in-flight
        model call and that a sibling cannot start from the same stale usage snapshot.
        """

        async with self._budget_lock:
            self._synchronize_budget_ledger(run_id, observed_usage)
            while True:
                self._check_usage_budget(self._budget_usage.plus(UsageDelta(model_calls=1)))
                try:
                    reservation = await self._repository.reserve_model_call(
                        run_id=run_id,
                        claim_token=self._runtime.claim_token,
                        operation_key=operation_key,
                        budget=self._budget,
                    )
                except BudgetLimitExceededError as failure:
                    raise RunBudgetExceededError(str(failure)) from failure
                except BudgetClaimConflictError as failure:
                    raise StaleClaimError(str(failure)) from failure
                except (
                    BudgetOperationInProgressError,
                    BudgetOperationInvalidError,
                ) as failure:
                    raise WorkflowExecutionError(str(failure)) from failure

                if reservation.replay:
                    provider_call = ModelCall(
                        value=result_type.model_validate(reservation.replay_value),
                        usage=reservation.replay_usage or UsageDelta(),
                    )
                else:
                    try:
                        provider_call = await invoke()
                    except WorkflowExecutionError:
                        raise
                    except Exception as failure:
                        safe_failure = classify_model_failure(failure)
                        await self._mark_model_attempt_unknown(
                            run_id,
                            operation_key,
                            reservation.attempt,
                        )
                        self._log_model_attempt_failure(
                            run_id,
                            operation_key,
                            reservation.attempt,
                            safe_failure,
                        )
                        will_retry = (
                            safe_failure.retryable
                            and reservation.attempt < self._settings.model_max_attempts
                        )
                        await self._emit_model_attempt_event(
                            run_id,
                            operation_key,
                            reservation.attempt,
                            safe_failure,
                            will_retry=will_retry,
                        )
                        if will_retry:
                            delay = self._settings.model_retry_backoff_seconds * (
                                2 ** (reservation.attempt - 1)
                            )
                            if delay:
                                await asyncio.sleep(delay)
                            continue
                        raise ModelCallError(
                            operation_key,
                            failure_kind=safe_failure.failure_kind,
                            attempt=reservation.attempt,
                            error_class=safe_failure.error_class,
                            retryable=safe_failure.retryable,
                        ) from safe_failure

                if provider_call.usage.model_calls != 1:
                    if not reservation.replay:
                        await self._mark_model_attempt_unknown(
                            run_id,
                            operation_key,
                            reservation.attempt,
                        )
                    raise WorkflowExecutionError(
                        "model adapter must account for exactly one logical model call"
                    )
                call = provider_call.model_copy(
                    update={
                        "usage": provider_call.usage.plus(
                            UsageDelta(model_calls=reservation.unknown_attempts)
                        )
                    }
                )
                charged = self._budget_usage.plus(call.usage)
                self._budget_usage = charged
                if not reservation.replay:
                    try:
                        await self._repository.settle_model_call(
                            run_id=run_id,
                            claim_token=self._runtime.claim_token,
                            operation_key=operation_key,
                            attempt=reservation.attempt,
                            value=provider_call.value.model_dump(mode="json"),
                            usage=provider_call.usage,
                        )
                    except BudgetClaimConflictError as failure:
                        raise StaleClaimError(str(failure)) from failure
                    except (
                        BudgetOperationInProgressError,
                        BudgetOperationInvalidError,
                    ) as failure:
                        raise WorkflowExecutionError(str(failure)) from failure
                # Token/cost are provider-reported rather than worst-case reserved. Record
                # the call durably first, then reject an over-limit authoritative response.
                self._check_usage_budget(charged)
                return call

    async def _mark_model_attempt_unknown(
        self,
        run_id: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        try:
            await self._repository.mark_model_call_unknown(
                run_id=run_id,
                claim_token=self._runtime.claim_token,
                operation_key=operation_key,
                attempt=attempt,
            )
        except BudgetClaimConflictError as failure:
            raise StaleClaimError(str(failure)) from failure
        except (
            BudgetOperationInProgressError,
            BudgetOperationInvalidError,
        ) as failure:
            raise WorkflowExecutionError(str(failure)) from failure

    @staticmethod
    def _log_model_attempt_failure(
        run_id: str,
        operation_key: str,
        attempt: int,
        failure: SafeModelFailure,
    ) -> None:
        # Every value is generated by server code or the allowlisted model adapter.
        # Never interpolate exception text, prompts, credentials, or raw responses.
        logger.warning(
            "model attempt failed run_id=%s operation=%s attempt=%s "
            "error_class=%s failure_kind=%s retryable=%s",
            run_id,
            operation_key,
            attempt,
            failure.error_class,
            failure.failure_kind,
            str(failure.retryable).lower(),
        )

    async def _emit_model_attempt_event(
        self,
        run_id: str,
        operation_key: str,
        attempt: int,
        failure: SafeModelFailure,
        *,
        will_retry: bool,
    ) -> None:
        event_type = "MODEL_RETRY_SCHEDULED" if will_retry else "MODEL_CALL_FAILED"
        suffix = "retry-scheduled" if will_retry else "failed"
        role_part = operation_key.split(":", maxsplit=2)[1].upper()
        await self._emit(
            EventRecord(
                run_id=run_id,
                event_key=f"{operation_key}:attempt-{attempt}:{suffix}",
                role=role_part,
                event_type=event_type,
                safe_payload={
                    "operation": operation_key,
                    "attempts": attempt,
                    "reasonCode": _MODEL_FAILURE_CODES.get(
                        failure.failure_kind,
                        "MODEL_CALL_FAILED",
                    ),
                    "retryable": failure.retryable,
                },
            )
        )

    async def _reserve_tool_call(
        self,
        run_id: str,
        observed_usage: UsageDelta,
        operation_key: str,
    ) -> int:
        async with self._budget_lock:
            self._synchronize_budget_ledger(run_id, observed_usage)
            reserved = self._budget_usage.plus(UsageDelta(tool_calls=1))
            self._check_usage_budget(reserved)
            try:
                attempt = await self._repository.reserve_tool_call(
                    run_id=run_id,
                    claim_token=self._runtime.claim_token,
                    operation_key=operation_key,
                    budget=self._budget,
                )
            except BudgetLimitExceededError as failure:
                raise RunBudgetExceededError(str(failure)) from failure
            except BudgetClaimConflictError as failure:
                raise StaleClaimError(str(failure)) from failure
            except BudgetOperationInvalidError as failure:
                raise WorkflowExecutionError(str(failure)) from failure
            self._budget_usage = reserved
            return attempt

    async def _settle_tool_call(
        self,
        run_id: str,
        operation_key: str,
        attempt: int,
    ) -> None:
        try:
            await self._repository.settle_tool_call(
                run_id=run_id,
                claim_token=self._runtime.claim_token,
                operation_key=operation_key,
                attempt=attempt,
            )
        except BudgetClaimConflictError as failure:
            raise StaleClaimError(str(failure)) from failure
        except BudgetOperationInvalidError as failure:
            raise WorkflowExecutionError(str(failure)) from failure

    def _synchronize_budget_ledger(
        self,
        run_id: str,
        observed_usage: UsageDelta,
    ) -> None:
        if self._budget_run_id is None:
            self._budget_run_id = run_id
            self._budget_usage = observed_usage
        elif self._budget_run_id != run_id:
            raise WorkflowExecutionError("compiled graph cannot be shared across workflow runs")
        else:
            # In a live run the ledger leads the checkpoint reducer; after recovery the
            # checkpoint may lead a fresh ledger. Usage is monotonic, so component-wise
            # synchronization handles both without double charging completed nodes.
            self._budget_usage = UsageDelta(
                model_calls=max(self._budget_usage.model_calls, observed_usage.model_calls),
                tool_calls=max(self._budget_usage.tool_calls, observed_usage.tool_calls),
                input_tokens=max(self._budget_usage.input_tokens, observed_usage.input_tokens),
                output_tokens=max(self._budget_usage.output_tokens, observed_usage.output_tokens),
                cost_cny=max(self._budget_usage.cost_cny, observed_usage.cost_cny),
            )
        self._check_usage_budget(self._budget_usage)

    def _check_usage_budget(self, usage: UsageDelta) -> None:
        if usage.model_calls > self._budget.max_model_calls:
            raise RunBudgetExceededError("model-call budget exceeded")
        if usage.tool_calls > self._budget.max_tool_calls:
            raise RunBudgetExceededError("tool-call budget exceeded")
        if usage.total_tokens > self._budget.max_tokens:
            raise RunBudgetExceededError("token budget exceeded")
        if usage.cost_cny > self._budget.max_cost_cny:
            raise RunBudgetExceededError("cost budget exceeded")

    @staticmethod
    def _usage(state: WorkflowState) -> UsageDelta:
        return UsageDelta.model_validate(state.get("usage", {}))

    @staticmethod
    def _insufficient_answer() -> str:
        return "现有可信证据不足; 无法在不臆测的前提下给出结论。"
