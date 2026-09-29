from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from typing_extensions import TypedDict


class ToolName(StrEnum):
    KB_SEARCH = "kb_search"
    WEB_SEARCH = "web_search"
    CALCULATOR = "calculator"


ALLOWED_TOOLS = frozenset(ToolName)


class WorkflowStatus(StrEnum):
    QUEUED = "QUEUED"
    PLANNING = "PLANNING"
    WORKING = "WORKING"
    REVIEWING = "REVIEWING"
    SYNTHESIZING = "SYNTHESIZING"
    FINALIZING = "FINALIZING"
    SUCCEEDED = "SUCCEEDED"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    TIMED_OUT = "TIMED_OUT"
    BUDGET_EXCEEDED = "BUDGET_EXCEEDED"

    @property
    def terminal(self) -> bool:
        return self in {
            self.SUCCEEDED,
            self.INSUFFICIENT_EVIDENCE,
            self.FAILED,
            self.CANCELLED,
            self.TIMED_OUT,
            self.BUDGET_EXCEEDED,
        }


class WorkflowStage(StrEnum):
    QUEUED = "QUEUED"
    PLANNING = "PLANNING"
    WORKING = "WORKING"
    REVIEWING = "REVIEWING"
    SYNTHESIZING = "SYNTHESIZING"
    FINALIZING = "FINALIZING"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class RunBudget(StrictModel):
    """Immutable per-run limits loaded from the Java-created JSON snapshot."""

    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        strict=True,
    )

    max_tasks: int = Field(default=4, alias="maxTasks", ge=1, le=4)
    max_concurrency: int = Field(default=2, alias="maxConcurrency", ge=1, le=2)
    max_revision_rounds: int = Field(
        default=1,
        alias="maxRevisionRounds",
        ge=0,
        le=1,
    )
    max_model_calls: int = Field(default=24, alias="maxModelCalls", ge=1, le=24)
    max_tool_calls: int = Field(default=16, alias="maxToolCalls", ge=1, le=16)
    max_tokens: int = Field(default=100_000, alias="maxTokens", ge=1_000, le=100_000)
    max_cost_cny: float = Field(
        default=1.0,
        alias="maxCostCny",
        ge=0.01,
        le=1.0,
    )
    deadline_seconds: int = Field(
        default=120,
        alias="deadlineSeconds",
        ge=10,
        le=120,
    )


class AgentRunBudget(RunBudget):
    runtime: Literal["agent"]
    max_tasks: int = Field(default=16, alias="maxTasks", ge=1, le=16)
    max_concurrency: int = Field(default=1, alias="maxConcurrency", ge=1, le=1)
    max_revision_rounds: int = Field(default=2, alias="maxRevisionRounds", ge=0, le=2)
    max_model_calls: int = Field(default=16, alias="maxModelCalls", ge=1, le=16)
    deadline_seconds: int = Field(default=180, alias="deadlineSeconds", ge=10, le=180)
    max_decision_steps: int = Field(default=8, alias="maxDecisionSteps", ge=1, le=8)
    max_input_tokens: int = Field(default=64_000, alias="maxInputTokens", ge=1, le=64_000)
    max_output_tokens: int = Field(default=16_384, alias="maxOutputTokens", ge=1, le=16_384)



class PlannedTaskDraft(StrictModel):
    objective: str = Field(min_length=3, max_length=280)
    query: str = Field(min_length=1, max_length=1_000)
    tool: ToolName


class PlanOutput(StrictModel):
    tasks: list[PlannedTaskDraft] = Field(min_length=1, max_length=4)
    summary: str = Field(min_length=1, max_length=500)

    @field_validator("tasks")
    @classmethod
    def no_duplicate_tasks(cls, value: list[PlannedTaskDraft]) -> list[PlannedTaskDraft]:
        keys = {(item.tool, item.query.strip().casefold()) for item in value}
        if len(keys) != len(value):
            raise ValueError("duplicate tool/query tasks are not allowed")
        return value


class WorkItem(StrictModel):
    task_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{0,63}$")
    objective: str = Field(min_length=3, max_length=280)
    query: str = Field(min_length=1, max_length=1_000)
    tool: ToolName
    revision_of: str | None = Field(default=None, max_length=64)


class WorkerPreparation(StrictModel):
    focused_query: str = Field(min_length=1, max_length=1_000)
    safe_summary: str = Field(min_length=1, max_length=300)


class ToolEvidence(StrictModel):
    source_id: str = Field(min_length=1, max_length=300)
    content: str = Field(min_length=1, max_length=6_000)
    source_uri: str | None = Field(default=None, max_length=2_000)
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)


class EvidenceRecord(StrictModel):
    task_id: str
    tool: ToolName
    call_id: str
    source_id: str
    content: str = Field(max_length=6_000)
    source_uri: str | None = None
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)
    error_code: str | None = Field(default=None, max_length=64)

    @property
    def usable(self) -> bool:
        return not self.error_code and bool(self.content.strip()) and self.confidence > 0


class RevisionTaskDraft(StrictModel):
    objective: str = Field(min_length=3, max_length=280)
    query: str = Field(min_length=1, max_length=1_000)
    tool: ToolName
    revision_of: str | None = Field(default=None, max_length=64)


class ReviewOutput(StrictModel):
    sufficient: bool
    # The reviewer may need to name several independent evidence gaps after a revision
    # round. 600 characters proved too small for that bounded job and caused an otherwise
    # valid decision to fail schema validation. Keep a hard ceiling for checkpoint/event
    # size, while leaving headroom above the 1,200-character generation instruction.
    summary: str = Field(
        min_length=1,
        max_length=2_000,
        description=(
            "Concise audit-safe verdict and at most three evidence gaps; "
            "target no more than 1,200 Unicode characters."
        ),
    )
    revision_tasks: list[RevisionTaskDraft] = Field(default_factory=list, max_length=4)

    @model_validator(mode="after")
    def consistent_decision(self) -> ReviewOutput:
        if self.sufficient and self.revision_tasks:
            raise ValueError("a sufficient review cannot request revision tasks")
        return self


class SynthesisDraft(StrictModel):
    """Small provider-facing schema; source IDs remain application-owned."""

    answer: str = Field(
        min_length=1,
        max_length=20_000,
        description=(
            "Complete final answer text using only exact [来源N] evidence-index markers."
        ),
    )
    grounded: bool = Field(
        description="Whether the answer is intended to be fully supported by supplied evidence."
    )


class SynthesisOutput(StrictModel):
    """Application-owned synthesis result stored in the durable model receipt."""

    answer: str = Field(
        min_length=1,
        max_length=20_000,
        description="Complete final answer text with inline [来源N] markers.",
    )
    citations: list[str] = Field(
        default_factory=list,
        max_length=30,
        description="Complete positional source-id table for every marker number in answer.",
    )
    grounded: bool = Field(
        description="Whether the answer is intended to be fully supported by supplied evidence."
    )
    # This is produced only by deterministic application validation, never by the
    # provider-facing schema. The default keeps older settled receipts replayable.
    citation_contract_error: Literal[
        "MISSING_MARKERS",
        "MARKER_OUT_OF_RANGE",
        "TOO_MANY_CITATIONS",
    ] | None = None


class UsageDelta(StrictModel):
    model_calls: int = Field(default=0, ge=0)
    tool_calls: int = Field(default=0, ge=0)
    input_tokens: int = Field(default=0, ge=0)
    output_tokens: int = Field(default=0, ge=0)
    cost_cny: float = Field(default=0.0, ge=0.0)

    @property
    def total_tokens(self) -> int:
        return self.input_tokens + self.output_tokens

    def plus(self, other: UsageDelta) -> UsageDelta:
        return UsageDelta(
            model_calls=self.model_calls + other.model_calls,
            tool_calls=self.tool_calls + other.tool_calls,
            input_tokens=self.input_tokens + other.input_tokens,
            output_tokens=self.output_tokens + other.output_tokens,
            cost_cny=round(self.cost_cny + other.cost_cny, 8),
        )


class ModelBudgetReservation(StrictModel):
    """Durable decision returned before one logical model operation."""

    attempt: int = Field(ge=1)
    unknown_attempts: int = Field(default=0, ge=0)
    replay_value: dict[str, Any] | None = None
    replay_usage: UsageDelta | None = None

    @model_validator(mode="after")
    def replay_fields_are_atomic(self) -> ModelBudgetReservation:
        if (self.replay_value is None) != (self.replay_usage is None):
            raise ValueError("replay value and usage must either both be present or both be absent")
        return self

    @property
    def replay(self) -> bool:
        return self.replay_value is not None


def merge_usage(left: dict[str, Any] | None, right: dict[str, Any] | None) -> dict[str, Any]:
    first = UsageDelta.model_validate(left or {})
    second = UsageDelta.model_validate(right or {})
    return first.plus(second).model_dump(mode="json")


def append_unique_evidence(
    left: list[dict[str, Any]] | None,
    right: list[dict[str, Any]] | None,
) -> list[dict[str, Any]]:
    result: dict[tuple[str, str, str], dict[str, Any]] = {}
    for item in [*(left or []), *(right or [])]:
        evidence = EvidenceRecord.model_validate(item)
        result[(evidence.task_id, evidence.call_id, evidence.source_id)] = evidence.model_dump(
            mode="json"
        )

    return list(result.values())


class WorkflowState(TypedDict, total=False):
    run_id: str
    graph_thread_id: str
    grant_id: str
    user_id: str
    question: str
    context_snapshot: dict[str, Any]
    requested_scopes: list[str]
    deadline_at: str
    status: str
    stage: str
    plan_summary: str
    tasks: list[dict[str, Any]]
    # Total Worker tasks scheduled across the initial plan and every revision.
    # This is a run-level budget counter; ``tasks`` only contains the current round.
    worker_task_count: int
    evidence: Annotated[list[dict[str, Any]], append_unique_evidence]
    usage: Annotated[dict[str, Any], merge_usage]
    review: dict[str, Any]
    revision_round: int
    should_revise: bool
    revision_blocked_reason: str | None
    final_answer: str
    citations: list[str]
    final_status: str
    error_code: str | None
    error_message: str | None


class WorkerInput(TypedDict):
    run_id: str
    grant_id: str
    user_id: str
    question: str
    requested_scopes: list[str]
    deadline_at: str
    base_usage: dict[str, Any]
    task: dict[str, Any]


class ModelCall[T: BaseModel](StrictModel):
    value: T
    usage: UsageDelta = Field(default_factory=UsageDelta)


class ToolExecutionRequest(StrictModel):
    run_id: str
    grant_id: str
    claim_token: str
    task: WorkItem
    requested_scopes: list[str]
    arguments: dict[str, Any]
    call_id: str
    timeout_seconds: float = Field(gt=0, le=60)


class ToolExecutionResult(StrictModel):
    call_id: str
    evidence: list[ToolEvidence] = Field(default_factory=list, max_length=10)
    error_code: str | None = Field(default=None, max_length=64)


class ClaimedRun(StrictModel):
    run_id: str
    session_id: str | None = None
    user_id: str
    question: str
    context_snapshot: dict[str, Any] = Field(default_factory=dict)
    endpoint: str
    graph_thread_id: str
    requested_scopes: list[str] = Field(default_factory=list)
    grant_id: str
    budget: RunBudget | AgentRunBudget = Field(default_factory=RunBudget)
    claim_token: str
    deadline_at: datetime
    status: WorkflowStatus
    stage: str

    @field_validator("deadline_at")
    @classmethod
    def deadline_has_timezone(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value


class FinalizeRequest(StrictModel):
    claimToken: str
    status: WorkflowStatus
    answer: str | None = None
    citations: list[str] = Field(default_factory=list)
    usage: dict[str, Any] = Field(default_factory=dict)
    errorCode: str | None = None
    errorMessage: str | None = None


class EventRecord(StrictModel):
    run_id: str
    event_key: str
    role: str
    event_type: str
    safe_payload: dict[str, Any] = Field(default_factory=dict)
    task_id: str | None = None


def deterministic_call_id(run_id: str, task: WorkItem) -> str:
    canonical = json.dumps(
        {
            "run_id": run_id,
            "task_id": task.task_id,
            "tool": task.tool.value,
            "query": task.query,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )
    digest = hashlib.sha256(canonical.encode()).hexdigest()[:32]
    return f"tool-{digest}"


def utc_now() -> datetime:
    return datetime.now(UTC)
