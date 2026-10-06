"""Controlled contract/replay/privacy checks; never a live answer success."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
from unittest.mock import AsyncMock

import pytest
from pydantic import ValidationError

from deepresearch_workflow.agent_budget import SAFE_FIELDS, AgentBudgetGateway
from deepresearch_workflow.agent_decision_instruction import (
    ACTION_INPUTS,
    LEGACY_POLICY_VERSION,
    POLICY_VERSION,
    obligation_instruction,
)
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_obligations import ObligationContinuation, ObligationDecision
from deepresearch_workflow.agent_protocol import (
    AgentDecision,
    AgentRunBudget,
    ModelRequest,
    ModelResult,
)
from deepresearch_workflow.agent_schema_diagnostics import diagnostic, safe_schema_diagnostic
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError

from .test_agent_first_planning import PlanningLedger, graph, state
from .test_agent_json_transport import json_settings
from .test_obligation_alignment import SequenceModel, initial_wire

ROOT = Path(__file__).resolve().parents[2]


def continuation_wire(frozen):
    return {
        "planner_contract": "agent-planning-obligations/3",
        "claims_contract": "agent-obligation-claims/1",
        "continuation_contract": "agent-frozen-requirements/2",
        "requirements_ref": frozen["original_requirements"]["manifest_sha256"],
        "action": "stop_with_gaps",
        "reason": "Controlled continuation",
        "gaps": ["No evidence"],
    }


async def context(policy=None):
    ledger, model = PlanningLedger(), SequenceModel(initial_wire())
    runtime = graph(model, ledger, legacy_fixture=False)
    initial = {
        **state(),
        "planner_contract": "agent-planning-obligations/3",
        "continuation_contract": "agent-frozen-requirements/2",
        "action_sequence": 0,
    }
    if policy is not None:
        initial["instruction_policy"] = policy
    selected = {**initial, **await runtime.decide(initial)}
    frozen = {**selected, **await runtime.act(selected)}
    model.following = continuation_wire(frozen)
    await runtime.decide(frozen)
    return runtime, ledger, model, initial, frozen


async def test_existing_v3_initial_continuation_bytes_and_settled_replay_unchanged():
    witness = json.loads(
        (Path(__file__).parent / "fixtures/planner-v3-instruction-witness.json").read_text()
    )
    runtime, ledger, model, initial, frozen = await context()
    adapter = OpenAIAgentModel(json_settings(), None)
    for request, row in zip(model.calls, witness["requests"], strict=True):
        prepared = adapter.prepare(request)
        assert hashlib.sha256(prepared.identity).hexdigest() == row["request_sha256"]
        assert hashlib.sha256(prepared.wire).hexdigest() == row["wire_sha256"]
        assert len(prepared.wire) == row["wire_bytes"]
        assert "instruction_policy" not in request.request_binding
    assert await runtime.initialize(initial) == {}
    await runtime.decide(copy.deepcopy(initial))
    await runtime.decide(copy.deepcopy(frozen))
    assert len(model.calls) == len(ledger.settlements) == 2


async def test_fresh_selector_persists_before_admission_and_selects_both_phase_contracts():
    runtime = graph(None, PlanningLedger(), legacy_fixture=False)
    runtime.ledger.scope = AsyncMock(return_value={})
    raw = state()
    raw.pop("decision_steps")
    update = await runtime.initialize(raw)
    assert update["instruction_policy"] == POLICY_VERSION and not runtime.ledger.rows
    _, _, model, initial, _ = await context(update["instruction_policy"])
    for request in model.calls:
        assert request.request_binding["instruction_policy"] == POLICY_VERSION
        assert "check_claims requires scoped text/kind/applicability" not in request.instruction
        assert (
            "Bind each covered criterion explicitly with criterion_bindings"
            not in request.instruction
        )
        assert "ONLY {text, requirement_id, criterion_id}" in request.instruction
        assert request.max_output_tokens == 1024
    assert (
        "For THIS phase choose exactly one action as a STRING from "
        "search/revise_plan/stop_with_gaps." in model.calls[0].instruction
    )
    assert (
        "For THIS phase choose exactly one action as a STRING from "
        "search/read_source/revise_plan/check_claims/finish/stop_with_gaps."
        in model.calls[1].instruction
    )
    with pytest.raises(WorkflowExecutionError) as error:
        await runtime.decide({**initial, "instruction_policy": "foreign/99"})
    assert error.value.error_code == "AGENT_INSTRUCTION_POLICY_INVALID"
    assert not runtime.ledger.rows


@pytest.mark.parametrize("action", list(ACTION_INPUTS))
def test_action_rules_match_actual_validator_and_finish_arguments_remain_optional(action):
    assert set(ACTION_INPUTS) == set(
        ObligationDecision.model_json_schema()["properties"]["action"]["enum"]
    )
    value = {"action": action, "reason": "Controlled action"}
    values = {
        "query": "Observed gap",
        "tool": "web_search",
        "source_id": "candidate",
        "tasks": [{"objective": "Check", "acceptance_criteria": ["Fact"]}],
        "claims": [
            {
                "text": "Claim",
                "kind": "factual",
                "applicability": initial_wire()["obligations"][0]["applicability"],
            }
        ],
        "gaps": ["Missing source"],
    }
    if ACTION_INPUTS[action]:
        with pytest.raises(ValidationError):
            AgentDecision.model_validate(value)
        value.update({k: values[k] for k in ACTION_INPUTS[action]})
    parsed = AgentDecision.model_validate(value)
    assert parsed.action == action
    if action == "finish":
        assert parsed.answer is None and parsed.citations == []
    for schema, phase in [
        (ObligationDecision.model_json_schema(), False),
        (ObligationContinuation.wire_schema(), True),
    ]:
        assert "answer/citations are optional" in obligation_instruction(schema, continuation=phase)


SCHEMA = {
    "type": "object",
    "properties": {"action": {"enum": ["search"]}, "reason": {"type": "string"}},
    "required": ["action"],
    "additionalProperties": False,
}
REQUEST = ModelRequest(
    name="AgentDecision",
    instruction="Fixed instruction",
    payload={},
    schema=SCHEMA,
    request_binding={"instruction_policy": POLICY_VERSION},
)


@pytest.mark.parametrize(
    "value,expected,path",
    [
        ({}, "MISSING", "action"),
        ({"action": "PRIVATE_BAD_ACTION"}, "ENUM", "action"),
        ({"action": 7}, "ENUM", "action"),
        ({"action": "search", "reason": 7}, "TYPE", "reason"),
        (
            {"action": "search", "PRIVATE_EXTRA_SECRET": "PRIVATE_VALUE"},
            "EXTRA_FIELD",
            "unknown_field",
        ),
    ],
)
async def test_actual_schema_failure_is_one_unknown_call_with_bound_safe_export(
    value, expected, path
):
    class Model:
        calls = 0

        async def invoke(self, request):
            self.calls += 1
            return ModelResult(value=copy.deepcopy(value), input_tokens=31, output_tokens=5)

    model, ledger = Model(), PlanningLedger()
    gateway = AgentBudgetGateway(
        run_id="offline",
        claim_token="claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger,
        model=model,
        guard=AsyncMock(),
    )
    with pytest.raises(ModelCallError):
        await gateway.model_call("model:diagnostic", "DECISION", REQUEST)
    row = ledger.rows["model:diagnostic"]
    failure = row["usage"]["model_failure"]
    d = failure["schema_diagnostic"]
    assert row["status"] == "UNKNOWN" and row["usage"]["input_tokens"] == 31 and model.calls == 1
    assert d["issues"] == [{"path": path, "code": expected}]
    assert (
        d["schema_sha256"]
        == hashlib.sha256(
            json.dumps(SCHEMA, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest()
    )
    assert d["instruction_sha256"] == hashlib.sha256(REQUEST.instruction.encode()).hexdigest()
    assert d["request_sha256"] == row["digest"]
    # Standalone audit whitelist matches production and strips unknown payload fields.
    import sys

    sys.path.insert(0, str(ROOT / "scripts"))
    spec = importlib.util.spec_from_file_location(
        "contract_export", ROOT / "scripts/accept-agent-round1.py"
    )
    export = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(export)
    assert (
        export.safe_model_failure(failure)["schema_diagnostic"]
        == safe_schema_diagnostic(d, SAFE_FIELDS)
        == d
    )
    assert "PRIVATE" not in json.dumps(export.safe_model_failure(failure))
    db = {
        "operations": [
            {
                "kind": "MODEL",
                "purpose": "DECISION",
                "attempt": 1,
                "status": "UNKNOWN",
                "actual_usage": row["usage"],
                "request_hash": row["digest"],
            }
        ]
    }
    assert export.model_receipts(db)[0]["schema_diagnostic_request_matches"] is True
    db["operations"][0]["request_hash"] = "0" * 64
    assert export.model_receipts(db)[0]["schema_diagnostic_request_matches"] is False
    scrubbed = export.scrub({"failure": failure}, [d["schema_sha256"]])
    assert "schema_diagnostic" not in export.safe_model_failure(scrubbed["failure"])

    bad = {**d, "message": "PRIVATE_EXCEPTION"}
    assert safe_schema_diagnostic(bad, SAFE_FIELDS) is None
    assert "schema_diagnostic" not in export.safe_model_failure(
        {**failure, "schema_diagnostic": bad}
    )


def test_unknown_validator_and_bounded_safe_paths_never_copy_input_or_messages():
    class Custom:
        validator = "PRIVATE_CUSTOM_TYPE"
        absolute_path = ("claims", "PRIVATE_KEY", "applicability", "conditions", "value", "status")
        message = "PRIVATE_EXCEPTION"
        instance = None

    d = diagnostic(Custom(), REQUEST, "0" * 64, SAFE_FIELDS)
    assert d["issues"] == [{"path": "claims.applicability.conditions.value", "code": "UNKNOWN"}]
    assert "PRIVATE" not in json.dumps(d)
    for mutation in [
        dict(d, issues=d["issues"] * 5),
        dict(d, issues=[{"path": "claims.applicability.conditions.value.status", "code": "TYPE"}]),
        dict(d, issues=[{"path": "PRIVATE_SECRET", "code": "TYPE"}]),
    ]:
        assert safe_schema_diagnostic(mutation, SAFE_FIELDS) is None

    class Many:
        def errors(self, **kwargs):
            assert kwargs == dict(include_input=False, include_context=False, include_url=False)
            return [{"loc": ["reason"], "type": "PRIVATE_TYPE", "input": "PRIVATE"}] * 6

    d = diagnostic(Many(), REQUEST, "0" * 64, SAFE_FIELDS, pydantic=True)
    assert len(d["issues"]) == 4 and all(i["code"] == "UNKNOWN" for i in d["issues"])
    assert "PRIVATE" not in json.dumps(d)


async def test_custom_domain_validator_is_unknown_not_a_proven_shape_fault():
    class Model:
        calls = 0

        async def invoke(self, request):
            self.calls += 1
            return ModelResult(
                value={"action": "search", "reason": "Controlled"}, input_tokens=31, output_tokens=5
            )

    model, ledger = Model(), PlanningLedger()
    gateway = AgentBudgetGateway(
        run_id="offline",
        claim_token="claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger,
        model=model,
        guard=AsyncMock(),
    )
    with pytest.raises(ModelCallError):
        await gateway.model_call(
            "model:domain", "DECISION", REQUEST, validate=AgentDecision.model_validate
        )
    failure = ledger.rows["model:domain"]["usage"]["model_failure"]
    assert failure["validation_stage"] == "domain_validation"
    assert failure["schema_diagnostic"]["issues"] == [{"path": "unknown_field", "code": "UNKNOWN"}]
    assert model.calls == 1 and ledger.rows["model:domain"]["status"] == "UNKNOWN"


async def test_instruction_policy_checkpoint_is_durable_before_any_model_reservation():
    from langgraph.checkpoint.memory import InMemorySaver

    runtime = graph(None, PlanningLedger(), legacy_fixture=False)
    runtime.ledger.scope = AsyncMock(return_value={})
    raw = state()
    raw.pop("decision_steps")
    saver = InMemorySaver()
    compiled = runtime.compile(checkpointer=saver, interrupt_after=["initialize"])
    config = {"configurable": {"thread_id": "policy-before-model"}}
    await compiled.ainvoke(raw, config)
    saved = await compiled.aget_state(config)
    assert saved.values["instruction_policy"] == POLICY_VERSION
    assert saved.values["planner_contract"] == "agent-planning-obligations/3"
    assert saved.next == ("compress",) and not runtime.ledger.rows


@pytest.mark.parametrize("policy", [None, LEGACY_POLICY_VERSION, POLICY_VERSION])
async def test_same_missing_source_field_preserves_legacy_receipt_and_gates_fresh_diagnostic(
    policy,
):
    from .test_agent_json_transport import REQUEST as source_request

    class Model:
        calls = 0

        async def invoke(self, request):
            self.calls += 1
            return ModelResult(value={"action": "read_source"}, input_tokens=41, output_tokens=7)

    binding = dict(source_request.request_binding)
    if policy is not None:
        binding["instruction_policy"] = policy
    request = source_request.model_copy(update={"request_binding": binding})
    model, ledger = Model(), PlanningLedger()
    gateway = AgentBudgetGateway(
        run_id="offline",
        claim_token="claim",
        budget=AgentRunBudget(runtime="agent"),
        ledger=ledger,
        model=model,
        guard=AsyncMock(),
    )
    with pytest.raises(ModelCallError):
        await gateway.model_call("model:paired-policy", "DECISION", request)
    row = ledger.rows["model:paired-policy"]
    saved = copy.deepcopy(row)
    failure = row["usage"]["model_failure"]
    if policy is None:
        assert "schema_diagnostic" not in failure
        assert "source_id" not in json.dumps(row["usage"])
    else:
        assert failure["schema_diagnostic"]["issues"] == [{"path": "source_id", "code": "MISSING"}]
        assert failure["schema_diagnostic"]["request_sha256"] == row["digest"]
    assert "read_source" not in json.dumps(row["usage"])
    with pytest.raises(WorkflowExecutionError):
        await gateway.model_call("model:paired-policy", "DECISION", request)
    assert ledger.rows["model:paired-policy"] == saved
    assert row["status"] == "UNKNOWN" and row["result"] == {}
    assert row["usage"]["input_tokens"] == 41 and row["usage"]["output_tokens"] == 7
    assert model.calls == len(ledger.settlements) == 1
