"""Offline adapter/planner and cross-consumer segment protocol regressions.

Constructed declarations never stand in for the missing live provider response.
"""

import copy
import hashlib
import json
from pathlib import Path

import httpx
import pytest
from langgraph.checkpoint.memory import InMemorySaver

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_diagnostics import safe_segment_diagnostic
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.agent_question_segments import (
    LEGACY_PLANNER_VERSION,
    PLANNER_VERSION,
    declaration_drafts,
    planner_binding,
    question_segments,
    segment_drafts,
)
from deepresearch_workflow.agent_requirements import (
    RequirementError,
    canonical,
    evaluate_coverage,
    freeze_requirements,
)
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError

from .test_agent_first_planning import QUESTION, PlanningLedger, declarations, graph, state
from .test_agent_identity import export
from .test_agent_json_transport import envelope, json_settings


def proposed(question=QUESTION):
    units = question_segments(question)["segments"]
    split = next((i for i, u in enumerate(units) if "它们的 HTTP" in u["text"]),
                 max(1, len(units) // 2))
    drafts = declarations()  # Names/scopes only; these are explicitly test declarations.
    for row, selected in zip(drafts, (units[:split], units[split:]), strict=True):
        row.pop("question_spans")
        row["segment_ids"] = [u["segment_id"] for u in selected]
    return drafts


def value(question=QUESTION):
    return {"action": "finish", "reason": "Offline declaration fixture",
            "planner_contract": PLANNER_VERSION, "requirements": proposed(question)}


def receipt(question, declaration):
    stored = copy.deepcopy(declaration)
    stored["requirements"] = segment_drafts(question, declaration["requirements"])
    return {"value": stored, "request_binding": {
        **planner_binding(question_segments(question)),
        "planner_declaration": canonical(declaration),
        "wire_response_sha256": hashlib.sha256(canonical(declaration).encode()).hexdigest(),
        "response_sha256": hashlib.sha256(canonical(stored).encode()).hexdigest(),
    }}


class RecordingAdapter(OpenAIAgentModel):
    def prepare(self, request):
        prepared = super().prepare(request)
        self.prepared = prepared
        self.request = request
        return prepared


@pytest.mark.parametrize("question", [
    QUESTION,
    "Compare example.com and example.org; HTTP guarantees? Cite https://iana.org/x?a=1.5.",
    "核查😀e\u0301注册；\r\n核查😀e\u0301转让？",  # noqa: RUF001 - exact Unicode fixtures
    "重复；重复；same; same?",  # noqa: RUF001 - exact Chinese delimiters
    "共同限定：version 2.0; encrypt? retain?",  # noqa: RUF001 - exact Chinese delimiters
])
async def test_actual_json_adapter_gateway_freezes_server_spans_replay_and_criteria(question):
    mapping = question_segments(question)
    declaration, calls, ledger = value(question), [], PlanningLedger()

    def transport(request):
        calls.append(request)
        return httpx.Response(200, json=envelope(json.dumps(declaration, ensure_ascii=False)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        initial = {**state(question), "planner_contract": PLANNER_VERSION}
        model = RecordingAdapter(json_settings(), client)
        runtime = graph(model, ledger)
        update = await runtime.decide(initial)
        saved = ledger.rows["model:agent:decision-1"]["result"]
        assert saved["value"]["planner_contract"] == PLANNER_VERSION
        assert "question_spans" in saved["value"]["requirements"][0]
        assert json.loads(saved["request_binding"]["planner_declaration"]) == declaration
        canonical_drafts = declaration_drafts(question, saved)
        assert canonical_drafts == segment_drafts(question, proposed(question))
        assert model.request.request_binding == {
            "context_contract": "agent-decision-context/2",
            "requirements_contract": "agent-original-requirements/1", **planner_binding(mapping),
        }
        assert "original_question" not in model.request.payload
        assert "".join(s["text"] for s in mapping["segments"]) == question
        st = {**initial, **update}
        st.update(await runtime.act(st))
        assert st["original_requirements"] == freeze_requirements(
            initial["run_id"], question, canonical_drafts
        )
        assert len(st["original_requirements"]["requirements"]) == 2
        assert len({b["criterion_id"] for b in st["requirement_bindings"]}) == 2
        assert not st.get("final_status") and not runtime.evidence.publish.called
        assert st["observations"][-1]["errorCode"] == "ORIGINAL_REQUIREMENTS_INCOMPLETE"
        runtime.claim_token = "recovered-lease"
        replay = await runtime.decide(json.loads(json.dumps(initial)))
        assert replay["decision"] == update["decision"]
    assert len(calls) == len(ledger.settlements) == 1
    assert (await ledger.summary())["inputTokens"] == 41


@pytest.mark.parametrize("mutation,code,stage", [
    ("omit", "REQUIREMENT_QUESTION_REGION_UNASSIGNED", "planning_requirements"),
    ("unknown", "REQUIREMENT_SEGMENT_UNKNOWN", "planning_requirements"),
    ("foreign", "REQUIREMENT_SEGMENT_UNKNOWN", "planning_requirements"),
    ("duplicate", "REQUIREMENT_SEGMENT_DUPLICATE", "planning_requirements"),
    ("number", None, "result_schema"),
    ("empty", None, "result_schema"),
    ("coordinates", None, "result_schema"),
    ("hash", None, "result_schema"),
    ("map", None, "result_schema"),
    ("null_conditions", None, "result_schema"),
    ("null_version", None, "result_schema"),
    ("wrong_version", None, "result_schema"),
    ("missing_requirements", "REQUIREMENTS_MISSING_OR_LIMIT", "planning_requirements"),
])
async def test_rejected_ids_never_settle_usable_or_retry_and_export_only_safe_ranges(
    mutation, code, stage
):
    declaration = value()
    row = declaration["requirements"][0]
    if mutation == "omit":
        declaration["requirements"] = declaration["requirements"][:1]
    elif mutation == "unknown":
        row["segment_ids"][0] = "PRIVATE_CANARY_UNKNOWN_ID"
    elif mutation == "foreign":
        row["segment_ids"][0] = question_segments("another question")["segments"][0]["segment_id"]
    elif mutation == "duplicate":
        row["segment_ids"].append(row["segment_ids"][0])
    elif mutation == "number":
        row["segment_ids"][0] = True
    elif mutation == "empty":
        row["segment_ids"] = []
    elif mutation == "coordinates":
        row["question_spans"] = [{"start": 0, "end": len(QUESTION)}]
    elif mutation == "hash":
        declaration["question_sha256"] = "a" * 64
    elif mutation == "map":
        declaration["question_segments"] = question_segments(QUESTION)
    elif mutation == "null_conditions":
        row["applicability"]["conditions"] = None
    elif mutation in {"null_version", "wrong_version"}:
        declaration["planner_contract"] = None if mutation == "null_version" else "unknown/99"
    else:
        declaration.pop("requirements")
    calls, ledger = [], PlanningLedger()

    def transport(request):
        calls.append(request)
        return httpx.Response(200, json=envelope(json.dumps(declaration, ensure_ascii=False)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        initial = {**state(), "planner_contract": PLANNER_VERSION}
        with pytest.raises(ModelCallError) as caught:
            await runtime.decide(initial)
        assert caught.value.validation_stage == stage and caught.value.domain_error_code == code
        saved = ledger.rows["model:agent:decision-1"]
        assert saved["status"] == "UNKNOWN" and saved["usage"]["input_tokens"] == 41
        metadata = saved["usage"]["model_failure"]
        assert export.safe_model_failure(metadata) == metadata
        if mutation == "omit":
            diagnostic = metadata["question_segments"]
            assert safe_segment_diagnostic(diagnostic) == diagnostic
            assert diagnostic["missing_count"] == len(diagnostic["missing_ranges"]) <= 16
            assert diagnostic["mapping_sha256"] == question_segments(QUESTION)["mapping_sha256"]
        assert QUESTION not in json.dumps(metadata)
        assert "PRIVATE_CANARY" not in json.dumps(metadata)
        with pytest.raises(WorkflowExecutionError, match="Cannot replay"):
            await runtime.decide(initial)
    assert len(calls) == len(ledger.settlements) == 1


@pytest.mark.parametrize("mutation", ["question", "maphash", "mapversion", "version", "null",
                                     "binding_null", "missing_binding", "response"])
def test_settled_provenance_cannot_downgrade_or_supply_own_mapping(mutation):
    saved = receipt(QUESTION, value())
    binding = saved["request_binding"]
    if mutation == "question":
        binding["question_sha256"] = "a" * 64
    elif mutation == "maphash":
        binding["question_mapping_sha256"] = "a" * 64
    elif mutation == "mapversion":
        binding["question_mapping_version"] = "unknown/99"
    elif mutation == "version":
        binding["planner_contract"] = "unknown/99"
    elif mutation == "null":
        saved["value"]["planner_contract"] = None
        binding.pop("planner_contract")
    elif mutation == "binding_null":
        binding["planner_contract"] = None
    elif mutation == "missing_binding":
        binding.clear()
    else:
        saved["value"]["reason"] = "tampered declaration"
    with pytest.raises(RequirementError):
        declaration_drafts(QUESTION, saved)


def test_shared_qualifiers_ordering_and_generic_coverage_semantic_limit():
    proposed_rows = proposed()
    proposed_rows[1]["segment_ids"].append(proposed_rows[0]["segment_ids"][0])
    frozen = freeze_requirements("shared", QUESTION, segment_drafts(QUESTION, proposed_rows))
    changed_order = copy.deepcopy(proposed_rows[::-1])
    for row in changed_order:
        row["segment_ids"].reverse()
    assert freeze_requirements(
        "shared", QUESTION, segment_drafts(QUESTION, changed_order)
    ) == frozen
    generic = copy.deepcopy(proposed_rows[:1])
    generic[0]["segment_ids"] = [s["segment_id"] for s in question_segments(QUESTION)["segments"]]
    generic_manifest = freeze_requirements("generic", QUESTION, segment_drafts(QUESTION, generic))
    assert len(generic_manifest["requirements"]) == 1
    # All IDs selected while the fixture text discusses registration alone: no semantic proof.
    assert "HTTP" not in generic_manifest["requirements"][0]["text"]
    assert not evaluate_coverage(generic_manifest, [], {}, [])["complete"]


@pytest.mark.parametrize("question", [
    "x;" * 2000,
    "中" * 4000,
    "😀" * 2000,
    "\x00" * 3999 + "x",
    "\\\"" * 2000,
    ";\r\n" + "x;" * 1998,
])
async def test_max_supported_utf16_inputs_are_complete_bounded_and_fit_original_budget(question):
    mapping = question_segments(question)
    assert question_segments(question) == mapping
    assert len(mapping["segments"]) <= 16
    assert "".join(s["text"] for s in mapping["segments"]) == question
    assert mapping["segments"][0]["start"] == 0
    assert mapping["segments"][-1]["end"] == len(question)
    body = {"action": "stop_with_gaps", "reason": "Size fixture", "gaps": ["Offline only"],
            "planner_contract": PLANNER_VERSION}
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=envelope(json.dumps(body)))
    )) as client:
        model = RecordingAdapter(json_settings(), client)
        await graph(model, PlanningLedger()).decide(
            {**state(question), "planner_contract": PLANNER_VERSION}
        )
        assert len(model.prepared.wire) + 1024 <= 64000


@pytest.mark.parametrize("question", ["x" * 4001, "😀" * 2001, "\ud800", "\r\n"])
def test_unsupported_inputs_reject_without_normalizing_or_dropping_text(question):
    with pytest.raises(RequirementError):
        question_segments(question)


async def test_initialized_v1_pending_and_settled_request_bytes_remain_baseline_exact():
    witness = json.loads((Path(__file__).parent / "fixtures/planner-v1-witness.json").read_text())
    initial = {**state(), **witness["state"]}
    declaration = {"action": "finish", "reason": "Legacy fixture", "requirements": declarations()}
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=envelope(json.dumps(declaration)))
    )) as client:
        ledger, model = PlanningLedger(), RecordingAdapter(json_settings(), client)
        runtime = graph(model, ledger)
        assert await runtime.initialize(initial) == {}  # No retroactive version selection.
        update = await runtime.decide(initial)
        assert hashlib.sha256(model.prepared.identity).hexdigest() == witness["request_sha256"]
        assert hashlib.sha256(model.prepared.wire).hexdigest() == witness["wire_sha256"]
        assert len(model.prepared.wire) == witness["wire_bytes"]
        replay = await runtime.decide(json.loads(json.dumps(initial)))
        assert replay["decision"] == update["decision"]
        legacy_receipt = ledger.rows["model:agent:decision-1"]["result"]
        assert declaration_drafts(QUESTION, legacy_receipt) == declarations()
        # Never consume the same v1 reservation through v2, even before a manifest exists.
        with pytest.raises(AssertionError):
            await runtime.decide({**initial, "planner_contract": PLANNER_VERSION})
        assert len(ledger.settlements) == 1


async def test_existing_v1_checkpoint_restart_keeps_legacy_declaration_and_manifest():
    declaration = {"action": "finish", "reason": "Legacy checkpoint fixture",
                   "requirements": declarations()}
    saver, ledger, calls = InMemorySaver(), PlanningLedger(), []
    config = {"configurable": {"thread_id": "legacy-restart-fixture"}}

    def transport(request):
        calls.append(request)
        return httpx.Response(200, json=envelope(json.dumps(declaration)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        model = OpenAIAgentModel(json_settings(), client)
        before = graph(model, ledger).compile(checkpointer=saver, interrupt_after=["initialize"])
        await before.ainvoke(state(), config)
        assert "planner_contract" not in before.get_state(config).values
        pending = graph(model, ledger).compile(checkpointer=saver, interrupt_after=["decide"])
        await pending.ainvoke(None, config)
        settled = pending.get_state(config).values
        assert "planner_contract" not in settled
        assert settled["decision"]["requirements"] == declarations()
        restarted = graph(model, ledger).compile(checkpointer=saver, interrupt_after=["act"])
        await restarted.ainvoke(None, config)
        final = restarted.get_state(config).values
        assert final["original_requirements"] == freeze_requirements(
            state()["run_id"], QUESTION, declarations()
        )
        assert "planner_contract" not in final
    assert len(calls) == len(ledger.settlements) == 1


async def test_v2_settled_replay_recomputes_canonical_not_merely_hash_check():
    ledger = PlanningLedger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=envelope(json.dumps(value())))
    )) as client:
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        initial = {**state(), "planner_contract": PLANNER_VERSION}
        await runtime.decide(initial)
        saved = ledger.rows["model:agent:decision-1"]["result"]
        saved["value"]["requirements"][0]["text"] = "Tampered canonical declaration"
        saved["request_binding"]["response_sha256"] = hashlib.sha256(
            canonical(saved["value"]).encode()
        ).hexdigest()
        with pytest.raises(ModelCallError) as caught:
            await runtime.decide(initial)
        assert caught.value.domain_error_code == "REQUIREMENT_SEGMENT_BINDING_INVALID"
        assert caught.value.validation_stage == "planning_requirements"
    assert len(ledger.settlements) == 1


async def test_fresh_initialize_pins_version_before_admission_and_unknown_version_fails():
    runtime = graph(None, PlanningLedger(), legacy_fixture=False)
    runtime.ledger.scope = lambda *_: _scope()
    initial = state()
    initial.pop("decision_steps")
    update = await runtime.initialize(initial)
    assert update["planner_contract"] == "agent-planning-obligations/3" and not runtime.ledger.rows
    assert update["continuation_contract"] == "agent-frozen-requirements/2"
    with pytest.raises(WorkflowExecutionError, match="Unknown planning"):
        await runtime.decide({**state(), "planner_contract": "unknown/99"})
    assert not runtime.ledger.rows
    assert runtime.planner_version(state()) == LEGACY_PLANNER_VERSION


async def _scope():
    return {}


async def test_schema_valid_oversized_settlement_retains_known_usage_once_without_raw_body():
    declaration = value()
    ids = [s["segment_id"] for s in question_segments(QUESTION)["segments"]]
    base = copy.deepcopy(declaration["requirements"][0])
    base["segment_ids"] = ids
    base["applicability"]["subject"] = "\\" * 1000
    base["applicability"]["conditions"] = ["\\" * 400] * 18
    declaration["requirements"] = [
        {**copy.deepcopy(base), "text": str(i) + "\\" * 399} for i in range(3)
    ]
    assert len(canonical(declaration).encode()) < 65536
    ledger, calls = PlanningLedger(), []

    def transport(request):
        calls.append(request)
        return httpx.Response(200, json=envelope(json.dumps(declaration)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        initial = {**state(), "planner_contract": PLANNER_VERSION}
        with pytest.raises(ModelCallError) as caught:
            await runtime.decide(initial)
        assert caught.value.domain_error_code == "REQUIREMENT_DECLARATION_LIMIT"
        assert caught.value.validation_stage == "planning_requirements"
        saved = ledger.rows["model:agent:decision-1"]
        assert saved["status"] == "UNKNOWN" and saved["result"] == {}
        assert saved["usage"]["input_tokens"] == 41 and saved["usage"]["output_tokens"] == 7
        assert "planner_declaration" not in json.dumps(saved["usage"])
        with pytest.raises(WorkflowExecutionError, match="Cannot replay"):
            await runtime.decide(initial)
    assert len(calls) == len(ledger.settlements) == 1


async def test_normalizer_internal_error_keeps_usage_and_unusable_receipt(monkeypatch):
    from unittest.mock import AsyncMock

    ledger = PlanningLedger()
    model = type("Model", (), {"invoke": AsyncMock(return_value=ModelResult(
        value={}, input_tokens=9, output_tokens=4
    ))})()
    gateway = AgentBudgetGateway(run_id="offline", claim_token="offline", ledger=ledger,
                                 model=model, budget=AgentRunBudget(runtime="agent"),
                                 guard=AsyncMock())

    def broken(_):
        raise RuntimeError("PRIVATE_CANARY")

    with pytest.raises(ModelCallError) as caught:
        await gateway.model_call("model:normalizer", "DECISION", ModelRequest(
            name="AgentDecision", instruction="Fixture", payload={}, schema={"type": "object"}
        ), canonicalize=broken)
    assert caught.value.failure_kind == "INTERNAL"
    assert caught.value.validation_stage == "planning_requirements"
    saved = ledger.rows["model:normalizer"]
    assert saved["status"] == "UNKNOWN"
    assert (saved["usage"]["input_tokens"], saved["usage"]["output_tokens"]) == (9, 4)
    assert "PRIVATE_CANARY" not in json.dumps(saved["usage"])
    assert len(ledger.settlements) == model.invoke.await_count == 1


async def test_custom_adapter_cannot_settle_a_declaration_that_replay_cannot_read():
    from unittest.mock import AsyncMock

    question = "x;" * 16
    declaration = value(question)
    base = copy.deepcopy(declaration["requirements"][0])
    base["segment_ids"] = [s["segment_id"] for s in question_segments(question)["segments"]]
    base["applicability"]["subject"] = "s" * 450
    declaration["requirements"] = [
        {**copy.deepcopy(base), "text": str(i) + "t" * 398} for i in range(32)
    ]
    encoded = canonical(declaration)
    assert len(encoded.encode()) > 65536
    canonical_value = {"planner_contract": PLANNER_VERSION,
                       "requirements": segment_drafts(question, declaration["requirements"])}
    assert len(canonical({"value": canonical_value,
                          "planner_declaration": encoded}).encode()) < 120000
    model = type("Model", (), {"invoke": AsyncMock(return_value=ModelResult(
        value=declaration, input_tokens=9, output_tokens=4
    ))})()
    ledger = PlanningLedger()
    with pytest.raises(ModelCallError) as caught:
        await graph(model, ledger).decide({**state(question), "planner_contract": PLANNER_VERSION})
    assert caught.value.domain_error_code == "REQUIREMENT_DECLARATION_LIMIT"
    assert ledger.rows["model:agent:decision-1"]["status"] == "UNKNOWN"
    assert ledger.rows["model:agent:decision-1"]["usage"]["input_tokens"] == 9
    assert len(ledger.settlements) == model.invoke.await_count == 1


async def test_optional_scope_conditions_default_in_settled_canonical_and_native_reconstruction():
    declaration = value()
    for row in declaration["requirements"]:
        row["applicability"].pop("conditions")
    ledger = PlanningLedger()
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=envelope(json.dumps(declaration)))
    )) as client:
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        await runtime.decide({**state(), "planner_contract": PLANNER_VERSION})
    saved = ledger.rows["model:agent:decision-1"]["result"]
    raw = json.loads(saved["request_binding"]["planner_declaration"])
    assert all("conditions" not in row["applicability"] for row in raw["requirements"])
    assert all(row["applicability"]["conditions"] == [] for row in saved["value"]["requirements"])
    assert declaration_drafts(QUESTION, saved) == saved["value"]["requirements"]


@pytest.mark.parametrize("mutate", ["extra", "huge", "bool", "count", "text", "unordered"])
def test_diagnostic_exporter_drops_malformed_content_with_production_parity(mutate):
    d = {"mapping_version": "agent-question-segments/1", "mapping_sha256": "a" * 64,
         "missing_count": 1, "missing_ranges": [{"start": 0, "end": 1}]}
    if mutate == "extra":
        d["PRIVATE_CANARY"] = "secret"
    elif mutate == "huge":
        d["missing_ranges"][0]["end"] = 4001
    elif mutate == "bool":
        d["missing_count"] = True
    elif mutate == "count":
        d["missing_count"] = 17
    elif mutate == "text":
        d["mapping_sha256"] = "PRIVATE_CANARY"
    else:
        d["missing_count"] = 2
        d["missing_ranges"] = [{"start": 5, "end": 8}, {"start": 1, "end": 3}]
    assert safe_segment_diagnostic(d) is None and export.safe_segment_diagnostic(d) is None
