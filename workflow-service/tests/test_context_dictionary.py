"""Regression: real nonrepetitive history must not grow by copying mandatory scope."""

import copy
import json
from unittest.mock import AsyncMock

import httpx
import pytest
from pydantic import ValidationError

from deepresearch_workflow.agent_context import decision_context
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.context_dictionary import ENCODING, pack, unpack
from deepresearch_workflow.graph import WorkflowExecutionError
from deepresearch_workflow.project_summary import (
    ProjectSummaryCoordinator,
    apply_summary,
    baseline,
    byte_size,
    policy_for,
)

from .test_project_summary import Model, Store, gateway, state


def test_lossless_dictionary_preserves_nonkeyword_constraints_ids_and_literal_markers():
    value = {
        "question": "项目崩溃与网络断线后如何继续研究？",  # noqa: RUF001 - Chinese punctuation in Chinese text.
        "tasks": [
            {
                "task_id": "task-original",
                "criteria": [
                    {
                        "criterion_id": "criterion-" + str(i),
                        "scope": {
                            "subject": "研究恢复",
                            "conditions": [
                                "实验仅限离线环境，只允许使用脱敏样本。",  # noqa: RUF001 - Chinese punctuation in Chinese text.
                                "缺少实测结果时保持待验证。",
                            ],
                        },
                    }
                    for i in range(16)
                ],
            }
        ],
        "source": {"shared_ref": "user-authored-value"},
        "literal": {"shared_literal": {"shared_ref": "literal-again"}},
        "checks": [{"claim_id": "claim-original", "status": "contested"}] * 6,
    }
    original = copy.deepcopy(value)
    encoded = pack(value)
    assert encoded["context_encoding"] == ENCODING
    assert unpack(encoded) == original == value
    assert byte_size(encoded) < byte_size(original)
    with pytest.raises(ValueError):
        unpack(
            {"shared_context_values": {"v0": {"shared_ref": "v0"}}, "data": {"shared_ref": "v0"}}
        )


def test_read_markers_use_authoritative_current_evidence_and_do_not_mutate_sources():
    s = state()
    s["context_snapshot"]["project_summary_policy"]["projection_encoding"] = ENCODING
    s["candidates"] = [
        {"source_id": "original-read", "content": "原文线索"},
        {"source_id": "unread-lead", "content": "检索片段"},
    ]
    s["evidence"] = [
        {"source": {"source_id": "original-read"}, "snapshot": {"text": "实际读取的原文"}}
    ]
    before = copy.deepcopy(s)
    payload = decision_context(s, AgentRunBudget(runtime="agent"))
    assert [row["read_status"] for row in payload["candidates"]] == ["ORIGINAL_READ", "NOT_READ"]
    assert all(row["trusted_as_evidence"] is False for row in payload["candidates"])
    assert s == before
    s["context_snapshot"]["project_summary_policy"].pop("projection_encoding")
    assert all(
        "read_status" not in row
        for row in decision_context(s, AgentRunBudget(runtime="agent"))["candidates"]
    )


def test_expanded_decision_output_requires_new_policy_and_stays_bounded():
    fields = {
        "name": "AgentDecision",
        "instruction": "Return JSON",
        "schema": {"type": "object"},
        "payload": {"question": "original"},
        "max_output_tokens": 2048,
        "request_binding": {"context_encoding": ENCODING},
    }
    assert ModelRequest.model_validate(fields).max_output_tokens == 2048
    for changed in [
        {"request_binding": {}},
        {"max_output_tokens": 2049},
        {"name": "ProjectContextSummary"},
    ]:
        with pytest.raises(ValidationError):
            ModelRequest.model_validate({**fields, **changed})


def test_checklist_distinguishes_completed_subset_from_remaining_original_obligation():
    from .test_agent_requirements import QUESTION, checked, fixture

    manifest, checked_state, bindings = fixture()
    checked(checked_state, 0)
    s = {
        **state(),
        **checked_state,
        "question": QUESTION,
        "original_requirements": manifest,
        "requirement_bindings": bindings,
    }
    s["context_snapshot"]["project_summary_policy"]["projection_encoding"] = ENCODING
    before = copy.deepcopy(s)
    checklist = decision_context(s, AgentRunBudget(runtime="agent"))["research_checklist"]
    by_question = {r["question"]: r for r in checklist}
    assert by_question["Verify encryption defaults"]["current_status"] == "resolved"
    assert by_question["Verify export retention"]["current_status"] == "blocked"
    assert all(r["task_id"] == "task-1" for r in checklist)
    assert {r["criterion_id"] for r in checklist} == {r["criterion_id"] for r in bindings}
    assert by_question["Verify export retention"]["current_gaps"]
    assert not by_question["Verify encryption defaults"]["current_gaps"]
    assert s == before


async def test_new_projection_shares_full_context_without_summary_provider_or_scope_loss():
    s = state()
    s["context_snapshot"]["project_summary_policy"]["projection_encoding"] = ENCODING
    # Current metadata/checks duplicate saved history; no synthetic repeated prose.
    s["tasks"] = [
        {
            "goal": "研究恢复",
            "criteria": [
                {
                    "criterion_id": "criterion-" + str(i),
                    "conditions": ["依据项目知识库", "如缺少浏览器验收必须明确说明"],
                }
                for i in range(16)
            ],
        }
    ]
    m, store = Model(), Store()
    coord = ProjectSummaryCoordinator(store)
    raw_input = decision_context(s, AgentRunBudget(runtime="agent"))
    s.update(await coord.prepare(s, raw_input, gateway(m)))
    encoded = apply_summary(s, raw_input)
    marker = encoded.pop("project_summary")
    assert unpack(encoded) == baseline(s, raw_input, policy_for(s))
    assert marker["trusted_as_evidence"] is False
    assert s["project_summary_view"]["status"] == "READY"
    assert not m.calls  # Lossless encoding does not need model-authored prose.
    assert s["project_summary_view"]["measurement"]["after_bytes"] == byte_size(
        {**encoded, "project_summary": marker}
    )
    # Newly read evidence must update both the cache key and final-input measurement.
    old = copy.deepcopy(s["project_summary_view"])
    s["evidence"].append(
        {
            "source": {"source_id": "read-original"},
            "snapshot": {"text": "真实来源的新增原文。" * 80},
        }
    )
    current_input = decision_context(s, AgentRunBudget(runtime="agent"))
    s.update(await coord.prepare(s, current_input, gateway(m)))
    assert s["project_summary_view"]["source_sha256"] != old["source_sha256"]
    assert s["project_summary_view"]["measurement"]["after_bytes"] == byte_size(
        apply_summary(s, current_input)
    )
    assert len(store.rows) == 2
    repeat = await coord.prepare(s, current_input, gateway(m))
    assert repeat["project_summary_view"] == s["project_summary_view"]
    s["project_summary_view"]["summary"]["sections"]["goals"][0]["value"] = "伪造目标"
    with pytest.raises(WorkflowExecutionError, match="differs"):
        apply_summary(s, current_input)


async def test_small_new_input_does_not_insert_unsupported_not_needed_status():
    s = state()
    s["context_snapshot"] = {
        "project_summary_policy": {
            "enabled": True,
            "projection_encoding": ENCODING,
            "trigger_bytes": 16000,
            "budget_bytes": 24000,
        }
    }
    store, m = Store(), Model()
    payload = decision_context(s, AgentRunBudget(runtime="agent"))
    view = (await ProjectSummaryCoordinator(store).prepare(s, payload, gateway(m)))[
        "project_summary_view"
    ]
    assert view["status"] == "NOT_NEEDED" and view["summary"] is None
    assert not store.rows and not m.calls and "source_sha256" not in view


async def test_revocation_blocks_lossless_projection_before_storing_history():
    s = state()
    s["context_snapshot"]["project_summary_policy"]["projection_encoding"] = ENCODING
    m, store = Model(), Store()
    revoked = AsyncMock(
        side_effect=WorkflowExecutionError("revoked", error_code="RESEARCH_MEMORY_REVOKED")
    )
    with pytest.raises(WorkflowExecutionError, match="revoked"):
        await ProjectSummaryCoordinator(store).prepare(
            s, decision_context(s, AgentRunBudget(runtime="agent")), gateway(m, validator=revoked)
        )
    assert not store.rows and not m.calls


async def test_actual_runtime_wire_matches_measurement_and_exposes_batch_limit():
    from deepresearch_workflow.agent_obligations import CLAIMS_VERSION, PLANNER_VERSION

    from .test_agent_first_planning import PlanningLedger, graph
    from .test_agent_first_planning import state as planning_state
    from .test_agent_json_transport import envelope, json_settings
    from .test_question_segments import RecordingAdapter

    s = planning_state()
    s["planner_contract"] = PLANNER_VERSION
    s["context_snapshot"] = {
        "project_summary_policy": {
            "enabled": True,
            "projection_encoding": ENCODING,
            "trigger_bytes": 1000,
            "budget_bytes": 24000,
        },
        "recentConversation": ["仅限脱敏样本，验收不充分时说明缺口。" * 12] * 6,  # noqa: RUF001 - Chinese punctuation in Chinese text.
    }
    raw = decision_context(s, AgentRunBudget(runtime="agent"))
    s.update(await ProjectSummaryCoordinator(Store()).prepare(s, raw, gateway(Model())))
    value = {
        "planner_contract": PLANNER_VERSION,
        "claims_contract": CLAIMS_VERSION,
        "action": "stop_with_gaps",
        "reason": "Offline wire test",
        "gaps": ["尚未读取真实来源"],
        "obligations": [],
        "constraints": [],
    }
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=envelope(json.dumps(value)))
        )
    ) as client:
        adapter = RecordingAdapter(json_settings(), client)
        await graph(adapter, PlanningLedger(), legacy_fixture=False).decide(s)
    body = json.loads(adapter.prepared.wire)
    assert body["max_tokens"] == 2048
    actual = json.loads(body["messages"][1]["content"])
    assert actual == apply_summary(s, raw)
    assert byte_size(actual) == s["project_summary_view"]["measurement"]["after_bytes"]
    actual.pop("project_summary")
    assert unpack(actual) == baseline(s, raw, policy_for(s))
    assert "at most 2 claims in ONE action" in body["messages"][0]["content"]
