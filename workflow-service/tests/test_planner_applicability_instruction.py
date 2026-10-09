"""Synthetic shape probes, not a reconstruction of the failed live response."""

import copy
import hashlib
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker
from pydantic import ValidationError

from deepresearch_workflow.agent_budget import SAFE_FIELDS
from deepresearch_workflow.agent_decision_instruction import (
    CAPACITY_POLICY_VERSION,
    POLICY_VERSION,
    SHAPE_POLICY_VERSION,
    TIME_EXAMPLE,
    UNKNOWN_EXAMPLE,
    VERSION_EXAMPLE,
    obligation_instruction,
)
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_obligations import ObligationDecision
from deepresearch_workflow.agent_requirements import freeze_requirements
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.agent_schema_diagnostics import diagnostic

from .test_agent_json_transport import json_settings
from .test_decision_contract import context
from .test_obligation_alignment import initial_wire


async def test_prompt_examples_validate_actual_initial_wire_and_domain_scope():
    _, _, model, _, _ = await context(POLICY_VERSION)
    req = model.calls[0]
    validator = Draft202012Validator(req.result_schema, format_checker=FormatChecker())
    for version, time in (
        (VERSION_EXAMPLE, UNKNOWN_EXAMPLE),
        (UNKNOWN_EXAMPLE, UNKNOWN_EXAMPLE),
        (VERSION_EXAMPLE, TIME_EXAMPLE),
    ):
        wire = initial_wire()
        wire["obligations"][0]["applicability"].update(
            version=json.loads(version),
            valid_at=json.loads(time),
        )
        validator.validate(wire)
        ObligationDecision.model_validate(wire)
        assert version in req.instruction and time in req.instruction
    assert "Version is a separate text field" not in req.instruction
    assert "not evidence or a default date" in req.instruction
    assert "never erase a user time condition" in req.instruction
    assert "obligations" not in model.calls[1].result_schema["properties"]
    assert req.max_output_tokens == model.calls[1].max_output_tokens == 1024


@pytest.mark.parametrize(
    "synthetic",
    [
        {"status": "unknown", "reason": "No original yet"},
        {"status": "unknown", "value": "null", "reason": "No original yet"},
        None,
    ],
)
async def test_synthetic_invalid_time_shapes_reproduce_safe_composition_only(synthetic):
    _, _, model, _, _ = await context(POLICY_VERSION)
    req = model.calls[0]
    wire = initial_wire()
    wire["obligations"][0]["applicability"]["valid_at"] = synthetic
    error = next(Draft202012Validator(req.result_schema).iter_errors(wire))
    identity = OpenAIAgentModel(json_settings(), None).prepare(req).identity
    safe = diagnostic(error, req, hashlib.sha256(identity).hexdigest(), SAFE_FIELDS)
    assert safe["issues"] == [{"path": "obligations.applicability.valid_at", "code": "COMPOSITION"}]
    with pytest.raises(ValidationError):
        ObligationDecision.model_validate(wire)


@pytest.mark.parametrize(
    "field,shape",
    [
        ("version", "1.0"),
        ("valid_at", {"status": "known", "value": "2026-10-05"}),
        ("valid_at", {"status": "known", "value": "2026-02-30T00:00:00Z"}),
    ],
)
def test_domain_gate_still_rejects_plain_version_date_only_or_invalid_calendar(field, shape):
    wire = initial_wire()
    wire["obligations"][0]["applicability"][field] = shape
    with pytest.raises(ValidationError):
        ObligationDecision.model_validate(wire)


def test_unknown_source_time_preserves_user_date_version_and_mode_after_freezing():
    question = (
        "仅依据合成资料;核实 1.0 legacy 模式截至 2026-10-05 的请求数;"
        "核实 2.0 general 模式截至 2026-10-05 的请求数;请保留时间条件;请引用原文。"
    )
    wire = initial_wire(question)
    for index, version in enumerate(("1.0", "2.0")):
        scope = wire["obligations"][index]["applicability"]
        scope.update(
            version={"status": "known", "value": version},
            valid_at=json.loads(UNKNOWN_EXAMPLE),
            conditions=["截至 2026-10-05"],
        )
    planned = AutonomousResearchGraph.planning_decision(
        wire,
        {
            "question": question,
            "planner_contract": "agent-planning-obligations/3",
        },
    )
    manifest = freeze_requirements(
        "synthetic-time-scope",
        question,
        [row.model_dump(mode="json") for row in planned.requirements],
    )
    for index, requirement in enumerate(manifest["requirements"]):
        scope = requirement["applicability"]
        assert scope["version"] == {"status": "known", "value": ("1.0", "2.0")[index]}
        assert scope["valid_at"]["status"] == "unknown"
        assert scope["valid_at"]["value"] is None and scope["valid_at"]["reason"]
        assert "截至 2026-10-05" in scope["conditions"]
        assert ("legacy", "general")[index] in requirement["text"]
        assert "2026-10-05" in requirement["text"]


def test_prior2_check_prepared_bytes_and_continuation_instruction_are_frozen():
    from .test_mixed_check_capacity import request

    witness = json.loads(
        (Path(__file__).parent / "fixtures/planner-policy2-instruction-witness.json").read_text()
    )
    req = request(CAPACITY_POLICY_VERSION)
    prepared = OpenAIAgentModel(json_settings(), None).prepare(req)
    assert (
        hashlib.sha256(prepared.identity).hexdigest() == witness["check_request"]["request_sha256"]
    )
    assert hashlib.sha256(prepared.wire).hexdigest() == witness["check_request"]["wire_sha256"]
    assert req.max_output_tokens == 4096
    schema = ObligationDecision.model_json_schema()
    old = obligation_instruction(
        copy.deepcopy(schema), continuation=True, policy=CAPACITY_POLICY_VERSION
    )
    new = obligation_instruction(copy.deepcopy(schema), continuation=True, policy=POLICY_VERSION)
    previous = obligation_instruction(
        copy.deepcopy(schema), continuation=True, policy=SHAPE_POLICY_VERSION
    )
    assert old == previous
    assert new.startswith(old)
    assert "allowed_tools is the exhaustive list" not in old
    assert "allowed_tools is the exhaustive list" in new
