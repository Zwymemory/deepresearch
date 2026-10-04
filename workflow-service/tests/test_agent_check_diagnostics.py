"""Known verifier response rejections remain domain failures through the actual gateway."""

import json
from unittest.mock import AsyncMock

import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_diagnostics import CHECK_ERROR_CODES
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.evidence_check import (
    EvidenceCheckError,
    canonical,
    parse_verifier_response,
    response_schema,
    sha,
)
from deepresearch_workflow.graph import ModelCallError, WorkflowExecutionError

from .test_agent_first_planning import CANARY, PlanningLedger
from .test_agent_identity import export
from .test_evidence_check import packet


@pytest.mark.parametrize(
    "text,quote,code",
    [
        ("Limit: 10; production is unsupported.", CANARY, "CHECK_QUOTE_BINDING_INVALID"),
        ("Limit: 10; production is unsupported.", "Limit: 10", "CHECK_QUOTE_CONTEXT_INCOMPLETE"),
        ("Limit: 10\n\nLimit: 10", "Limit: 10", "CHECK_QUOTE_BINDING_INVALID"),
    ],
)
async def test_schema_valid_bad_quote_keeps_domain_classification_usage_and_replay(
    text, quote, code
):
    request, value = packet(text)
    value["claims"][0]["relations"][0]["quote"] = quote
    ledger = PlanningLedger()
    model = type(
        "Model",
        (),
        {
            "invoke": AsyncMock(
                return_value=ModelResult(
                    value=value,
                    input_tokens=23,
                    output_tokens=8,
                )
            )
        },
    )()
    gw = AgentBudgetGateway(
        run_id="offline-check",
        claim_token="offline",
        model=model,
        ledger=ledger,
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
    )
    contract = ModelRequest(
        name="EvidenceCheck",
        instruction="offline",
        payload=request,
        schema=response_schema(request),
    )

    def validate(response):
        parse_verifier_response(canonical(response), request, sha(canonical(request)))

    with pytest.raises(ModelCallError) as captured:
        await gw.model_call("model:check", "CHECK", contract, validate)
    failure = captured.value
    assert failure.error_code == "MODEL_SCHEMA_INVALID"
    assert failure.error_class == "validator_rejected" and failure.failure_kind == "SCHEMA"
    assert failure.domain_error_code == code and failure.validation_stage == "domain_validation"
    receipt = ledger.rows["model:check"]
    assert receipt["status"] == "UNKNOWN" and ledger.settlements[0][1]
    usage = receipt["usage"]
    assert (usage["input_tokens"], usage["output_tokens"]) == (23, 8)
    assert export.safe_model_failure(usage["model_failure"]) == usage["model_failure"]
    assert CANARY not in json.dumps(usage) + str(failure)
    with pytest.raises(WorkflowExecutionError, match="Cannot replay"):
        await gw.model_call("model:check", "CHECK", contract, validate)
    assert model.invoke.await_count == len(ledger.settlements) == 1


@pytest.mark.parametrize(
    "code", [*sorted(CHECK_ERROR_CODES), CANARY, "REQUIREMENTS_MISSING_OR_LIMIT", [], True]
)
def test_check_error_vocabulary_is_type_specific_and_unknown_content_stays_internal(code):
    failure, usage = AgentBudgetGateway.classify_model_failure(
        "model:check",
        1,
        EvidenceCheckError(code),
        ModelResult(value={}, input_tokens=23, output_tokens=8),
        "EvidenceCheck",
    )
    known = type(code) is str and code in CHECK_ERROR_CODES
    assert failure.failure_kind == ("SCHEMA" if known else "INTERNAL")
    assert failure.error_class == ("validator_rejected" if known else "application_internal")
    assert failure.domain_error_code == (code if known else None)
    assert export.safe_model_failure(usage["model_failure"]) == usage["model_failure"]
    assert CANARY not in json.dumps(usage) + str(failure)
