"""Controlled offline pipeline/replay and actual graph terminal recovery regressions."""
import copy
import importlib.util
import json
from pathlib import Path

import httpx
import pytest

from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import CONTINUATION_VERSION
from deepresearch_workflow.agent_question_segments import PLANNER_VERSION, question_segments
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError
from deepresearch_workflow.ports import RepositoryEventSink

from .test_agent_first_planning import PlanningLedger, declarations, export, graph, state
from .test_agent_json_transport import envelope, json_settings
from .test_agent_runtime import ObservationDrivenModel, continuation_fields, setup


def new_state():
    return {**state(), "planner_contract": PLANNER_VERSION,
            "continuation_contract": CONTINUATION_VERSION, "action_sequence": 0}


def first_value(initial):
    units = [row["segment_id"] for row in question_segments(initial["question"])["segments"]]
    drafts = declarations(initial["question"])
    for row in drafts:
        row.pop("question_spans")
        row["segment_ids"] = units
    return {"planner_contract": PLANNER_VERSION, "action": "finish",
            "reason": "Controlled unverified first declaration", "requirements": drafts}


@pytest.mark.parametrize("mutation", [None, "empty_declaration", "same_declaration",
                                     "modified_declaration", "wrong_ref", "missing_ref",
                                     "wrong_contract", "unknown_field"])
async def test_new_continuation_real_adapter_gateway_and_settled_or_unknown_replay(mutation):
    initial = new_state()
    first = first_value(initial)
    ledger, calls = PlanningLedger(), []
    continuation = {}

    def transport(wire):
        calls.append(wire)
        value = first if len(calls) == 1 else continuation
        return httpx.Response(200, json=envelope(json.dumps(value)))

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        runtime = graph(OpenAIAgentModel(json_settings(), client), ledger)
        update = await runtime.decide(initial)
        frozen = {**initial, **update}
        frozen.update(await runtime.act(frozen))
        assert len(frozen["original_requirements"]["requirements"]) == 2
        continuation.update(planner_contract=PLANNER_VERSION,
                            continuation_contract=CONTINUATION_VERSION,
                            requirements_ref=frozen["original_requirements"]["manifest_sha256"],
                            action="search", query="Changed targeted query", tool="web_search",
                            task_id=frozen["tasks"][0]["task_id"],
                            reason="Controlled continuation with frozen server references")
        if mutation in {"empty_declaration", "same_declaration", "modified_declaration"}:
            continuation["requirements"] = ([] if mutation == "empty_declaration" else
                                             copy.deepcopy(first["requirements"]))
            if mutation == "modified_declaration":
                continuation["requirements"][0]["text"] = "CANARY edited declaration"
        elif mutation == "wrong_ref":
            continuation["requirements_ref"] = "0" * 64
        elif mutation == "missing_ref":
            continuation.pop("requirements_ref")
        elif mutation == "wrong_contract":
            continuation["continuation_contract"] = "foreign"
        elif mutation == "unknown_field":
            continuation["private_CANARY"] = "CANARY"
        manifest = copy.deepcopy(frozen["original_requirements"])
        if mutation:
            with pytest.raises(ModelCallError) as failure:
                await runtime.decide(frozen)
            assert not failure.value.retryable
            assert ledger.rows["model:agent:decision-2"]["status"] == "UNKNOWN"
            assert "CANARY" not in str(failure.value)
            with pytest.raises(WorkflowExecutionError):
                await runtime.decide(frozen)
        else:
            result = await runtime.decide(frozen)
            replay = await runtime.decide(copy.deepcopy(frozen))
            assert {k: result[k] for k in ("decision", "decision_steps", "action_sequence")} == {
                k: replay[k] for k in ("decision", "decision_steps", "action_sequence")
            }
            stored = ledger.rows["model:agent:decision-2"]["result"]
            assert stored["value"]["requirements"] == []
            assert (stored["request_binding"]["requirements_manifest_sha256"]
                    == manifest["manifest_sha256"])
            assert "requirements" not in json.loads(
                stored["request_binding"]["planner_declaration"])
            trusted = export.audit_identifiers({"tasks": frozen["tasks"]})
            # Task references inside the hashed declaration must survive safe export too.
            assert export.scrub(stored, [], identifiers=trusted) == stored
        assert frozen["original_requirements"] == manifest
        assert len(calls) == len(ledger.settlements) == 2
        assert sum(row["usage"]["input_tokens"] for row in ledger.rows.values()) == 82
        schema = json.loads(json.loads(calls[-1].content)["messages"][0]["content"].split(
            "Result JSON Schema:\n")[1])
        assert "requirements" not in schema["properties"]
        assert {"requirements_ref", "continuation_contract"} <= set(schema["required"])
        assert json.loads(calls[-1].content)["max_tokens"] == 1024


class IdempotentEvents:
    """Event key retries may repeat equal payloads; changed payloads must never be lost."""
    def __init__(self):
        self.rows = {}

    async def emit(self, event, claim):
        value = (event.event_type, event.safe_payload)
        if event.event_key in self.rows:
            assert self.rows[event.event_key] == value, "event key collided with different payload"
            return
        self.rows[event.event_key] = copy.deepcopy(value)
        await self.sink.emit(event, claim)


class RecoveringSourceModel(ObservationDrivenModel):
    def __init__(self, *, recover):
        super().__init__()
        self.recover = recover

    async def invoke(self, request):
        observations = request.payload.get("observations", [])
        rejected = [o for o in observations if o.get("errorCode") == "SOURCE_NOT_IN_CURRENT_SEARCH"]
        if (request.name == "AgentDecision" and request.payload.get("candidates")
                and not request.payload.get("evidence") and (not rejected or not self.recover)):
            self.requests.append(request)
            from deepresearch_workflow.agent_protocol import ModelResult
            return ModelResult(value={"planner_contract": PLANNER_VERSION,
                                      **continuation_fields(request), "action": "read_source",
                                      "source_id": "https://www.iana.org/help/example-domains",
                                      "reason": "Controlled absent candidate selection"},
                               input_tokens=120, output_tokens=100)
        return await super().invoke(request)


@pytest.mark.parametrize("recover,terminal", [(False, "INSUFFICIENT_EVIDENCE"),
                                              (True, "SUCCEEDED")])
async def test_actual_graph_invalid_source_stops_or_recovers_and_mechanical_terminal(
    recover, terminal,
):
    oracle = RecoveringSourceModel(recover=recover)
    events = IdempotentEvents()
    context = setup("version-difference", model=oracle, events=events)
    events.sink = RepositoryEventSink(context[2])
    observed = json.loads((Path(__file__).parent / "fixtures/action_recovery_candidates.json")
                          .read_text())["candidates"]
    assert len(observed) == 5
    # Reuse the public candidate shape; all originals/checks are labelled synthetic substitutes.
    base = next(r for r in context[6].records
                if "Version 2.0" in r["snapshot"]["text"])
    records = []
    for index, candidate in enumerate(observed):
        record = copy.deepcopy(base)
        record["source"]["source_id"] = candidate["source_id"]
        record["evidence_id"] = f"evidence-synthetic-{index}"
        records.append(record)
    context[5].records = context[6].records = records
    await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == terminal
    saved = context[8].get_tuple({"configurable": {"thread_id": "wf-test"}})
    saved = saved.checkpoint["channel_values"]
    assert saved["final_status"] == terminal
    assert saved["action_sequence"] > saved["decision_steps"]
    assert saved["action_progress_step"] == saved["action_sequence"]
    rejects = [o for o in saved["observations"]
               if o.get("errorCode") == "SOURCE_NOT_IN_CURRENT_SEARCH"]
    assert len(rejects) == (1 if recover else 2)
    assert all(o["rejected_source_id"] == "https://www.iana.org/help/example-domains"
               for o in rejects)
    assert all(0 < len(o["authorized_source_ids"]) <= 8 and o["correction"] for o in rejects)
    assert len(context[5].calls) == 1
    assert len(context[6].publications) == 1
    if recover:
        assert saved["evidence"] and context[6].checks
        assert any(event.safe_payload.get("mechanicalPublication") for event in context[2].events)
    else:
        assert saved["decision_steps"] == 3  # Search + two rejected reads; stop costs no model.
        assert not saved["evidence"] and not context[6].checks
    # A checkpoint already containing the completed action cannot count or emit it twice.
    runtime = graph(None, PlanningLedger())
    assert await runtime.act(saved) == {}
    assert not runtime.events.emit.called


async def test_revise_plan_churn_and_bare_packet_churn_are_not_progress():
    initial = state()
    runtime = graph(None, PlanningLedger())
    initial["decision"] = {"action": "revise_plan", "reason": "Controlled plan-only change",
                           "tasks": [{"objective": "Extra plan",
                                      "acceptance_criteria": ["Verify"]}]}
    update = await runtime.act(initial)
    assert update["no_progress"] == 1
    assert not runtime.events.emit.call_args.args[0].safe_payload["newEvidence"]
    for packet in [{"status": "supported", "records": []}, {"gaps": ["Different text"]}]:
        assert (runtime.progress_marker(initial)
                == runtime.progress_marker({**initial, "packet": packet}))


async def test_rejected_native_publication_stops_without_an_unbounded_mechanical_loop():
    oracle = RecoveringSourceModel(recover=False)
    context = setup("version-difference", model=oracle)
    publications = []

    async def reject(state, task, call_id, decision):
        publications.append(call_id)
        return {"approved": False, "gaps": ["Synthetic native gate rejection"]}

    context[6].publish = reject
    await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == "FAILED"
    assert context[7].requests[-1].errorCode == "AGENT_PUBLICATION_REJECTED"
    assert len(publications) == 1  # The stable ledger key replays its rejection once.
    decisions = [r for r in oracle.requests if r.name == "AgentDecision"]
    assert len(decisions) == 3  # Search + two invalid reads, no unbudgeted closure model.


@pytest.mark.parametrize("secret", ["", "short", "task-req-" + "a" * 40])
def test_scrub_trusted_identity_scope_and_explicit_secret_priority(secret):
    identity = "task-req-" + "a" * 40
    trusted = export.audit_identifiers({"tasks": [{"task_id": identity}]})
    value = {"task_id": identity, "canonical": json.dumps({"task_id": identity}),
             "fake": "task-req-" + "b" * 40,
             "embedded_key": "x-sk-" + "c" * 32,
             "jwt": "eyJ" + "d" * 22 + "." + "e" * 24 + "." + "f" * 24,
             "api_key": "PRIVATE", "path": "/".join(("", "Users", "example", "private")),
             "explicit": secret}
    scrubbed = export.scrub(value, [secret], identifiers=trusted)
    if secret == identity:
        assert scrubbed["task_id"] == "[redacted]"
    else:
        assert scrubbed["task_id"] == identity
        assert json.loads(scrubbed["canonical"])["task_id"] == identity
    assert "[redacted]" in scrubbed["fake"] and "[redacted]" in scrubbed["embedded_key"]
    assert scrubbed["jwt"] == "[redacted]" and "api_key" not in scrubbed
    assert scrubbed["path"] == "[local-path]"
    if secret:
        assert scrubbed["explicit"] == "[redacted]"
    assert export.scrub(identity, []) != identity  # untrusted lookalike has no exemption


def test_native_audit_roundtrip_preserves_authoritative_identity_and_hashes():
    script = Path(__file__).resolve().parents[2] / "scripts/tests/test_agent_v22_readiness.py"
    spec = importlib.util.spec_from_file_location("action_native_fixture", script)
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    identity = "task-req-" + "a" * 40
    database = fixture.native_fixture(task_id=identity)
    trusted = export.audit_identifiers(database)
    safe = export.scrub({"database": database}, [], identifiers=trusted)
    assert safe["database"] == database
    audit = export.finalize_audit(safe)
    proof = export.validate_saved_audit(json.loads(json.dumps(audit)))
    assert proof["status"] == "verified_mapping"
    assert proof["mapping_complete"] and proof["eligible_for_complete_review"]
    # An explicit secret collision still wins; native validation must reject the damage.
    collided = export.finalize_audit(export.scrub(
        {"database": database}, [identity], identifiers=trusted))
    rejected = export.validate_saved_audit(collided)
    assert rejected["status"] == "invalid"
    assert "NATIVE_CRITERION_IDENTITY_MISMATCH" in json.dumps(rejected)
