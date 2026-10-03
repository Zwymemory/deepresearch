"""Sanitized adversarial projection/actual-wire tests. No provider requests."""

from __future__ import annotations

import copy
import json

import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway, canonical
from deepresearch_workflow.agent_context import CONTEXT_VERSION, decision_context
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.graph import RunBudgetExceededError
from deepresearch_workflow.settings import Settings


def context(text="short original"):
    unknown = {"status": "unknown", "value": None, "reason": "No effective time declared"}
    draft = {
        "text": "Service is guaranteed",
        "kind": "factual",
        "applicability": {
            "subject": "service",
            "version": unknown,
            "valid_at": unknown,
            "conditions": ["enterprise production"],
        },
    }
    claim = {
        "record_type": "Claim",
        "claim_id": "claim-one",
        **draft,
        "decision_status": "contested",
        "evidence_links": [
            {
                "evidence_id": "evidence-one",
                "quote": "no guarantee",
                "start": 2400,
                "end": 2412,
                "snapshot_sha256": "a" * 64,
            }
        ],
    }
    decision = {
        "record_type": "DecisionRecord",
        "decision_id": "decision-one",
        "claim_id": "claim-one",
        "decision_status": "contested",
        "adopted_evidence_ids": ["evidence-one"],
        "unresolved_evidence_ids": ["evidence-counter"],
        "gaps": ["Version conflict remains unresolved"],
    }
    packet = {
        "check_id": "check-one",
        "records": [claim, decision],
        "claim_specs": [draft],
        "required_evidence_ids": ["evidence-counter"],
        "gaps": decision["gaps"],
    }
    return {
        "question": "Does production have a guarantee?",
        "plan_version": 1,
        "tasks": [
            {
                "task_id": "task-one",
                "criteria": [
                    {
                        "criterion_id": "criterion-one",
                        "expected_claim": draft,
                        "gaps": decision["gaps"],
                    }
                ],
            }
        ],
        "evidence": [
            {
                "record_type": "Evidence",
                "evidence_id": "evidence-one",
                "snapshot": {"text": text, "sha256": "a" * 64},
                "applicability": draft["applicability"],
            }
        ],
        "candidates": [],
        "packet": packet,
        "investigations": {
            "investigation-one": {
                "claim_specs": [draft],
                "task_ids": ["task-one"],
                "packet": packet,
                "history": [packet],
            }
        },
        "observations": [{"action": "check_claims", **packet}],
        "requested_scopes": ["web_search"],
        "decision_steps": 3,
        "agent_usage": {"inputTokens": 14000},
    }


def prepared(payload):
    model = OpenAIAgentModel(
        Settings(
            _env_file=None,
            runner_enabled=False,
            model_name="deepseek-flash",
            openai_base_url="https://api.deepseek.com",
            agent_result_transport="deepseek_json_object",
        ),
        None,
    )
    request = ModelRequest(
        name="ProjectionTest",
        instruction="fixture",
        payload=payload,
        schema={"type": "object"},
        request_binding={"context_contract": CONTEXT_VERSION},
    )
    return model, request, model.prepare(request)


def test_canonical_records_and_checks_once_preserve_conflicts_and_scope():
    state = context()
    original = copy.deepcopy(state)
    payload = decision_context(state, AgentRunBudget(runtime="agent"))
    assert state == original
    objects = payload["canonical_objects"]
    assert len(objects["records"]) == 2
    assert len(objects["claim_specs"]) == 1
    assert len(objects["checks"]) == 1
    encoded = canonical(payload)
    assert encoded.count('"quote":"no guarantee"') == 1
    assert "evidence-counter" in encoded and "Version conflict remains unresolved" in encoded
    assert "enterprise production" in encoded and "No effective time declared" in encoded
    assert payload["observations"][0]["check_ref"] == payload["packet"]["check_ref"]


def test_all_history_and_large_unicode_source_omissions_are_explicit():
    state = context("文🙂" * 100000)
    for index in range(12):
        row = copy.deepcopy(state["packet"])
        row["check_id"] = f"check-old-{index}"
        row["records"][1]["gaps"] = [f"Historical unresolved conflict {index}"]
        state["investigations"]["investigation-one"]["history"].append(row)
    payload = decision_context(state, AgentRunBudget(runtime="agent"))
    snapshot = payload["evidence"][0]["snapshot"]
    assert snapshot["projection"]["omitted_codepoints"] == 198400
    assert snapshot["projection"]["shown_codepoints"] == [0, 1600]
    assert snapshot["context_preview_only"]
    assert len(payload["investigations"][0]["history"]) == 13
    assert '"start":2400' in canonical(payload)
    assert all(f"Historical unresolved conflict {i}" in canonical(payload) for i in range(12))
    _, _, wire = prepared(payload)
    assert len(wire.wire) < 64000


def test_deterministic_checkpoint_projection_and_contract_change_identity():
    state = context()
    payload = decision_context(state, AgentRunBudget(runtime="agent"))
    _, request, before = prepared(payload)
    # Only stable state is used: caller's latest DB summary cannot influence projection.
    assert canonical(payload) == canonical(
        decision_context(copy.deepcopy(state), AgentRunBudget(runtime="agent"))
    )
    model, _, _ = prepared(payload)
    changed = request.model_copy(update={"request_binding": {"context_contract": "legacy/1"}})
    assert model.prepare(changed).identity != before.identity
    assert model.prepare(changed).wire == before.wire


@pytest.mark.asyncio
async def test_required_context_over_limit_is_rejected_before_provider_with_actual_wire():
    payload = decision_context(context(), AgentRunBudget(runtime="agent"))
    payload["required_obligations"] = ["不能丢弃的义务🙂" * 10000]
    model, request, wire = prepared(payload)
    reserves = []

    class Ledger:
        async def reserve(self, *args):
            reserves.append(args)
            assert args[6] == len(wire.wire) + 1024
            raise RunBudgetExceededError("required context exceeds cap")

    async def guard():
        pass

    async def forbidden(*args):
        raise AssertionError("provider invocation forbidden")

    model.invoke_prepared = forbidden
    gateway = AgentBudgetGateway(
        run_id="run",
        claim_token="claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=Ledger(),
        model=model,
        guard=guard,
    )
    with pytest.raises(RunBudgetExceededError):
        await gateway.model_call("decision", "DECISION", request)
    assert len(reserves) == 1 and reserves[0][6] > 64000
    assert json.loads(wire.wire)["model"] == "deepseek-flash"
