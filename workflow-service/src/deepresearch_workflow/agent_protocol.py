"""Round-1 service protocol; does not modify frozen v0 record schemas."""

from __future__ import annotations

from typing import Annotated, Any, Literal

from pydantic import Field, model_validator
from typing_extensions import TypedDict

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


class ClaimScope(StrictModel):
    subject: str = Field(min_length=1, max_length=1000)
    version: TaggedValue
    valid_at: TaggedValue
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


class ModelRequest(StrictModel):
    name: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
    instruction: str = Field(min_length=1, max_length=12000)
    payload: dict[str, Any]
    result_schema: dict[str, Any] = Field(alias="schema")
    max_output_tokens: int = Field(default=1024, ge=1, le=1024)
    request_binding: dict[str, str] = Field(default_factory=dict)


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
    decision: dict[str, Any]
    observations: list[dict[str, Any]]
    candidates: list[dict[str, Any]]
    evidence: list[dict[str, Any]]
    packet: dict[str, Any]
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
