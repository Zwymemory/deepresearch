import copy
import json
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.conversation_context import checked_conversation
from deepresearch_workflow.graph import WorkflowExecutionError
from deepresearch_workflow.progress_memory import frozen_learning

from .test_agent_identity import Ledger
from .test_conversation_context import conversation
from .test_progress_memory import memory_client


def learning_context():
    value = conversation()
    value["schema_version"] = "conversation-referents/2"
    value["reports"][0]["origin"] = "learning_project"
    value["learning_notes"] = {
        "schema_version": "learning-note-context/1",
        "topic_id": "topic-1",
        "title": "构造器注入",
        "correction": "第二问还没有理解",
        "mastery": "unknown",
        "summary_method": "source-excerpts/1",
        "total_entries": 20,
        "entries": [
            {
                "run_id": "old-run",
                "question": "给我一道练习",
                "discussed": [],
                "open_questions": [],
                "user_correction": "",
                "has_exercise": True,
                "has_code": True,
            }
        ],
        "source_refs": [
            {
                "run_id": "old-run",
                "source_sha256": "b" * 64,
                "topic_id": "topic-1",
                "topic_revision": 2,
            }
        ],
    }
    return {
        "conversation_context": value,
        "learning_memory_binding": {
            "project_id": "project-learning",
            "projection_sha256": "a" * 64,
            "canonical_bytes": 2048,
        },
    }


def test_episode_schema_retains_correction_and_does_not_promote_mastery():
    ctx = learning_context()
    assert frozen_learning(ctx)[0] == checked_conversation(ctx["conversation_context"])
    for mutation in (
        lambda v: v["learning_notes"].update(mastery="mastered"),
        lambda v: v["learning_notes"]["source_refs"].clear(),
        lambda v: v["reports"][0].update(origin="foreign_project"),
    ):
        bad = copy.deepcopy(ctx["conversation_context"])
        mutation(bad)
        with pytest.raises(ValueError):
            checked_conversation(bad)


@pytest.mark.parametrize("method", ["source-excerpts/1", "source-grouped-excerpts/1"])
def test_old_and_grouped_notes_preserve_the_same_original_binding(method):
    value = learning_context()["conversation_context"]
    value["learning_notes"]["summary_method"] = method
    assert checked_conversation(value) == value
    value["learning_notes"]["summary_method"] = "unverified-generated-summary/1"
    with pytest.raises(ValueError):
        checked_conversation(value)


def test_native_verifier_retains_episode_binding_and_requires_claim_results():
    from deepresearch_workflow.evidence_check import (
        canonical,
        checked_request,
        sha,
        verifier_instruction,
    )
    from deepresearch_workflow.evidence_quotes import model_payload, model_schema

    from .test_obligation_alignment import check_fixture

    request, _ = check_fixture()
    request["original_context"].update(
        contract_version="agent-obligation-context/2",
        conversation_context=learning_context()["conversation_context"],
    )
    digest = sha(canonical(request))
    assert checked_request(request, digest) == request
    assert (
        model_payload(request, digest)["original_context"]["conversation_context"]
        == learning_context()["conversation_context"]
    )
    assert "claims" in model_schema(request)["required"]
    assert "ALL three top-level keys" in verifier_instruction(request)


async def test_learning_history_is_validated_by_native_service():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(
            200,
            json={
                "projectId": "project-learning",
                "projectionSha256": "a" * 64,
                "checkedAt": "2026-10-08T00:00:00Z",
            },
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        await memory_client(client).validate("run", "claim", learning_context())
    assert len(calls) == 1
    assert calls[0].url.path == "/internal/agent/memory/learning/validate"
    assert json.loads(calls[0].content)["projectionSha256"] == "a" * 64


@pytest.mark.parametrize("purpose", ["DECISION", "CHECK", "SUMMARY"])
async def test_deleted_notes_block_all_model_consumers_before_provider(purpose):
    value = learning_context()["conversation_context"]
    payload = (
        {"original_context": {"conversation_context": value}}
        if purpose == "CHECK"
        else {"conversation_context": value}
    )
    ledger, model = Ledger(), AsyncMock()
    gateway = AgentBudgetGateway(
        run_id="run",
        claim_token="claim",
        ledger=ledger,
        model=model,
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
        validate_memory=AsyncMock(
            side_effect=WorkflowExecutionError("revoked", error_code="RESEARCH_MEMORY_REVOKED")
        ),
    )
    with pytest.raises(WorkflowExecutionError):
        await gateway.model_call(
            "learning-call",
            purpose,
            ModelRequest(name="AgentDecision", instruction="fixture", schema={}, payload=payload),
        )
    assert ledger.reservations == 0
    assert not model.invoke.called


async def test_learning_projection_and_instruction_reach_production_adapter_within_limits():
    from deepresearch_workflow.agent_context import decision_context
    from deepresearch_workflow.agent_decision_instruction import POLICY_VERSION
    from deepresearch_workflow.context_dictionary import ENCODING, unpack
    from deepresearch_workflow.project_summary import ProjectSummaryCoordinator

    from .test_agent_first_planning import PlanningLedger, graph, state
    from .test_obligation_alignment import SequenceModel, initial_wire
    from .test_project_summary import Model, Store, gateway

    model = SequenceModel(initial_wire())
    runtime = graph(model, PlanningLedger(), legacy_fixture=False)
    runtime.progress_memory = type("Memory", (), {"validate": AsyncMock()})()
    initial = {
        **state(),
        "planner_contract": "agent-planning-obligations/3",
        "continuation_contract": "agent-frozen-requirements/2",
        "action_sequence": 0,
        "instruction_policy": POLICY_VERSION,
        "context_snapshot": {
            **learning_context(),
            "project_summary_policy": {
                "enabled": True,
                "projection_encoding": ENCODING,
                "trigger_bytes": 1000,
                "budget_bytes": 24000,
            },
        },
    }
    initial.update(
        await ProjectSummaryCoordinator(Store()).prepare(
            initial, decision_context(initial, AgentRunBudget(runtime="agent")), gateway(Model())
        )
    )
    await runtime.decide(initial)
    request = model.calls[0]
    ModelRequest.model_validate(request.model_dump(by_alias=True))
    assert len(request.instruction) <= 12000
    assert "Discussed does not mean mastered" in request.instruction
    wire = (
        unpack(request.payload)
        if request.payload.get("context_encoding") == ENCODING
        else request.payload
    )
    assert wire["conversation_context"]["learning_notes"]["correction"] == "第二问还没有理解"
    assert runtime.progress_memory.validate.await_count > 0
