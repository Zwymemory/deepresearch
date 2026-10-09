"""Round-1 service protocol; does not modify frozen v0 record schemas."""

from __future__ import annotations

import re
from datetime import datetime
from typing import Annotated, Any, Literal

from pydantic import Field, field_validator, model_validator
from typing_extensions import TypedDict

from .agent_decision_instruction import CHECK_CAPACITY_POLICIES
from .agent_question_segments import PLANNER_VERSION, SegmentRequirementDraft
from .agent_requirements import RequirementBinding, RequirementDraft
from .domain import AgentRunBudget as AgentRunBudget
from .domain import StrictModel

ShortText = Annotated[str, Field(min_length=1, max_length=400)]
Identifier = Annotated[str, Field(min_length=1, max_length=128)]


class KnownValue(StrictModel):
    status: Literal["known"]
    value: str = Field(min_length=1, max_length=200)


class UnknownValue(StrictModel):
    status: Literal["unknown"]
    value: None
    reason: str = Field(min_length=1, max_length=400)


TaggedValue = Annotated[KnownValue | UnknownValue, Field(discriminator="status")]

_VALID_AT_PATTERN = (
    r"^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])T"
    r"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
    r"(?:\.[0-9]{1,9})?(?:Z|[+-](?:(?:0[0-9]|1[0-7]):[0-5][0-9]|18:00))$"
)


def valid_at_instant(value: str) -> tuple[int, int]:
    """Return an exact UTC ordinal-second/nanosecond pair without float or truncation."""
    if not isinstance(value, str) or re.fullmatch(_VALID_AT_PATTERN, value) is None:
        raise ValueError("valid_at needs a real offset timestamp with seconds")
    try:
        local = datetime.fromisoformat(value[:19])
    except ValueError:
        raise ValueError("valid_at must be a real calendar date") from None
    if value.endswith("Z"):
        zone = "Z"
        fraction = value[19:-1]
    else:
        zone = value[-6:]
        fraction = value[19:-6]
    nanos = int(fraction[1:].ljust(9, "0")) if fraction else 0
    offset_seconds = 0
    if zone != "Z":
        hours, minutes = int(zone[1:3]), int(zone[4:6])
        if minutes > 59 or hours > 18 or (hours == 18 and minutes != 0):
            raise ValueError("valid_at timezone offset is outside the supported range")
        offset_seconds = (hours * 60 + minutes) * 60 * (1 if zone[0] == "+" else -1)
    local_seconds = local.toordinal() * 86400 + local.hour * 3600 + local.minute * 60 + local.second
    return local_seconds - offset_seconds, nanos


class KnownValidAt(StrictModel):
    status: Literal["known"]
    value: str = Field(
        min_length=1,
        max_length=200,
        pattern=_VALID_AT_PATTERN,
        json_schema_extra={"format": "date-time"},
        description="Fact-effective RFC3339 timestamp with seconds and explicit timezone",
    )

    @field_validator("value")
    @classmethod
    def real_offset_datetime(cls, value: str) -> str:
        valid_at_instant(value)
        return value


ValidAtValue = Annotated[KnownValidAt | UnknownValue, Field(discriminator="status")]


class ClaimScope(StrictModel):
    subject: str = Field(min_length=1, max_length=1000)
    version: TaggedValue
    valid_at: ValidAtValue
    conditions: list[ShortText] = Field(default_factory=list, max_length=20)


class ClaimDraft(StrictModel):
    text: str = Field(min_length=1, max_length=4000)
    kind: Literal["factual", "inference", "recommendation"]
    applicability: ClaimScope


class TaskDraft(StrictModel):
    objective: str = Field(min_length=1, max_length=280)
    dependencies: list[Identifier] = Field(default_factory=list, max_length=16)
    acceptance_criteria: list[ShortText] = Field(min_length=1, max_length=5)


class AgentTask(TaskDraft):
    task_id: str = Field(pattern=r"^[a-z0-9][a-z0-9._-]{0,63}$")
    status: Literal["pending", "running", "blocked", "done", "cancelled"] = "pending"
    evidence_ids: list[str] = Field(default_factory=list, max_length=32)
    criteria: list[dict[str, Any]] = Field(default_factory=list, max_length=5)
    plan_version: int = Field(ge=1)


class CriterionBinding(StrictModel):
    criterion_id: Identifier
    claim_index: int = Field(ge=0, le=3)


class AgentDecision(StrictModel):
    requirements: list[RequirementDraft] = Field(default_factory=list, max_length=32)
    requirement_bindings: list[RequirementBinding] = Field(default_factory=list, max_length=32)
    action: Literal[
        "search", "read_source", "revise_plan", "check_claims", "finish", "stop_with_gaps"
    ]
    reason: str = Field(
        min_length=1,
        max_length=400,
        description="Brief public action rationale; no private reasoning",
    )
    task_id: str | None = Field(default=None, max_length=64)
    investigation_id: Identifier | None = Field(
        default=None,
        description=(
            "Server-issued identity from a previous successful check. "
            "Omit for the first check of a claim group; never invent one."
        ),
    )
    evidence_ids: list[Identifier] = Field(default_factory=list, max_length=32)
    query: str | None = Field(default=None, min_length=1, max_length=1000)
    tool: Literal["kb_search", "web_search", "calculator"] | None = None
    source_id: str | None = Field(default=None, max_length=300)
    tasks: list[TaskDraft] = Field(default_factory=list, max_length=8)
    claims: list[ClaimDraft] = Field(default_factory=list, max_length=4)
    criterion_bindings: list[CriterionBinding] = Field(default_factory=list, max_length=4)
    answer: str | None = Field(default=None, min_length=1, max_length=6000)
    citations: list[Identifier] = Field(default_factory=list, max_length=16)
    gaps: list[ShortText] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def required_action_inputs(self):
        required = {
            "search": bool(self.query and self.tool),
            "read_source": bool(self.source_id),
            "revise_plan": bool(self.tasks),
            "check_claims": bool(self.claims),
            "finish": True,
            "stop_with_gaps": bool(self.gaps),
        }
        if not required[self.action]:
            raise ValueError("action lacks required arguments")
        return self


class SegmentAgentDecision(AgentDecision):
    planner_contract: Literal[PLANNER_VERSION]
    requirements: list[SegmentRequirementDraft] = Field(default_factory=list, max_length=32)


CONTINUATION_VERSION = "agent-frozen-requirements/1"


class ContinuationAgentDecision(SegmentAgentDecision):
    continuation_contract: Literal[CONTINUATION_VERSION]
    requirements_ref: str = Field(pattern=r"^[a-f0-9]{64}$")

    @classmethod
    def wire_schema(cls):
        schema = cls.model_json_schema()
        # No declaration field in continuation, even an empty or unchanged one.
        schema["properties"].pop("requirements")
        return schema


class ModelRequest(StrictModel):
    name: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
    instruction: str = Field(min_length=1, max_length=12000)
    payload: dict[str, Any]
    result_schema: dict[str, Any] = Field(alias="schema")
    max_output_tokens: int = Field(default=1024, ge=1, le=4096)
    request_binding: dict[str, str] = Field(default_factory=dict)

    @model_validator(mode="after")
    def output_allocation(self):
        if self.max_output_tokens > 1024 and not (
            (
                self.name == "EvidenceCheck"
                and self.request_binding.get("instruction_policy") in CHECK_CAPACITY_POLICIES
            )
            or (
                self.name == "AgentDecision"
                and self.max_output_tokens <= 2048
                and self.request_binding.get("context_encoding") == "shared-context-values/1"
            )
        ):
            raise ValueError(
                "Expanded output requires an authorized CHECK or lossless decision policy"
            )
        return self


class ModelResult(StrictModel):
    value: dict[str, Any]
    input_tokens: int | None = Field(default=None, ge=0)
    output_tokens: int | None = Field(default=None, ge=0)
    cost_cny: float | None = Field(default=None, ge=0)
    request_binding: dict[str, str] = Field(default_factory=dict)


class AgentState(TypedDict, total=False):
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
    usage: dict[str, Any]
    tasks: list[dict[str, Any]]
    plan_version: int
    decision_steps: int
    planner_contract: str
    instruction_policy: str
    unusable_check: dict[str, Any]
    continuation_contract: str
    action_progress_step: int
    action_sequence: int
    decision: dict[str, Any]
    observations: list[dict[str, Any]]
    candidates: list[dict[str, Any]]
    evidence: list[dict[str, Any]]
    packet: dict[str, Any]
    original_requirements: dict[str, Any]
    requirement_bindings: list[dict[str, str]]
    requirement_coverage: dict[str, Any]
    investigations: dict[str, dict[str, Any]]
    task_investigations: dict[str, str]
    no_progress: int
    conflict_rounds: int
    agent_usage: dict[str, Any]
    final_answer: str
    citations: list[str]
    final_status: str
    report: dict[str, Any]
    error_code: str | None
    error_message: str | None
    project_summary_view: dict[str, Any]
