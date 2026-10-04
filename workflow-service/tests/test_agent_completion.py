"""Required criteria, current shared adjudication and stale prerequisite outcomes."""

import copy

import pytest

from deepresearch_workflow.agent_completion import criterion_id, ensure_criteria
from deepresearch_workflow.agent_protocol import AgentTask
from deepresearch_workflow.agent_question_segments import PLANNER_VERSION

from .test_agent_investigations import action_context, check, claim


async def bound_check(context, task_id, claims, indices=None, *, bind=True):
    task = next(t for t in context.state["tasks"] if t["task_id"] == task_id)
    ensure_criteria(context.state["run_id"], context.state["tasks"])
    context.state["decision_steps"] += 1
    context.state["action_sequence"] += 1
    context.state["decision"] = {
        "planner_contract": PLANNER_VERSION,
        "action": "check_claims",
        "task_id": task_id,
        "claims": claims,
        "reason": "Check the stored standards",
        "criterion_bindings": [
            {"criterion_id": task["criteria"][index]["criterion_id"], "claim_index": i}
            for i, index in enumerate(indices or range(len(claims)))
        ]
        if bind
        else [],
    }
    update = await context.graph.act(context.state)
    context.state.update(update)
    return update


async def test_multiple_stored_criteria_cannot_complete_from_one_bound_or_unbound_claim():
    context = await action_context()
    context.backend.status = "supported"
    context.state["tasks"][0]["acceptance_criteria"] = ["Verify version", "Verify rate"]
    context.state["tasks"][0]["criteria"] = []
    await bound_check(context, "task-a", [claim("Version claim")], [0])
    task = context.state["tasks"][0]
    assert task["status"] == "blocked" and [c["status"] for c in task["criteria"]] == [
        "resolved",
        "uncovered",
    ]
    await bound_check(context, "task-a", [claim("Rate claim")], [1])
    assert context.state["tasks"][0]["status"] == "done"
    assert len(context.state["investigations"]) == 2
    other = await action_context()
    other.backend.status = "supported"
    await bound_check(other, "task-a", [claim("Version claim")], bind=False)
    assert other.state["tasks"][0]["status"] != "done"


@pytest.mark.parametrize("failure", [None, {"errorCode": "CHECK_REQUEST_INVALID"}])
async def test_shared_new_dispute_or_failure_revokes_all_bound_tasks_and_blocks_new_dependencies(
    failure,
):
    context = await action_context()
    context.backend.status = "supported"
    claims = [claim("Shared claim")]
    await check(context, "task-a", claims)
    await check(context, "task-b", claims)
    assert all(t["status"] == "done" for t in context.state["tasks"])
    context.backend.status = "contested"
    context.backend.failure = failure
    await check(context, "task-b", claims)
    assert all(t["status"] == "blocked" for t in context.state["tasks"])
    context.state["tasks"].append(
        AgentTask(
            task_id="task-c",
            objective="Use the shared finding",
            dependencies=["task-a"],
            acceptance_criteria=["Recheck dependent conclusion"],
            plan_version=1,
        ).model_dump(mode="json")
    )
    calls = len(context.backend.requests)
    result = await bound_check(context, "task-c", [claim("Dependent claim")])
    assert (
        result["observations"][-1]["errorCode"] == "DEPENDENCY_NOT_DONE"
        and len(context.backend.requests) == calls
    )
    restored = await action_context()
    restored.state = copy.deepcopy(context.state)
    result = await bound_check(restored, "task-c", [claim("Dependent claim")])
    assert result["observations"][-1]["errorCode"] == "DEPENDENCY_NOT_DONE"


async def test_already_used_prerequisite_is_stale_even_after_parent_resolves_again():
    context = await action_context()
    context.backend.status = "supported"
    context.state["tasks"][1]["dependencies"] = ["task-a"]
    await check(context, "task-a", [claim("Parent claim")])
    await check(context, "task-b", [claim("Dependent claim")])
    old_decision = copy.deepcopy(context.state["decision"])
    old_step = context.state["decision_steps"]
    assert all(t["status"] == "done" for t in context.state["tasks"])
    context.backend.status = "contested"
    await check(context, "task-a", [claim("Parent claim")])
    assert context.state["tasks"][1]["criteria"][0]["status"] == "stale"
    context.backend.status = "supported"
    await check(context, "task-a", [claim("Parent claim")])
    assert (
        context.state["tasks"][0]["status"] == "done"
        and context.state["tasks"][1]["status"] == "blocked"
    )
    latest_step = context.state["decision_steps"]
    context.state["decision"] = old_decision
    context.state["decision_steps"] = old_step
    context.state["action_sequence"] = old_step
    context.state.update(await context.graph.act(context.state))
    assert context.state["tasks"][1]["criteria"][0]["status"] == "stale"
    context.state["decision_steps"] = latest_step
    context.state["action_sequence"] = latest_step
    await check(context, "task-b", [claim("Dependent claim")])
    assert context.state["tasks"][1]["status"] == "done"


async def test_shared_investigation_only_fulfills_each_tasks_bound_standard():
    context = await action_context()
    context.backend.status = "supported"
    context.state["tasks"][1]["acceptance_criteria"] = ["Verify version", "Verify rate"]
    context.state["tasks"][1]["criteria"] = []
    await bound_check(context, "task-a", [claim("Version claim")])
    await bound_check(context, "task-b", [claim("Version claim")], [0])
    assert (
        context.state["tasks"][0]["status"] == "done"
        and context.state["tasks"][1]["status"] == "blocked"
    )
    assert context.state["tasks"][1]["criteria"][1]["status"] == "uncovered"


@pytest.mark.parametrize(
    "bad", ["unknown", "cross_task", "cross_run", "duplicate", "scope", "group_scope", "same_claim"]
)
async def test_coverage_forgery_and_scope_mutation_are_rejected(bad):
    context = await action_context()
    context.backend.status = "supported"
    if bad in {"duplicate", "same_claim"}:
        context.state["tasks"][0]["acceptance_criteria"] = [
            "Verify first standard",
            "Verify second standard",
        ]
        context.state["tasks"][0]["criteria"] = []
    ensure_criteria(context.state["run_id"], context.state["tasks"])
    task = context.state["tasks"][0]
    ids = [c["criterion_id"] for c in task["criteria"]]
    if bad in {"scope", "group_scope"}:
        await bound_check(context, "task-a", [claim("First pinned scope")])
    context.state["decision_steps"] += 1
    context.state["action_sequence"] += 1
    claims = [claim("Altered scope")]
    bindings = [{"criterion_id": ids[0], "claim_index": 0}]
    if bad == "unknown":
        bindings[0]["criterion_id"] = "criterion-from-an-unknown-standard"
    if bad == "cross_task":
        bindings[0]["criterion_id"] = context.state["tasks"][1]["criteria"][0]["criterion_id"]
    if bad == "cross_run":
        bindings[0]["criterion_id"] = criterion_id(
            "different-run", task["task_id"], 0, task["acceptance_criteria"][0]
        )
    if bad == "group_scope":
        claims = [claim("First pinned scope"), claim("Added unrelated group member")]
    if bad in {"duplicate", "same_claim"}:
        claims = [
            claim("One fact"),
            claim("Other fact") if bad == "duplicate" else claim("One fact"),
        ]
        bindings = [
            {"criterion_id": ids[0], "claim_index": 0},
            {"criterion_id": ids[0] if bad == "duplicate" else ids[1], "claim_index": 1},
        ]
    context.state["decision"] = {
        "planner_contract": PLANNER_VERSION,
        "action": "check_claims",
        "task_id": "task-a",
        "claims": claims,
        "criterion_bindings": bindings,
        "reason": "Attempt invalid coverage",
    }
    before = len(context.backend.requests)
    result = await context.graph.act(context.state)
    assert result["observations"][-1]["errorCode"] in {
        "CRITERION_NOT_IN_TASK",
        "CRITERION_COVERAGE_INVALID",
        "CRITERION_SCOPE_CHANGED",
        "CRITERION_CLAIM_REUSED",
    }
    assert len(context.backend.requests) == before


async def test_refuted_verification_can_resolve_a_criterion_without_forcing_support():
    context = await action_context()
    context.backend.status = "refuted"
    # The substitute's generic conflict gap is removed to represent a valid resolved refutation.
    original = context.backend.check

    async def refuted(*args):
        result = await original(*args)
        result["gaps"] = []
        return result

    context.backend.check = refuted
    await check(context, "task-a", [claim("Claim disproved by the original")])
    assert context.state["tasks"][0]["status"] == "done"
