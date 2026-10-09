"""M4 dispatch/retry safeguards; the server remains the hash and authority owner."""

import copy
import json
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_context import decision_context
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.graph import WorkflowExecutionError
from deepresearch_workflow.progress_memory import frozen_recall
from deepresearch_workflow.project_summary import mandatory_sections

from .test_agent_context import context
from .test_agent_identity import Ledger
from .test_agent_json_transport import json_settings
from .test_progress_memory import memory_client, selected_context


def recalled_context():
    old = selected_context()
    records = old["prior_progress"]["records"]
    return {
        "recalled_progress": {
            "schema_version": "research-recall-context/1",
            "context_kind": "recalled_progress",
            "trusted_as_evidence": False,
            "records": records,
        },
        "recalled_progress_binding": old["prior_progress_binding"],
    }


async def test_recall_and_explicit_continuation_validate_separately():
    observed = []

    def handler(request):
        observed.append(request.url.path)
        return httpx.Response(
            200,
            json={
                "projectId": "project-memory",
                "projectionSha256": "a" * 64,
                "checkedAt": "2026-10-07T00:00:00Z",
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        await memory_client(client).validate(
            "new", "claim", selected_context() | recalled_context()
        )
    assert observed == ["/internal/agent/memory/validate", "/internal/agent/memory/recall/validate"]


@pytest.mark.parametrize("purpose", ["DECISION", "SUMMARY"])
async def test_recalled_history_revocation_blocks_reservation_and_provider(purpose):
    ledger, transport = Ledger(), AsyncMock()
    validate = AsyncMock(
        side_effect=WorkflowExecutionError("Revoked", error_code="RESEARCH_MEMORY_REVOKED")
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        gateway = AgentBudgetGateway(
            run_id="run",
            claim_token="claim",
            budget=AgentRunBudget(runtime="agent"),
            ledger=ledger,
            model=OpenAIAgentModel(json_settings(), client),
            guard=AsyncMock(),
            validate_memory=validate,
        )
        with pytest.raises(WorkflowExecutionError):
            await gateway.model_call(
                "model:recall",
                purpose,
                ModelRequest(
                    name="Recall",
                    instruction="fixture",
                    payload=recalled_context(),
                    schema={"type": "object"},
                ),
            )
    assert validate.call_count == 1 and not ledger.reservations and not transport.called


def test_empty_detached_valid_and_invalid_recall():
    original = recalled_context()
    projected, _ = frozen_recall(original)
    projected["records"].clear()
    assert original["recalled_progress"]["records"]
    empty = copy.deepcopy(original)
    empty["recalled_progress"]["records"].clear()
    assert frozen_recall(empty)[0]["records"] == []
    for field in ("recalled_progress", "recalled_progress_binding"):
        bad = copy.deepcopy(original)
        bad.pop(field)
        with pytest.raises(WorkflowExecutionError):
            frozen_recall(bad)
    bad = copy.deepcopy(original)
    bad["recalled_progress_binding"]["canonical_bytes"] = 8193
    with pytest.raises(WorkflowExecutionError):
        frozen_recall(bad)


def test_recall_only_in_planning_not_current_evidence_and_summary_pins_criteria():
    state = context()
    state["context_snapshot"] = recalled_context()
    payload = decision_context(state, AgentRunBudget(runtime="agent"))
    assert payload["context_version"] == "agent-decision-context/4"
    assert "No measured numbers" in json.dumps(payload["recalled_progress"])
    assert "No measured numbers" not in json.dumps(payload["evidence"])
    sections = mandatory_sections(state)
    assert "Same data and hardware" in json.dumps(sections["constraints"])
    assert "contested" in json.dumps(sections["disputes"])


def test_empty_recall_does_not_spend_planning_input_budget():
    state = context()
    state["context_snapshot"] = recalled_context()
    state["context_snapshot"]["recalled_progress"]["records"].clear()
    payload = decision_context(state, AgentRunBudget(runtime="agent"))
    assert "recalled_progress" not in payload
    assert payload["context_version"] == "agent-decision-context/2"
