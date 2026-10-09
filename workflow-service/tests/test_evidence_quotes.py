"""Paragraph references preserve the native quote contract and durable replay."""

import copy
import hashlib
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from jsonschema import validate

from deepresearch_workflow import evidence_quotes as quotes
from deepresearch_workflow.agent_budget import AgentBudgetGateway, canonical
from deepresearch_workflow.agent_decision_instruction import POLICY_VERSION, SHAPE_POLICY_VERSION
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.evidence_check import EvidenceCheckError, verifier_instruction
from deepresearch_workflow.evidence_client import HttpEvidenceBackend
from deepresearch_workflow.graph import ModelCallError

from .test_agent_runtime import LedgerSubstitute
from .test_obligation_alignment import check_fixture


def fixture(followup=False):
    request, response = check_fixture()
    text = "开头 🧪\n\n" + "范围说明、代码和证据需要完整保留。" * 160 + "仅适用于 v1。\n结尾"
    request["evidence"][0]["snapshot"] = {
        "text": text,
        "sha256": hashlib.sha256(text.encode()).hexdigest(),
    }
    response["claims"][0]["relations"][0]["quote"] = {"start_paragraph": 2, "end_paragraph": 2}
    if followup:
        from .test_conversation_context import conversation

        request["original_context"].update(
            contract_version="agent-obligation-context/2", conversation_context=conversation()
        )
        response["claims"] = {
            row["claim_id"]: {
                **{k: v for k, v in row.items() if k not in {"claim_id", "relations"}},
                "relations": {
                    rel["evidence_id"]: {k: v for k, v in rel.items() if k != "evidence_id"}
                    for rel in row["relations"]
                },
            }
            for row in response["claims"]
        }
    digest = hashlib.sha256(canonical(request).encode()).hexdigest()
    return request, response, digest


def test_lossless_source_projection_and_complete_unicode_quote_reduce_output():
    request, response, digest = fixture()
    payload = quotes.model_payload(request, digest)
    assert "text" not in payload["evidence"][0]["snapshot"]
    assert (
        "\n".join(p["text"] for p in payload["evidence"][0]["snapshot"]["paragraphs"])
        == request["evidence"][0]["snapshot"]["text"]
    )
    validate(response, quotes.model_schema(request))
    expanded = quotes.expand(response, request, digest)
    q = expanded["claims"][0]["relations"][0]["quote"]
    assert q["start"] == len("开头 🧪\n\n")
    assert q["text"].endswith("仅适用于 v1。")
    assert len(canonical(expanded).encode()) > 10 * len(canonical(response).encode())
    assert request["evidence"][0]["snapshot"]["text"][q["start"] : q["end"]] == q["text"]


@pytest.mark.parametrize("start,end", [(True, 2), (-1, 2), (2, 1), (0, 99), (1, 1)])
def test_invalid_or_empty_paragraph_ranges_never_become_evidence(start, end):
    request, response, digest = fixture()
    response["claims"][0]["relations"][0]["quote"] = {
        "start_paragraph": start,
        "end_paragraph": end,
    }
    with pytest.raises(EvidenceCheckError):
        quotes.expand(response, request, digest)


def test_changed_source_omitted_counterevidence_and_missing_alignment_are_rejected():
    request, response, digest = fixture()
    changed = copy.deepcopy(request)
    changed["evidence"][0]["snapshot"]["text"] += " changed"
    with pytest.raises(EvidenceCheckError):
        quotes.expand(response, changed, digest)
    for mutate in [
        lambda r: r["claims"][0].update(relations=[]),
        lambda r: r.pop("planning_alignment"),
    ]:
        invalid = copy.deepcopy(response)
        mutate(invalid)
        with pytest.raises(EvidenceCheckError):
            quotes.expand(invalid, request, digest)


@pytest.mark.parametrize("followup", [False, True])
async def test_compact_response_is_attested_as_expanded_native_quote_and_replays_without_model(
    followup,
):
    request, response, digest = fixture(followup)
    ledger = LedgerSubstitute()
    model = SimpleNamespace(
        invoke=AsyncMock(
            return_value=ModelResult(value=response, input_tokens=50, output_tokens=200)
        )
    )
    gateway = AgentBudgetGateway(
        run_id="run",
        claim_token="claim",
        ledger=ledger,
        model=model,
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
        validate_memory=AsyncMock(),
    )
    req = ModelRequest(
        name="EvidenceCheck",
        instruction=quotes.instruction(verifier_instruction(request), request),
        payload=quotes.model_payload(request, digest),
        schema=quotes.model_schema(request),
        max_output_tokens=4096,
        request_binding={
            "instruction_policy": POLICY_VERSION,
            "evidence_quote_encoding": quotes.encoding_for(request),
            "check_id": request["check_id"],
            "request_sha256": digest,
        },
    )
    def expand(value):
        return quotes.expand(value, request, digest)
    first = await gateway.model_call("model:check", "CHECK", req, expand, canonicalize=expand)
    replay = await gateway.model_call("model:check", "CHECK", req, expand, canonicalize=expand)
    assert first == replay
    assert first.value == expand(response)
    assert (
        first.request_binding["response_sha256"]
        == hashlib.sha256(canonical(first.value).encode()).hexdigest()
    )
    assert json.loads(first.request_binding["encoded_response"]) == response
    assert "encoded_response" not in ledger.rows["model:check"]["usage"]["request_binding"]
    assert model.invoke.await_count == 1
    ledger.rows["model:check"]["result"]["request_binding"]["encoded_response"] = "{}"
    with pytest.raises(ModelCallError):
        await gateway.model_call("model:check", "CHECK", req, expand, canonicalize=expand)
    assert model.invoke.await_count == 1


@pytest.mark.parametrize("followup", [False, True])
async def test_backend_posts_expanded_receipt_bound_quote_to_native_completion(followup):
    request, response, digest = fixture(followup)
    ledger = LedgerSubstitute()

    async def invoke(req):
        assert req.request_binding["evidence_quote_encoding"] == quotes.encoding_for(request)
        return ModelResult(value=response, input_tokens=50, output_tokens=200)

    gw = AgentBudgetGateway(
        run_id="run",
        claim_token="claim",
        ledger=ledger,
        model=SimpleNamespace(invoke=invoke),
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
        validate_memory=AsyncMock(),
    )

    def transport(req):
        body = json.loads(req.content)
        if req.url.path.endswith("prepare"):
            return httpx.Response(
                200,
                json={
                    "check_id": request["check_id"],
                    "request": request,
                    "request_sha256": digest,
                    "requires_model": True,
                },
            )
        if req.url.path.endswith("complete"):
            assert body["response"] == quotes.expand(response, request, digest)
            assert ledger.rows[body["model_call_id"]]["result"]["value"] == body["response"]
            return httpx.Response(200, json={"records": [], "follow_up_actions": []})
        return httpx.Response(200, json={"packet_id": "packet", "gaps": []})

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://fixture.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "fixture"),
        )
        result = await backend.check(
            {
                "run_id": "run",
                "claim_token": "claim",
                "instruction_policy": POLICY_VERSION,
                "context_snapshot": {"agent_scope": {"project_id": "project"}},
                "evidence": request["evidence"],
            },
            {"task_id": "task"},
            "call",
            request["claims"],
            gw,
        )
    assert result["packet_id"] == "packet"


def test_keyed_followup_slots_preserve_judgment_and_reject_missing_or_extra_sources():
    request, response, digest = fixture(True)
    validate(response, quotes.model_schema(request))
    source = request["evidence"][0]["evidence_id"]
    claim = request["claims"][0]["claim_id"]
    response["claims"][claim]["relations"][source]["relation"] = "insufficient"
    assert (
        quotes.expand(response, request, digest)["claims"][0]["relations"][0]["relation"]
        == "insufficient"
    )
    for mutation in (
        lambda value: value["claims"][claim]["relations"].pop(source),
        lambda value: value["claims"][claim]["relations"].update(foreign={}),
        lambda value: value.update(claims=[]),
    ):
        changed = copy.deepcopy(response)
        mutation(changed)
        with pytest.raises(EvidenceCheckError):
            quotes.expand(changed, request, digest)


async def test_new_policy_tools_are_limited_but_old_policy_keeps_its_schema():
    from .test_decision_contract import context

    for policy in [SHAPE_POLICY_VERSION, POLICY_VERSION]:
        _, _, model, initial, _ = await context(policy)
        req = model.calls[0]
        tool = req.result_schema["properties"]["tool"]
        allowed = sorted(
            set(initial["requested_scopes"]) & {"web_search", "kb_search", "calculator"}
        )
        if policy == POLICY_VERSION:
            assert tool["anyOf"][0]["enum"] == allowed
            assert "Never request a disabled search tool" in req.instruction
        else:
            assert "Never request a disabled search tool" not in req.instruction


async def test_read_originals_remain_visible_and_duplicate_reads_do_not_spend_tools():
    from .test_decision_contract import context

    runtime, ledger, model, _, state = await context(POLICY_VERSION)
    source = "https://official.test/reference"
    state["candidates"] = [{"source_id": source, "content": "search preview"}]
    state["evidence"] = [
        {
            "evidence_id": "read-original",
            "record_type": "Evidence",
            "source": {"source_id": source, "kind": "web", "title": "Official reference"},
            "availability": "available",
            "freshness": "fresh",
            "validity": "unassessed",
            "snapshot": {"text": "Actual read definition and its conditions.", "sha256": "fixture"},
        }
    ]
    state["decision_steps"] += 1
    state["action_sequence"] = state["decision_steps"]
    state.update(await runtime.decide(state))
    navigation = model.calls[-1].payload["current_work_summary"]
    assert navigation["read_originals"][0]["evidence_id"] == "read-original"
    assert navigation["read_originals"][0]["preview"] == state["evidence"][0]["snapshot"]["text"]
    assert all(row["task_id"] for row in navigation["remaining"])
    state["decision"].update(action="read_source", source_id=source, tool=None, query=None)
    runtime.evidence.read = AsyncMock(side_effect=AssertionError("Already read"))
    before = len(ledger.rows)
    update = await runtime.act(state)
    assert update["observations"][-1]["already_read"] is True
    assert update["observations"][-1]["evidence_ids"] == ["read-original"]
    assert update["no_progress"] == state.get("no_progress", 0) + 1 and len(ledger.rows) == before
    runtime.evidence.read.assert_not_called()
    # A stale original must still be refreshable; duplicate suppression cannot
    # turn an old snapshot into permanent authority.
    state["evidence"][0]["freshness"] = "stale"
    state["action_sequence"] += 1
    refreshed = copy.deepcopy(state["evidence"][0])
    refreshed.update(evidence_id="refreshed-original", freshness="fresh")
    runtime.evidence.read = AsyncMock(return_value={"records": [refreshed]})
    refreshed_update = await runtime.act(state)
    runtime.evidence.read.assert_awaited_once()
    assert len(ledger.rows) == before + 1
    assert refreshed_update["evidence"][-1]["evidence_id"] == "refreshed-original"
