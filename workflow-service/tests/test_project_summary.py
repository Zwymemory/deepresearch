"""M2 semantic boundaries, real adapter bytes, recovery and failure handling."""

import copy
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_context import decision_context
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.graph import WorkflowExecutionError
from deepresearch_workflow.project_summary import (
    DEFAULT_POLICY,
    ProjectSummaryCoordinator,
    apply_summary,
    assemble,
    baseline,
    byte_size,
    compressed,
    material,
    policy_for,
)

from .test_agent_json_transport import envelope, json_settings
from .test_agent_runtime import LedgerSubstitute
from .test_progress_memory import selected_context


class Store:
    def __init__(self):
        self.rows = {}

    async def get(self, run, key):
        return copy.deepcopy(self.rows.get((run, key)))

    async def save(self, state, claim, key, view):
        self.rows[(state["run_id"], key)] = copy.deepcopy(view)


class Model:
    def __init__(self):
        self.calls = []
        self.fail = False

    async def invoke(self, request):
        self.calls.append(request)
        if self.fail:
            return ModelResult(value={"selected_segment_ids": ["invented-fact"]})
        rows = request.payload["source_segments"]
        return ModelResult(
            value={"selected_segment_ids": [rows[0]["segment_id"]]},
            input_tokens=100,
            output_tokens=30,
        )


def state():
    return {
        "planner_contract": "agent-planning-obligations/3",
        "run_id": "wf-summary",
        "question": "Continue latency measurement; do not invent numbers",
        "context_snapshot": {
            **selected_context(),
            "project_summary_policy": {
                **DEFAULT_POLICY,
                "trigger_bytes": 4000,
                "budget_bytes": 16000,
            },
            "recentConversation": [
                "Old source observation " + ("repeated observation; " * 65) for _ in range(6)
            ]
            + ["LATEST correction: hardware C", "LATEST: no measured numbers"],
        },
        "plan_version": 1,
        "decision_steps": 0,
        "tasks": [],
        "observations": [],
        "candidates": [],
        "evidence": [],
        "packet": {},
        "requested_scopes": ["kb_search"],
    }


def gateway(model, ledger=None, validator=None):
    return AgentBudgetGateway(
        run_id="wf-summary",
        claim_token="claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger or LedgerSubstitute(),
        model=model,
        guard=AsyncMock(),
        validate_memory=validator or AsyncMock(),
    )


async def prepare(s, model=None, store=None):
    m = model or Model()
    store = store or Store()
    payload = decision_context(s, AgentRunBudget(runtime="agent"))
    update = await ProjectSummaryCoordinator(store).prepare(s, payload, gateway(m))
    return {**s, **update}, m, store


async def test_m3_annotation_and_completion_survive_summary_without_keyword_selection():
    s = state()
    snapshot = s["context_snapshot"]["prior_progress"]["records"][0]["snapshot"]
    snapshot["user_correction"] = "以后改用机器 C 和认证方案 Z。"
    snapshot["historical_completed_work"] = [
        {
            "goal": "Earlier version check",
            "historical": True,
            "source_run_id": "previous",
            "completion_verified": True,
        }
    ]
    updated, _model, _ = await prepare(s)
    compact = apply_summary(updated, decision_context(updated, AgentRunBudget(runtime="agent")))
    summary = compact["project_summary"]
    assert any(
        row["value"] == snapshot["user_correction"] for row in summary["sections"]["constraints"]
    )
    assert any(
        row["value"] == snapshot["historical_completed_work"]
        for row in summary["sections"]["findings"]
    )
    assert compact["prior_progress"]["trusted_as_evidence"] is False
    assert "historical" in str(summary)


async def test_sourced_summary_preserves_scope_dispute_latest_originals_and_reduces_bytes():
    original = state()
    after, model, _ = await prepare(original)
    view = after["project_summary_view"]
    assert view["status"] == "READY"
    assert view["measurement"]["after_bytes"] < view["measurement"]["before_bytes"]
    assert view["measurement"]["after_bytes"] <= 16000
    payload = apply_summary(after, decision_context(after, AgentRunBudget(runtime="agent")))
    assert payload["context_version"] == "agent-decision-context/4"
    assert (
        payload["prior_context"]["recentConversation"][-2:]
        == original["context_snapshot"]["recentConversation"][-2:]
    )
    assert payload["prior_progress"] == original["context_snapshot"]["prior_progress"]
    assert payload["project_summary"]["trusted_as_evidence"] is False
    sections = view["summary"]["sections"]
    assert sections["constraints"][0]["value"] == ["Same data and hardware"]
    assert sections["disputes"][0]["value"][0]["decision_status"] == "contested"
    assert sections["unfinished"][0]["value"][0]["gaps"] == ["No measured numbers"]
    refs = {row["source_ref"]: row for row in view["sources"]}
    for quote in view["summary"]["excerpts"]:
        raw = refs[quote["source_ref"]]["value"]
        assert quote["text"] == raw[quote["start_codepoint"] : quote["end_codepoint"]]
    assert original == state() and len(model.calls) == 1


async def test_restart_and_repeat_same_range_use_durable_result_without_new_summary_call():
    first, model, store = await prepare(state())
    recovered, _, _ = await prepare(copy.deepcopy(first), model, store)
    assert recovered["project_summary_view"] == first["project_summary_view"]
    assert len(model.calls) == 1 and len(store.rows) == 1


async def test_failure_keeps_previous_valid_summary_and_uncovered_original_range():
    first, model, store = await prepare(state())
    changed = copy.deepcopy(first)
    changed["context_snapshot"]["recentConversation"].insert(6, "Another original source " * 65)
    model.fail = True
    failed, _, _ = await prepare(changed, model, store)
    view = failed["project_summary_view"]
    assert (
        view["status"] == "FAILED" and view["summary"] == first["project_summary_view"]["summary"]
    )
    assert view["uncovered_records"]
    payload = apply_summary(failed, decision_context(failed, AgentRunBudget(runtime="agent")))
    assert "Another original source" in str(payload["prior_context"])
    count = len(model.calls)
    await prepare(failed, model, store)
    assert len(model.calls) == count


async def test_correction_invalidates_predecessor_and_does_not_reuse_changed_excerpt():
    first, model, store = await prepare(state())
    changed = copy.deepcopy(first)
    changed["context_snapshot"]["recentConversation"][0] = (
        "CORRECTED: the old latency result was invalid. " * 30
    )
    model.fail = True
    failed, _, _ = await prepare(changed, model, store)
    assert failed["project_summary_view"]["summary"] is None
    payload = apply_summary(failed, decision_context(failed, AgentRunBudget(runtime="agent")))
    assert "project_summary" not in payload
    assert "CORRECTED" in payload["prior_context"]["recentConversation"][0]


async def test_revocation_blocks_summary_before_provider_and_remains_a_memory_error():
    s = state()
    model = Model()
    ledger = LedgerSubstitute()
    check = AsyncMock(
        side_effect=WorkflowExecutionError("revoked", error_code="RESEARCH_MEMORY_REVOKED")
    )
    with pytest.raises(WorkflowExecutionError, match="revoked"):
        await ProjectSummaryCoordinator(Store()).prepare(
            s, decision_context(s, AgentRunBudget(runtime="agent")), gateway(model, ledger, check)
        )
    assert not model.calls and not ledger.rows


async def test_raw_constraints_checks_and_failure_gaps_are_not_compressed_away():
    s = state()
    s["observations"] = [
        {"action": "search", "errorCode": "TOOL_UNAVAILABLE", "error": "Original failed attempt"}
    ] * 4
    s["packet"] = {"status": "CONTESTED", "gaps": ["Original counterevidence remains unresolved"]}
    ready, _, _ = await prepare(s)
    raw = decision_context(ready, AgentRunBudget(runtime="agent"))
    compact = apply_summary(ready, raw)
    for key in [
        "tasks",
        "packet",
        "investigations",
        "canonical_objects",
        "action_gaps",
        "original_requirements",
    ]:
        assert compact[key] == raw[key]


async def test_actual_adapter_encodes_summary_selection_and_next_planning_input():
    import json

    calls = []

    def transport(wire):
        body = json.loads(wire.content)
        calls.append(body)
        payload = json.loads(body["messages"][1]["content"])
        value = (
            {"selected_segment_ids": [payload["source_segments"][0]["segment_id"]]}
            if "source_segments" in payload
            else {"accepted": True}
        )
        return httpx.Response(200, json=envelope(json.dumps(value), inp=120, out=40))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        m = OpenAIAgentModel(json_settings(), client)
        ledger = LedgerSubstitute()
        g = gateway(m, ledger)
        s = state()
        payload = decision_context(s, AgentRunBudget(runtime="agent"))
        update = await ProjectSummaryCoordinator(Store()).prepare(s, payload, g)
        s.update(update)
        compact = apply_summary(s, payload)
        request = ModelRequest(
            name="AgentDecision",
            instruction="fixture next planning input",
            schema={"type": "object"},
            payload=compact,
            request_binding={
                "project_summary_sha256": compact["project_summary"]["summary_sha256"]
            },
        )
        await g.model_call("decision-1", "DECISION", request)
    actual = json.loads(calls[1]["messages"][1]["content"])
    assert actual["project_summary"] == s["project_summary_view"]["summary"]
    assert actual["prior_context"]["recentConversation"][-1] == "LATEST: no measured numbers"
    assert [row["kind"] for row in ledger.rows.values()] == ["MODEL", "MODEL"]


def test_old_frozen_runs_and_disabled_policy_do_not_change_context():
    s = state()
    s["context_snapshot"].pop("project_summary_policy")
    raw = decision_context(s, AgentRunBudget(runtime="agent"))
    assert apply_summary(s, raw) == raw and not policy_for(s)["enabled"]


async def test_forged_checkpoint_summary_cannot_add_a_fact_even_with_recomputed_hash():
    from deepresearch_workflow.project_summary import digest

    ready, _, _ = await prepare(state())
    summary = ready["project_summary_view"]["summary"]
    summary["excerpts"][0]["text"] = "Latency was measured at 1 ms"
    summary["summary_sha256"] = digest({k: v for k, v in summary.items() if k != "summary_sha256"})
    with pytest.raises(WorkflowExecutionError, match="differs"):
        apply_summary(ready, decision_context(ready, AgentRunBudget(runtime="agent")))


async def test_hard_constraints_in_older_source_sentences_survive_nonselection():
    s = state()
    s["context_snapshot"]["recentConversation"][1] = (
        "Unimportant repetition. Must use hardware C; no measured numbers. "
        + "Repeated filler. " * 70
    )
    ready, _, _ = await prepare(s)
    summary = ready["project_summary_view"]["summary"]
    assert any(
        "Must use hardware C" in str(row["value"]) for row in summary["sections"]["constraints"]
    )


@pytest.mark.parametrize(
    "constraint",
    [
        "实验仅限离线环境，只允许使用脱敏样本。",  # noqa: RUF001 - exact review reproduction
        "Only anonymized samples in an offline environment.",
    ],
)
async def test_nonkeyword_constraints_remain_in_actual_input_without_model_selection(constraint):
    s = state()
    s["context_snapshot"]["recentConversation"][1] = constraint + "普通背景描述。" * 180
    ready, _, _ = await prepare(s)
    assert ready["project_summary_view"]["status"] == "READY"
    payload = apply_summary(ready, decision_context(ready, AgentRunBudget(runtime="agent")))
    assert constraint in str(payload)
    summary = payload["project_summary"]
    refs = {
        source["source_ref"]: source["value"] for source in ready["project_summary_view"]["sources"]
    }
    for original in summary["original_records"]:
        restored = "".join(
            summary["source_dictionary"][part["text_ref"]] * part["repeat"]
            for part in original["parts"]
        )
        assert restored == refs[original["source_ref"]]


async def test_all_admitted_selections_fit_actual_context_byte_budget():
    s = state()
    model = Model()
    ready, model, _ = await prepare(s, model)
    request = model.calls[0]
    assert (
        request.result_schema["properties"]["selected_segment_ids"]["maxItems"]
        == request.payload["selection_limit"]
    )
    raw = baseline(s, decision_context(s, AgentRunBudget(runtime="agent")), policy_for(s))
    sources = material(s, raw, policy_for(s))
    worst = sorted(request.payload["source_segments"], key=byte_size, reverse=True)
    selected = [row["segment_id"] for row in worst[: request.payload["selection_limit"]]]
    largest = assemble(s, sources, request.payload["source_segments"], selected, policy_for(s))
    final = compressed(raw, largest, sources)
    assert "original_question" not in final and "question_segments" in final
    assert byte_size(final) <= 16000
    assert ready["project_summary_view"]["measurement"]["after_bytes"] <= 16000
