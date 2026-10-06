"""M1: real provider encoding, with explicitly controlled HTTP/model transports."""

import asyncio
import copy
import json
from datetime import UTC, datetime, timedelta
from unittest.mock import AsyncMock

import httpx
import pytest

from deepresearch_workflow.agent_budget import AgentBudgetGateway
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.graph import (
    RunCancelledError,
    RunTimedOutError,
    WorkflowExecutionError,
)
from deepresearch_workflow.ports import RepositoryEventSink
from deepresearch_workflow.progress_memory import HttpProgressMemoryClient, frozen_progress

from .test_agent_identity import Ledger
from .test_agent_json_transport import envelope, json_settings, oracle_transport
from .test_agent_runtime import (
    LegacyFixtureGraph,
    ObservationDrivenModel,
    continuation_fields,
    setup,
)


def selected_context():
    return {
        "prior_progress": {
            "schema_version": "research-progress-context/1", "context_kind": "prior_progress",
            "trusted_as_evidence": False, "project_id": "project-memory",
            "records": [{"source_run_id": "old-run", "snapshot_sha256": "b" * 64,
                         "snapshot": {
                             "original_goal": "Compare retrieval A and B",
                             "completed_work": ["Mechanisms explained; not current evidence"],
                             "unresolved_questions": [{"goal": "Measure latency",
                                                       "criteria": ["Same data and hardware"],
                                                       "gaps": ["No measured numbers"]}],
                             "next_steps": ["Measure latency; preserve disputed quality claim"],
                             "source_evidence": [], "source_claims": [
                                 {"decision_status": "contested", "claim": "Quality is better"}],
                         }}],
        },
        "prior_progress_binding": {"project_id": "project-memory",
                                   "projection_sha256": "a" * 64, "canonical_bytes": 2048},
    }


def memory_client(client):
    return HttpProgressMemoryClient(client=client, java_base_url="http://java.test",
                                    service_tokens=type("Tokens", (), {
                                        "authorization_header": lambda _: "Bearer service-only"})())


async def test_validation_posts_only_identity_and_verifies_server_response():
    calls = []

    def handler(request):
        calls.append(request)
        return httpx.Response(200, json={"projectId": "project-memory",
                              "projectionSha256": "a" * 64, "checkedAt": "2026-10-06T12:00:00Z"})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        memory = memory_client(client)
        assert await memory.validate("new-run", "fresh-claim", {}) is None
        await memory.validate("new-run", "fresh-claim", selected_context())
    assert len(calls) == 1
    assert calls[0].headers["Authorization"] == "Bearer service-only"
    assert json.loads(calls[0].content) == {
        "runId": "new-run", "claimToken": "fresh-claim", "projectionSha256": "a" * 64}
    assert str(calls[0].url) == "http://java.test/internal/agent/memory/validate"


@pytest.mark.parametrize("status,data,code", [
    (403, {"errorCode": "RESEARCH_MEMORY_REVOKED"}, "RESEARCH_MEMORY_REVOKED"),
    (503, {"errorCode": "PRIVATE SECRET"}, "RESEARCH_MEMORY_UNAVAILABLE"),
    (200, {"projectId": "foreign"}, "RESEARCH_MEMORY_INVALID"),
    (302, {}, "RESEARCH_MEMORY_UNAVAILABLE"),
])
async def test_failed_validation_is_closed_without_retry_or_body_leak(status, data, code):
    transport = AsyncMock(return_value=httpx.Response(status, json=data))
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        with pytest.raises(WorkflowExecutionError) as error:
            await memory_client(client).validate("run", "claim", selected_context())
    assert error.value.error_code == code and "PRIVATE" not in str(error.value)
    assert transport.call_count == 1


def test_invalid_or_partial_binding_fails_and_valid_projection_is_detached():
    original = selected_context()
    detached, _ = frozen_progress(original)
    detached["records"].clear()
    assert original["prior_progress"]["records"]
    for field in ("prior_progress", "prior_progress_binding"):
        bad = copy.deepcopy(original)
        bad.pop(field)
        with pytest.raises(WorkflowExecutionError):
            frozen_progress(bad)
    bad = copy.deepcopy(original)
    bad["prior_progress"]["trusted_as_evidence"] = True
    with pytest.raises(WorkflowExecutionError):
        frozen_progress(bad)


def request():
    return ModelRequest(name="AgentDecision", instruction="Controlled M1 check",
                        payload=selected_context(), schema={"type": "object"})


async def test_revoked_memory_blocks_before_budget_reservation_and_provider_dispatch():
    ledger, transport = Ledger(), AsyncMock()
    validate = AsyncMock(side_effect=WorkflowExecutionError(
        "Reload", error_code="RESEARCH_MEMORY_REVOKED"))
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        gateway = AgentBudgetGateway(run_id="run", claim_token="claim",
            budget=AgentRunBudget(runtime="agent"), ledger=ledger,
            model=OpenAIAgentModel(json_settings(), client), guard=AsyncMock(),
            validate_memory=validate)
        with pytest.raises(WorkflowExecutionError):
            await gateway.model_call("model:1", "DECISION", request())
    assert validate.call_count == 1
    assert not ledger.reservations and not transport.called


async def test_retry_revalidates_and_replay_does_not_bypass_revocation():
    ledger, calls = Ledger(), []
    validate = AsyncMock(side_effect=[None, WorkflowExecutionError(
        "Reload", error_code="RESEARCH_MEMORY_REVOKED")])
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda wire: calls.append(wire) or httpx.Response(503, json={"error": "retry"})
    )) as client:
        gateway = AgentBudgetGateway(run_id="run", claim_token="claim",
            budget=AgentRunBudget(runtime="agent"), ledger=ledger,
            model=OpenAIAgentModel(json_settings(), client), guard=AsyncMock(),
            validate_memory=validate)
        with pytest.raises(WorkflowExecutionError):
            await gateway.model_call("model:1", "DECISION", request())
        assert validate.call_count == 2 and len(calls) == ledger.reservations == 1
        ledger.reserve = AsyncMock(return_value={"replay": {"value": {}}, "attempt": 1})
        validate.side_effect = WorkflowExecutionError(
            "Reload", error_code="RESEARCH_MEMORY_REVOKED")
        with pytest.raises(WorkflowExecutionError):
            await gateway.model_call("model:1", "DECISION", request())
        assert not ledger.reserve.called and len(calls) == 1


async def test_no_memory_or_check_requires_no_memory_validator():
    ledger, validate = Ledger(), AsyncMock(side_effect=AssertionError("Not used"))
    async with httpx.AsyncClient(transport=httpx.MockTransport(
        lambda _: httpx.Response(200, json=envelope("{}"))
    )) as client:
        gateway = AgentBudgetGateway(run_id="run", claim_token="claim",
            budget=AgentRunBudget(runtime="agent"), ledger=ledger,
            model=OpenAIAgentModel(json_settings(), client), guard=AsyncMock(),
            validate_memory=validate)
        await gateway.model_call("check", "CHECK", request())
        await gateway.model_call("plain", "DECISION", request().model_copy(update={"payload": {}}))
    assert not validate.called and ledger.reservations == 2


@pytest.mark.parametrize("with_memory", [False, True])
async def test_actual_graph_adapter_input_drives_latency_task_and_preserves_dispute(with_memory):
    class Continue(ObservationDrivenModel):
        async def invoke(self, req):
            result = await super().invoke(req)
            progress = req.payload.get("prior_progress")
            if not req.payload["tasks"]:
                result.value.update(action="revise_plan", tasks=[{
                    "objective": "Measure latency" if progress else "Clarify missing goal",
                    "acceptance_criteria": ["Same data and hardware; preserve disputed quality"],
                }], reason="Continue latency TODO" if progress else "No saved goal")
                result.value.pop("query", None)
                result.value.pop("tool", None)
                result.value["obligations"] = result.value.pop("requirements")
                result.value["constraints"] = []
            else:
                result = result.model_copy(update={"value": {
                    "action": "stop_with_gaps", "reason": "No measurement tool",
                    "gaps": ["Latency unmeasured; quality contested"],
                    "planner_contract": req.request_binding["planner_contract"],
                    **continuation_fields(req)}})
            result.value["claims_contract"] = "agent-obligation-claims/1"
            return result

    oracle, calls, memory = Continue(), [], type("Memory", (), {"validate": AsyncMock()})()
    base = oracle_transport(oracle, calls)

    async def transport(wire):
        try:
            return await base.handle_async_request(wire)
        except Exception as error:
            pytest.fail(f"Controlled provider fixture failed: {error}")

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        context = setup("version-difference", model=OpenAIAgentModel(json_settings(), client))
        runner, run, repo, ledger, model, tools, evidence, _, saver = context
        if with_memory:
            run = run.model_copy(update={"context_snapshot": selected_context()})
        runner._progress_memory = memory
        runner._graph_factory = lambda claim, budget: AutonomousResearchGraph(
            model=model, tools=tools, repository=repo, ledger=ledger, evidence=evidence,
            events=RepositoryEventSink(repo), budget=budget, claim_token=claim,
            progress_memory=memory).compile(checkpointer=saver)
        await runner.run_claimed(run)
    assert len(calls) == 2
    payloads = [json.loads(json.loads(wire.content)["messages"][1]["content"]) for wire in calls]
    if with_memory:
        assert payloads[0]["prior_progress"] == selected_context()["prior_progress"]
        assert payloads[0]["context_version"] == "agent-decision-context/3"
        assert memory.validate.call_count == 3  # Runner + each provider decision.
        assert any(t["objective"] == "Measure latency" for t in ledger.tasks)
        assert "untrusted saved history" in json.loads(calls[0].content)["messages"][0]["content"]
    else:
        assert all("prior_progress" not in payload for payload in payloads)
        assert not memory.validate.called
    assert context[7].requests[-1].status == "INSUFFICIENT_EVIDENCE"
    assert not tools.calls and not evidence.checks


async def test_runner_rejects_revocation_before_initialization_and_model_call():
    context = setup("version-difference")
    runner, run = context[:2]
    run = run.model_copy(update={"context_snapshot": selected_context()})
    runner._progress_memory = type("Memory", (), {"validate": AsyncMock(
        side_effect=WorkflowExecutionError("Reload", error_code="RESEARCH_MEMORY_REVOKED"))})()
    await runner.run_claimed(run)
    assert not context[4].requests and not context[3].rows
    assert context[7].requests[-1].status == "FAILED"
    assert runner.last_error_code == "RESEARCH_MEMORY_REVOKED"


async def test_expired_selected_run_is_timed_out_without_attempting_memory_validation():
    context = setup("version-difference")
    runner, run = context[:2]
    run = run.model_copy(update={"context_snapshot": selected_context(),
                                "deadline_at": datetime.now(UTC) - timedelta(seconds=1)})
    memory = type("Memory", (), {"validate": AsyncMock(side_effect=WorkflowExecutionError(
        "Expired", error_code="RESEARCH_MEMORY_CLAIM_INVALID"))})()
    runner._progress_memory = memory
    await runner.run_claimed(run)
    assert not memory.validate.called and not context[4].requests
    assert context[7].requests[-1].status == "TIMED_OUT"


@pytest.mark.parametrize("lifecycle_error", [
    RunCancelledError("Cancelled"), RunTimedOutError("Late"),
])
async def test_lifecycle_during_memory_http_wait_retains_original_guard_behavior(lifecycle_error):
    ledger, transport = Ledger(), AsyncMock()
    memory = AsyncMock(side_effect=WorkflowExecutionError(
        "No longer active", error_code="RESEARCH_MEMORY_CLAIM_INVALID"))
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        gateway = AgentBudgetGateway(run_id="run", claim_token="claim",
            budget=AgentRunBudget(runtime="agent"), ledger=ledger,
            model=OpenAIAgentModel(json_settings(), client),
            guard=AsyncMock(side_effect=[None, lifecycle_error]), validate_memory=memory)
        with pytest.raises(type(lifecycle_error)):
            await gateway.model_call("model:1", "DECISION", request())
    assert not ledger.reservations and not transport.called


@pytest.mark.parametrize("change", ["revoked", "changed-binding", "removed-binding"])
async def test_restore_checks_current_binding_and_revocation_before_replaying_settled_call(change):
    class CrashOnce:
        fired = False

        async def emit(self, event, claim):
            if event.event_type == "AGENT_ACTION_SELECTED" and not self.fired:
                self.fired = True
                operation.cancel()
                await asyncio.Event().wait()
            await sink.emit(event, claim)

    crash = CrashOnce()
    context = setup("version-difference", events=crash)
    runner, run, repo, ledger, model, tools, evidence, _, saver = context
    run = run.model_copy(update={"context_snapshot": selected_context()})
    memory = type("Memory", (), {"validate": AsyncMock()})()
    sink = RepositoryEventSink(repo)
    runner._progress_memory = memory
    runner._graph_factory = lambda claim, budget: LegacyFixtureGraph(
        model=model, tools=tools, repository=repo, ledger=ledger, evidence=evidence,
        events=crash, budget=budget, claim_token=claim, progress_memory=memory
    ).compile(checkpointer=saver)
    operation = asyncio.create_task(runner.run_claimed(run))
    with pytest.raises(asyncio.CancelledError):
        await operation
    assert len(model.requests) == 1 and not context[7].requests
    saved = await saver.aget_tuple({"configurable": {"thread_id": run.run_id}})
    assert saved.checkpoint["channel_values"]["context_snapshot"]["prior_progress"]
    if change == "revoked":
        memory.validate.side_effect = WorkflowExecutionError(
            "Reload", error_code="RESEARCH_MEMORY_REVOKED")
        expected = "RESEARCH_MEMORY_REVOKED"
    else:
        modified = selected_context() if change == "changed-binding" else {}
        if modified:
            modified["prior_progress_binding"]["projection_sha256"] = "c" * 64
        run = run.model_copy(update={"context_snapshot": modified})
        expected = "RESEARCH_MEMORY_INVALID"
    await runner.run_claimed(run.model_copy(update={"claim_token": "claim-restored"}))
    assert len(model.requests) == 1 and not tools.calls
    assert runner.last_error_code == expected
