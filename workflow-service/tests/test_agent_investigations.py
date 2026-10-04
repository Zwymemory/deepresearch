"""Actual action node with scoped investigation state; no provider or network execution."""

import copy
from types import SimpleNamespace

import pytest

from deepresearch_workflow.agent_completion import ensure_criteria
from deepresearch_workflow.agent_investigations import scope_key
from deepresearch_workflow.agent_protocol import AgentTask
from deepresearch_workflow.agent_question_segments import PLANNER_VERSION
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.graph import WorkflowExecutionError
from deepresearch_workflow.ports import RepositoryEventSink

from .test_agent_runtime import setup


def claim(text):
    unknown = {"status": "unknown", "value": None, "reason": "Not independently established"}
    return {
        "text": text,
        "kind": "factual",
        "applicability": {
            "subject": text,
            "version": copy.deepcopy(unknown),
            "valid_at": copy.deepcopy(unknown),
            "conditions": [],
        },
    }


class CheckBackend:
    def __init__(self):
        self.requests = []
        self.failure = None
        self.status = "contested"

    async def check(self, state, task, key, claims, gateway):
        self.requests.append(copy.deepcopy((state.get("packet", {}), task, claims)))
        if isinstance(self.failure, Exception):
            raise self.failure
        if self.failure:
            return copy.deepcopy(self.failure)
        prior = state.get("packet", {})
        round_number = prior.get("dispute_round", -1) + 1
        return {
            "investigation_id": "server-" + scope_key(claims),
            "packet_id": "packet-" + key,
            "check_id": "check-" + key,
            "check_ids": [*prior.get("check_ids", []), "check-" + key],
            "dispute_round": round_number,
            "claim_specs": copy.deepcopy(claims),
            "records": [
                {"record_type": "Claim", **c, "decision_status": self.status} for c in claims
            ],
            "gaps": []
            if self.status == "supported"
            else ["The original conflict remains unresolved"],
        }


async def action_context():
    context = setup("version-difference")
    backend = CheckBackend()
    graph = AutonomousResearchGraph(
        model=context[4],
        tools=context[5],
        repository=context[2],
        ledger=context[3],
        evidence=backend,
        events=RepositoryEventSink(context[2]),
        budget=context[1].budget,
        claim_token=context[1].claim_token,
    )
    state = {
        "run_id": context[1].run_id,
        "grant_id": context[1].grant_id,
        "requested_scopes": context[1].requested_scopes,
        "deadline_at": context[1].deadline_at.isoformat(),
        "question": "Investigate both independent subjects",
    }
    state.update(await graph.initialize(state))
    state["tasks"] = [
        AgentTask(
            task_id=identity,
            objective=objective,
            acceptance_criteria=["Keep a traceable scoped decision"],
            plan_version=1,
        ).model_dump(mode="json")
        for identity, objective in (("task-a", "Subject A"), ("task-b", "Subject B"))
    ]
    ensure_criteria(state["run_id"], state["tasks"])
    return SimpleNamespace(graph=graph, state=state, backend=backend, ledger=context[3])


async def check(context, task_id, claims, *, investigation_id=None):
    context.state["decision_steps"] += 1
    context.state["decision"] = {
        "planner_contract": PLANNER_VERSION,
        "action": "check_claims",
        "task_id": task_id,
        "claims": claims,
        "reason": "Check the selected goal",
        "investigation_id": investigation_id,
        "criterion_bindings": [
            {
                "criterion_id": next(t for t in context.state["tasks"] if t["task_id"] == task_id)[
                    "criteria"
                ][0]["criterion_id"],
                "claim_index": 0,
            }
        ],
    }
    update = await context.graph.act(context.state)
    context.state.update(update)
    return update


async def test_independent_tasks_keep_distinct_scopes_parents_and_successful_records():
    context = await action_context()
    await check(context, "task-a", [claim("Subject A claim")])
    first = copy.deepcopy(context.state["packet"])
    await check(context, "task-b", [claim("Subject B claim")])
    investigations = context.state["investigations"]
    assert len(investigations) == 2
    assert investigations[scope_key([claim("Subject A claim")])]["packet"] == first
    assert context.backend.requests[1][0] == {}
    await check(context, "task-a", [claim("Subject A claim")])
    assert context.backend.requests[2][0]["check_id"] == first["check_id"]
    assert context.state["packet"]["dispute_round"] == 1


@pytest.mark.parametrize(
    "failure",
    [
        {"errorCode": "CHECK_REQUEST_INVALID"},
        {"records": []},
        WorkflowExecutionError("private source response", error_code="MODEL_SCHEMA_INVALID"),
        TimeoutError("private transport detail"),
    ],
)
async def test_failed_checks_preserve_prior_packet_history_and_block_dependencies(failure):
    context = await action_context()
    await check(context, "task-a", [claim("Subject A claim")])
    prior = copy.deepcopy(context.state["packet"])
    history = copy.deepcopy(context.state["investigations"])
    context.backend.failure = failure
    update = await check(context, "task-a", [claim("Subject A claim")])
    assert "packet" not in update and context.state["packet"] == prior
    for identity, entry in history.items():
        assert context.state["investigations"][identity]["packet"] == entry["packet"]
        assert context.state["investigations"][identity]["history"] == entry["history"]
    assert context.state["tasks"][0]["status"] == "blocked"
    assert "private" not in str(context.state["observations"][-1])
    context.state["tasks"][1]["dependencies"] = ["task-a"]
    dependent = await check(context, "task-b", [claim("Subject B claim")])
    assert dependent["observations"][-1]["errorCode"] == "DEPENDENCY_NOT_DONE"


async def test_reordered_claims_and_changed_task_reuse_history_not_a_new_root():
    context = await action_context()
    claims = [claim("Subject A claim"), claim("Another scoped claim")]
    await check(context, "task-a", claims)
    key = scope_key(claims)
    await check(context, "task-b", list(reversed(claims)))
    assert len(context.state["investigations"]) == 1
    assert context.state["packet"]["dispute_round"] == 1
    assert context.backend.requests[-1][0]["dispute_round"] == 0
    assert context.state["task_investigations"] == {"task-a": [key], "task-b": [key]}
    rewritten = await check(context, "task-b", [claim("Silently rewritten claim")])
    assert rewritten["observations"][-1]["errorCode"] in {
        "CLAIM_SCOPE_CHANGED",
        "CRITERION_SCOPE_CHANGED",
    }
    explicit = await check(
        context, "task-b", [claim("Silently rewritten claim")], investigation_id=key
    )
    assert explicit["observations"][-1]["errorCode"] in {
        "CLAIM_SCOPE_CHANGED",
        "CRITERION_SCOPE_CHANGED",
    }
    assert len(context.backend.requests) == 2


async def test_published_server_investigation_identity_reuses_checkpoint_history():
    from deepresearch_workflow.agent_investigations import public_investigations

    context = await action_context()
    claims = [claim("Subject A claim")]
    await check(context, "task-a", claims)
    server_id = context.state["packet"]["investigation_id"]
    assert public_investigations(context.state)[0]["investigation_id"] == server_id
    await check(context, "task-b", claims, investigation_id=server_id)
    assert (
        len(context.state["investigations"]) == 1 and context.state["packet"]["dispute_round"] == 1
    )


async def test_checkpoint_values_retain_both_investigations_after_failure_and_resume():
    context = await action_context()
    await check(context, "task-a", [claim("Subject A claim")])
    await check(context, "task-b", [claim("Subject B claim")])
    context.backend.failure = {"errorCode": "EVIDENCE_ACCESS_DENIED"}
    await check(context, "task-b", [claim("Subject B claim")])
    persisted = copy.deepcopy(context.state)
    restored = await action_context()
    restored.state = persisted
    await check(restored, "task-a", [claim("Subject A claim")])
    assert len(restored.state["investigations"]) == 2
    assert restored.backend.requests[-1][0]["dispute_round"] == 0
    assert (
        restored.state["investigations"][scope_key([claim("Subject B claim")])]["packet"]
        == context.state["investigations"][scope_key([claim("Subject B claim")])]["packet"]
    )


async def test_later_resolution_keeps_history_but_completes_all_bound_native_goals():
    context = await action_context()
    claims = [claim("Subject A claim")]
    await check(context, "task-a", claims)
    await check(context, "task-b", claims)
    context.backend.status = "supported"
    await check(context, "task-b", claims)
    entry = context.state["investigations"][scope_key(claims)]
    assert entry["history"][0]["gaps"] and not entry["packet"]["gaps"]
    assert all(t["status"] == "done" for t in context.state["tasks"])
    await check(context, "task-a", claims)
    assert context.state["observations"][-1]["errorCode"] == "DISPUTE_LIMIT"


async def test_actual_langgraph_checkpointer_restores_multiple_investigations_and_failed_check():
    from langgraph.checkpoint.memory import InMemorySaver

    from deepresearch_workflow.agent_protocol import ModelResult

    context = await action_context()
    decisions = [
        ("task-a", "Subject A claim"),
        ("task-b", "Subject B claim"),
        ("task-a", "Subject A claim"),
        ("task-b", "Subject B claim"),
    ]

    class Model:
        async def invoke(self, request):
            step = context.graph.budget.max_decision_steps - request.payload["remaining_decisions"]
            task, text = decisions[step]
            return ModelResult(
                value={
                    "planner_contract": request.request_binding["planner_contract"],
                    "action": "check_claims",
                    "task_id": task,
                    "claims": [claim(text)],
                    "criterion_bindings": [
                        {
                            "criterion_id": next(
                                t for t in context.state["tasks"] if t["task_id"] == task
                            )["criteria"][0]["criterion_id"],
                            "claim_index": 0,
                        }
                    ],
                    "reason": "Investigate the selected original scope",
                }
            )

    context.graph.model = Model()
    saver = InMemorySaver()
    graph = context.graph.compile(checkpointer=saver, interrupt_after=["act"])
    config = {"configurable": {"thread_id": "investigation-recovery"}}
    await graph.ainvoke(context.state, config)
    await graph.ainvoke(None, config)
    context.backend.failure = {"errorCode": "CHECK_REQUEST_INVALID"}
    await graph.ainvoke(None, config)
    failed = (await graph.aget_state(config)).values
    assert len(failed["investigations"]) == 2 and failed["tasks"][0]["status"] == "blocked"
    before = copy.deepcopy(failed["investigations"])
    context.graph.claim_token = "claim-recovered"
    context.backend.failure = None
    recovered = context.graph.compile(checkpointer=saver, interrupt_after=["act"])
    await recovered.ainvoke(None, config)
    state = (await recovered.aget_state(config)).values
    a, b = scope_key([claim("Subject A claim")]), scope_key([claim("Subject B claim")])
    assert state["investigations"][a] == before[a]
    assert state["investigations"][b]["packet"]["dispute_round"] == 1
    assert len(state["investigations"][b]["history"]) == 2


@pytest.mark.parametrize("action", ["finish", "stop_with_gaps"])
@pytest.mark.parametrize("report_status", ["complete", "partial", "insufficient"])
async def test_final_actions_use_whole_report_status_and_preserve_reliable_parts(
    action, report_status
):
    context = await action_context()
    citations = [] if report_status == "insufficient" else ["https://source.test/exact"]
    terminal = "SUCCEEDED" if report_status == "complete" else "INSUFFICIENT_EVIDENCE"
    report = {
        "approved": True,
        "report_status": report_status,
        "terminal_status": terminal,
        "answer": "Server scoped findings and unresolved goals",
        "citations": citations,
        "unfinished_goals": [] if terminal == "SUCCEEDED" else [{"task_id": "task-b"}],
    }

    async def publish(state, task, call_id, decision):
        return copy.deepcopy(report)

    context.backend.publish = publish
    context.state["tasks"][0]["status"] = "done"
    context.state["tasks"][1]["dependencies"] = ["task-a"]
    context.state["decision"] = {
        "planner_contract": PLANNER_VERSION,
        "action": action,
        "reason": "Finalize the server report",
        "gaps": ["Remaining scoped issue"] if action == "stop_with_gaps" else [],
    }
    result = await context.graph.act(context.state)
    if action == "finish" or report_status == "complete":
        assert "final_status" not in result  # Legacy task labels cannot bypass original coverage.
        assert result["observations"][-1]["errorCode"] == (
            "ORIGINAL_REQUIREMENTS_INCOMPLETE" if action == "finish" else "PUBLICATION_NOT_APPROVED"
        )
        return
    assert result["final_status"] == terminal and result["citations"] == citations
    assert result["final_answer"] == report["answer"]
    assert result["tasks"][1]["status"] == "pending"  # Publication never declares a goal done.


async def test_subset_approved_without_report_completeness_cannot_finish_run():
    context = await action_context()

    async def publish(*_):
        return {"approved": True, "answer": "Subset", "citations": ["source"]}

    context.backend.publish = publish
    context.state["decision"] = {
        "planner_contract": PLANNER_VERSION,
        "action": "finish", "reason": "Request whole report",
    }
    result = await context.graph.act(context.state)
    assert "final_status" not in result
    assert result["observations"][-1]["errorCode"] == "ORIGINAL_REQUIREMENTS_INCOMPLETE"
