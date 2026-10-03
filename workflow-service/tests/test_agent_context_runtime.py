"""Production graph/wire offline integration of multiple original obligations."""

from __future__ import annotations

import copy

import httpx
import pytest

from deepresearch_workflow.agent_protocol import ModelResult
from deepresearch_workflow.agent_requirements import evaluate_coverage
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.ports import RepositoryEventSink

from .test_agent_json_transport import fixture_model, oracle_transport
from .test_agent_runtime import ObservationDrivenModel, setup


class DualObligationModel(ObservationDrivenModel):
    def __init__(self, *, omit_second=False, change_scope=False):
        super().__init__()
        self.omit_second, self.change_scope = omit_second, change_scope

    async def invoke(self, request):
        payload = request.payload
        if request.name == "SyntheticCheck":
            return await super().invoke(request)
        if not payload.get("original_requirements"):
            result = await super().invoke(request)
            unknown = {"status": "unknown", "value": None, "reason": "No effective date"}
            result.value["requirements"] = [
                {
                    "text": text,
                    "question_spans": [{"start": 0, "end": len(payload["original_question"])}],
                    "kind": "factual",
                    "applicability": {
                        "subject": subject,
                        "version": {"status": "known", "value": "2.0"},
                        "valid_at": unknown,
                        "conditions": ["requested version only"],
                    },
                }
                for text, subject in (
                    ("Verify document version", "document version"),
                    ("Verify request rate", "request rate"),
                )
            ]
            return result
        unread = [
            c
            for c in payload["candidates"]
            if c["source_id"] not in {e["source"]["source_id"] for e in payload["evidence"]}
        ]
        if unread or not payload["evidence"]:
            return await super().invoke(request)
        self.requests.append(request)
        requirements = payload["original_requirements"]["requirements"]
        binding = {b["requirement_id"]: b["criterion_id"] for b in payload["requirement_bindings"]}
        criteria = {c["criterion_id"]: c for t in payload["tasks"] for c in t["criteria"]}
        missing = [
            r
            for r in requirements
            if criteria[binding[r["requirement_id"]]].get("status") != "resolved"
        ]
        if self.omit_second:
            missing = [r for r in missing if r["text"] == "Verify document version"]
        if not missing:
            return ModelResult(
                value={"action": "finish", "reason": "Test proposed closure"},
                input_tokens=120,
                output_tokens=100,
            )
        claims, links = [], []
        for row in missing:
            scope = copy.deepcopy(row["applicability"])
            if self.change_scope:
                scope["version"] = {"status": "known", "value": "1.0"}
            claims.append(
                {
                    "text": "Document version is 2.0."
                    if row["text"] == "Verify document version"
                    else "Version 2.0 allows 100 requests.",
                    "kind": row["kind"],
                    "applicability": scope,
                }
            )
            links.append(
                {"criterion_id": binding[row["requirement_id"]], "claim_index": len(claims) - 1}
            )
        return ModelResult(
            value={
                "action": "check_claims",
                "claims": claims,
                "criterion_bindings": links,
                "reason": "Check all independent scoped obligations",
            },
            input_tokens=120,
            output_tokens=100,
        )


@pytest.mark.parametrize(
    "omit_second,status", [(False, "SUCCEEDED"), (True, "INSUFFICIENT_EVIDENCE")]
)
async def test_actual_json_graph_multiple_requirements_complete_or_honest_partial(
    omit_second, status
):
    oracle, calls = DualObligationModel(omit_second=omit_second), []
    async with httpx.AsyncClient(transport=oracle_transport(oracle, calls)) as client:
        context = setup("version-difference", model=fixture_model(client))
        await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == status
    assert len(context[6].publications) == 1
    assert len(context[3].tasks[0]["criteria"]) == 2
    assert any(r.name == "SyntheticCheck" for r in oracle.requests)
    decisions = [r for r in oracle.requests if r.name == "AgentDecision"]
    assert all("context_version" in r.payload for r in decisions)
    if not omit_second:
        # Last provider dispatch is the check. Closure/publication adds no model call.
        assert oracle.requests[-1].name == "SyntheticCheck"
        assert len(calls) == len(oracle.requests) == 5
    else:
        assert len(context[6].checks[0]) == 1
        assert context[7].requests[-1].citations


async def test_wrong_original_scope_rejects_before_backend_verification():
    oracle = DualObligationModel(change_scope=True)
    context = setup("version-difference", model=oracle)
    await context[0].run_claimed(context[1])
    assert not context[6].checks
    assert context[7].requests[-1].status == "INSUFFICIENT_EVIDENCE"
    assert any(
        event.safe_payload.get("errorCode") == "REQUIREMENT_CLAIM_SCOPE_CHANGED"
        for event in context[2].events
    )


async def test_missing_fresh_declaration_cannot_use_generic_task_or_search():
    class Missing(ObservationDrivenModel):
        async def invoke(self, request):
            result = await super().invoke(request)
            result.value.pop("requirements", None)
            return result

    context = setup("version-difference", model=Missing())
    await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == "FAILED"
    assert not context[5].calls and not context[6].checks and not context[6].publications
    assert not context[3].tasks


async def test_capacity_gap_prevents_mechanical_closure_even_if_helper_obligations_resolve():
    context = setup("version-difference")
    await context[0].run_claimed(context[1])
    saved = await context[8].aget_tuple({"configurable": {"thread_id": context[1].run_id}})
    state = copy.deepcopy(saved.checkpoint["channel_values"])
    for entry in state["investigations"].values():
        entry["packet"]["gaps"] = ["Durable capacity rejection still needs server review"]
    report = evaluate_coverage(
        state["original_requirements"],
        state["tasks"],
        state["investigations"],
        state["requirement_bindings"],
    )
    assert report["complete"]  # Helper explicitly leaves capacity authority to the server.
    model = ObservationDrivenModel()
    graph = AutonomousResearchGraph(
        model=model,
        tools=context[5],
        repository=context[2],
        ledger=context[3],
        evidence=context[6],
        events=RepositoryEventSink(context[2]),
        budget=context[1].budget,
        claim_token=context[1].claim_token,
    )
    await graph.decide(state)
    assert len(model.requests) == 1  # Mechanical finish was not selected.
