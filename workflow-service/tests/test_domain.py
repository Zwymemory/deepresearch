from __future__ import annotations

import pytest
from pydantic import ValidationError

from deepresearch_workflow.domain import (
    AgentRunBudget,
    PlannedTaskDraft,
    PlanOutput,
    ReviewOutput,
    RunBudget,
    ToolName,
    UsageDelta,
    WorkItem,
    deterministic_call_id,
    merge_usage,
)
from deepresearch_workflow.model import conservative_token_estimate


def test_memory_agent_budget_accepts_new_finite_limits_and_preserves_legacy_defaults():
    budget = AgentRunBudget(runtime="agent", maxInputTokens=240000,
                            maxModelCalls=24, maxDecisionSteps=16)
    assert budget.max_input_tokens == 240000
    assert budget.max_decision_steps == 16
    old = AgentRunBudget(runtime="agent")
    assert (old.max_input_tokens, old.max_decision_steps, old.max_model_calls) == (64000, 8, 16)
    for field, value in [("maxInputTokens", 240001), ("maxModelCalls", 25),
                         ("maxDecisionSteps", 17), ("maxTokens", 200001), ("maxCostCny", 2.01)]:
        with pytest.raises(ValidationError):
            AgentRunBudget.model_validate({"runtime": "agent", field: value})


def test_usage_reducer_sums_parallel_deltas() -> None:
    merged = merge_usage(
        UsageDelta(model_calls=1, input_tokens=10).model_dump(),
        UsageDelta(tool_calls=2, output_tokens=4, cost_cny=0.01).model_dump(),
    )
    assert merged == {
        "model_calls": 1,
        "tool_calls": 2,
        "input_tokens": 10,
        "output_tokens": 4,
        "cost_cny": 0.01,
    }


def test_plan_rejects_duplicate_tool_query() -> None:
    task = PlannedTaskDraft(objective="Find evidence", query="same", tool=ToolName.KB_SEARCH)
    with pytest.raises(ValidationError):
        PlanOutput(tasks=[task, task], summary="duplicate")


def test_review_summary_has_bounded_headroom_for_multiple_evidence_gaps() -> None:
    review = ReviewOutput(sufficient=False, summary="x" * 2_000)

    assert len(review.summary) == 2_000
    with pytest.raises(ValidationError) as captured:
        ReviewOutput(sufficient=False, summary="x" * 2_001)

    assert captured.value.errors(include_url=False)[0]["type"] == "string_too_long"


def test_call_id_is_deterministic_and_query_sensitive() -> None:
    first = WorkItem(
        task_id="task-01", objective="Find evidence", query="alpha", tool=ToolName.KB_SEARCH
    )
    same = first.model_copy()
    changed = first.model_copy(update={"query": "beta"})
    assert deterministic_call_id("run", first) == deterministic_call_id("run", same)
    assert deterministic_call_id("run", first) != deterministic_call_id("run", changed)


def test_conservative_token_estimate_is_nonzero_for_ascii_and_cjk() -> None:
    assert conservative_token_estimate("") == 1
    assert conservative_token_estimate("twelve chars") >= 4
    assert conservative_token_estimate("中文证据") >= 4


def test_run_budget_reads_camel_case_snapshot_and_keeps_defaults() -> None:
    budget = RunBudget.model_validate(
        {
            "maxTasks": 2,
            "maxConcurrency": 1,
            "maxRevisionRounds": 0,
            "maxModelCalls": 12,
            "maxToolCalls": 8,
            "maxTokens": 50_000,
            "maxCostCny": 0.5,
            "deadlineSeconds": 90,
        }
    )

    assert budget.max_tasks == 2
    assert budget.max_concurrency == 1
    assert budget.model_dump(by_alias=True)["maxCostCny"] == 0.5
    assert RunBudget().model_dump(by_alias=True) == {
        "maxTasks": 4,
        "maxConcurrency": 2,
        "maxRevisionRounds": 1,
        "maxModelCalls": 24,
        "maxToolCalls": 16,
        "maxTokens": 100_000,
        "maxCostCny": 1.0,
        "deadlineSeconds": 120,
    }


@pytest.mark.parametrize(
    "snapshot",
    [
        {"maxTasks": 5},
        {"maxConcurrency": 3},
        {"maxRevisionRounds": 2},
        {"maxModelCalls": 25},
        {"maxToolCalls": 17},
        {"maxTokens": 100_001},
        {"maxCostCny": 1.01},
        {"deadlineSeconds": 121},
        {"maxTasks": "4"},
        {"unknownLimit": 1},
    ],
)
def test_run_budget_rejects_values_outside_hard_caps(snapshot: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        RunBudget.model_validate(snapshot)
