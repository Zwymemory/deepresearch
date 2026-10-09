import copy
import hashlib
from unittest.mock import AsyncMock

import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_context import decision_context
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.conversation_context import checked_conversation
from deepresearch_workflow.evidence_check import (
    EvidenceCheckError,
    canonical,
    checked_request,
    sha,
    verifier_instruction,
)
from deepresearch_workflow.evidence_quotes import model_payload
from deepresearch_workflow.graph import WorkflowExecutionError

from .test_agent_context import context
from .test_agent_identity import Ledger
from .test_obligation_alignment import check_fixture


def conversation():
    answer = "题目：写一个 OrderService，构造器注入 PaymentGateway。\n说明为何无需手动 new。"  # noqa: RUF001 - Chinese punctuation in Chinese text.
    return {
        "schema_version": "conversation-referents/1",
        "trusted_as_evidence": False,
        "reports": [
            {
                "run_id": "old-run",
                "session_id": "old-session",
                "origin": "selected_project",
                "question": "给我一道练习",
                "question_truncated": False,
                "answer": answer,
                "answer_sha256": hashlib.sha256(answer.encode()).hexdigest(),
                "answer_truncated": False,
            }
        ],
    }


def test_history_is_copied_into_planning_without_replacing_the_current_question():
    state, budget = context(), AgentRunBudget(runtime="agent")
    state["question"] = "这道题的答案是？"  # noqa: RUF001 - Chinese punctuation in Chinese text.
    state["context_snapshot"] = {"conversation_context": conversation()}
    projected = decision_context(state, budget)
    assert projected["original_question"] == "这道题的答案是？"  # noqa: RUF001 - Chinese punctuation in Chinese text.
    assert projected["conversation_context"] == conversation()
    assert not projected["conversation_context"]["trusted_as_evidence"]
    projected["conversation_context"]["reports"][0]["answer"] = "changed"
    assert (
        "OrderService" in state["context_snapshot"]["conversation_context"]["reports"][0]["answer"]
    )


async def test_actual_planning_request_includes_referent_and_current_request_priority():
    from deepresearch_workflow.agent_decision_instruction import POLICY_VERSION

    from .test_agent_first_planning import PlanningLedger, graph, state
    from .test_obligation_alignment import SequenceModel, initial_wire

    model = SequenceModel(initial_wire())
    runtime = graph(model, PlanningLedger(), legacy_fixture=False)
    initial = {
        **state(),
        "planner_contract": "agent-planning-obligations/3",
        "continuation_contract": "agent-frozen-requirements/2",
        "action_sequence": 0,
        "instruction_policy": POLICY_VERSION,
        "context_snapshot": {"conversation_context": conversation()},
    }
    await runtime.decide(initial)
    request = model.calls[0]
    assert request.payload["conversation_context"] == conversation()
    assert "CURRENT user request and has priority" in request.instruction
    assert "SOLVE" in request.instruction


async def test_followup_with_shared_projection_and_both_memory_kinds_fits_provider_request():
    from deepresearch_workflow.agent_decision_instruction import POLICY_VERSION
    from deepresearch_workflow.context_dictionary import ENCODING, unpack
    from deepresearch_workflow.project_summary import ProjectSummaryCoordinator

    from .test_agent_first_planning import PlanningLedger, graph, state
    from .test_memory_recall import recalled_context
    from .test_obligation_alignment import SequenceModel, initial_wire
    from .test_progress_memory import selected_context
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
            **selected_context(),
            **recalled_context(),
            "conversation_context": conversation(),
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
    assert request.payload["context_encoding"] == ENCODING
    assert unpack(request.payload)["conversation_context"] == conversation()
    assert "SOLVE" in request.instruction
    assert "preserve relevant corrections, disputes" in request.instruction
    assert "prior_progress" in request.payload and "recalled_progress" in request.payload


def test_verifier_receives_the_same_referent_and_keeps_native_evidence_hash_binding():
    request, _ = check_fixture()
    request["original_context"].update(
        contract_version="agent-obligation-context/2", conversation_context=conversation()
    )
    digest = sha(canonical(request))
    assert checked_request(request, digest) == request
    assert (
        model_payload(request, digest)["original_context"]["conversation_context"] == conversation()
    )
    assert "not factual evidence" in verifier_instruction(request)
    assert "Each evidence_id must occur ONCE per claim" in verifier_instruction(request)
    assert (
        "ONE relation spanning their complete contiguous paragraph range"
        in verifier_instruction(request)
    )
    changed = copy.deepcopy(request)
    changed["original_context"]["conversation_context"]["reports"][0]["answer"] = "unbound text"
    with pytest.raises(EvidenceCheckError, match="BINDING"):
        checked_request(changed, digest)
    with pytest.raises(EvidenceCheckError, match="CONTEXT_INVALID"):
        checked_request(changed, sha(canonical(changed)))


@pytest.mark.parametrize(
    "change",
    [
        lambda v: v.update(trusted_as_evidence=True),
        lambda v: v["reports"].append(copy.deepcopy(v["reports"][0])),
        lambda v: v["reports"][0].update(origin="unrelated_recall"),
        lambda v: v["reports"][0].update(answer="x" * 25000),
    ],
)
def test_malformed_or_untrusted_context_is_not_promoted(change):
    value = conversation()
    change(value)
    with pytest.raises(ValueError, match="CONVERSATION_CONTEXT_INVALID"):
        checked_conversation(value)


async def test_revoked_selected_history_blocks_check_before_reservation_or_provider():
    ledger, model = Ledger(), AsyncMock()
    validate = AsyncMock(
        side_effect=WorkflowExecutionError("revoked", error_code="RESEARCH_MEMORY_REVOKED")
    )
    gateway = AgentBudgetGateway(
        run_id="run",
        claim_token="claim",
        ledger=ledger,
        model=model,
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
        validate_memory=validate,
    )
    req = ModelRequest(
        name="EvidenceCheck",
        instruction="fixture",
        schema={},
        payload={"original_context": {"conversation_context": conversation()}},
    )
    with pytest.raises(WorkflowExecutionError) as failed:
        await gateway.model_call("model:check", "CHECK", req)
    assert failed.value.error_code == "RESEARCH_MEMORY_REVOKED"
    assert ledger.reservations == 0
    assert not model.invoke.called
