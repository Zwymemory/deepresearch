"""Offline wire/receipt/crash regressions; no live-model acceptance claim."""

import copy
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest
from langgraph.checkpoint.memory import InMemorySaver
from pydantic import ValidationError

from deepresearch_workflow.agent_budget import AgentBudgetGateway, canonical
from deepresearch_workflow.agent_context import decision_context
from deepresearch_workflow.agent_decision_instruction import LEGACY_POLICY_VERSION, POLICY_VERSION
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.domain import ToolEvidence, ToolExecutionResult
from deepresearch_workflow.evidence_client import HttpEvidenceBackend
from deepresearch_workflow.graph import (
    ModelCallError,
    RunBudgetExceededError,
    RunCancelledError,
    RunTimedOutError,
    StaleClaimError,
)

from . import test_evidence_client as peer
from .test_agent_first_planning import graph
from .test_agent_json_transport import envelope, json_settings
from .test_agent_runtime import LedgerSubstitute
from .test_decision_contract import context
from .test_obligation_alignment import SequenceModel, check_fixture, initial_wire


def request(policy=POLICY_VERSION):
    prepared, _ = check_fixture()
    from deepresearch_workflow.evidence_check import response_schema, verifier_instruction

    return ModelRequest(
        name="EvidenceCheck",
        instruction=verifier_instruction(prepared),
        payload=prepared,
        schema=response_schema(prepared),
        max_output_tokens=4096,
        request_binding={"instruction_policy": policy},
    )


async def test_policy1_prepared_bytes_pending_and_settled_replay_are_frozen():
    witness = json.loads(
        (Path(__file__).parent / "fixtures/planner-policy1-instruction-witness.json").read_text()
    )
    runtime, ledger, model, initial, frozen = await context(LEGACY_POLICY_VERSION)
    adapter = OpenAIAgentModel(json_settings(), None)
    for index, (req, row) in enumerate(zip(model.calls, witness["requests"], strict=True), 1):
        prepared = adapter.prepare(req)
        assert hashlib.sha256(prepared.identity).hexdigest() == row["request_sha256"]
        assert hashlib.sha256(prepared.wire).hexdigest() == row["wire_sha256"]
        assert len(prepared.wire) == row["wire_bytes"]
        assert req.max_output_tokens == 1024
        assert "actual_read_source_kinds" not in req.payload
        receipt = ledger.rows[f"model:agent:decision-{index}"]
        assert (
            hashlib.sha256(canonical(receipt["result"]).encode()).hexdigest()
            == row["settled_result_sha256"]
        )
        assert (
            hashlib.sha256(canonical(receipt["usage"]).encode()).hexdigest()
            == row["settled_usage_sha256"]
        )
    await runtime.decide(copy.deepcopy(initial))
    await runtime.decide(copy.deepcopy(frozen))
    assert len(model.calls) == len(ledger.settlements) == 2
    # Pending request identity is the same before a receipt/checkpoint exists.
    pending = copy.deepcopy(initial)
    pending_model = SequenceModel(initial_wire())
    new_runtime = graph(pending_model, type(ledger)(), legacy_fixture=False)
    await new_runtime.decide(pending)
    assert (
        adapter.prepare(pending_model.calls[0]).identity == adapter.prepare(model.calls[0]).identity
    )


@pytest.mark.parametrize("policy", [None, LEGACY_POLICY_VERSION])
@pytest.mark.parametrize("protocol", ["evidence-check/1", "evidence-check/2"])
async def test_legacy_check_wire_and_settled_replay_preserve_original_binding(
    monkeypatch, policy, protocol
):
    witness = json.loads(
        (Path(__file__).parent / "fixtures/check-legacy-output-witness.json").read_text()
    )
    expected = next(r for r in witness["requests"] if r["protocol"] == protocol)
    original_check, original_call = HttpEvidenceBackend.check, AgentBudgetGateway.model_call

    async def selected(self, state, *args):
        if policy is not None:
            state = {**state, "instruction_policy": policy}
        return await original_check(self, state, *args)

    async def call(self, key, purpose, req, validate=None):
        prepared = OpenAIAgentModel(json_settings(), None).prepare(req)
        assert req.max_output_tokens == 1024 and set(req.request_binding) == {
            "check_id",
            "request_sha256",
        }
        assert hashlib.sha256(prepared.identity).hexdigest() == expected["request_sha256"]
        assert hashlib.sha256(prepared.wire).hexdigest() == expected["wire_sha256"]
        result = await original_call(self, key, purpose, req, validate)
        invoke = self.model.invoke
        self.model.invoke = AsyncMock(side_effect=AssertionError("Settled replay cannot dispatch"))
        assert await original_call(self, key, purpose, req, validate) == result
        self.model.invoke = invoke
        return result

    monkeypatch.setattr(HttpEvidenceBackend, "check", selected)
    monkeypatch.setattr(AgentBudgetGateway, "model_call", call)
    await peer.test_peer_verifier_request_and_model_receipt_bind_exact_response_and_current_claim(
        protocol
    )


async def test_fresh_check_wire_reserved_before_dispatch_and_actual_usage_settles_once():
    ledger, calls = LedgerSubstitute(), []
    _, response = check_fixture()
    response["planning_alignment"]["reason"] = "Controlled complete scope assessment. " * 120
    req = request()

    def transport(wire):
        row = ledger.rows["model:capacity"]
        body = json.loads(wire.content)
        assert row["result"] is None and row["output_reserved"] == body["max_tokens"] == 4096
        calls.append(wire)
        return httpx.Response(
            200,
            json=envelope(
                canonical(response),
                usage={"prompt_tokens": 101, "completion_tokens": 1536},
            ),
        )

    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        gw = AgentBudgetGateway(
            run_id="offline",
            claim_token="claim",
            ledger=ledger,
            model=OpenAIAgentModel(json_settings(), client),
            budget=AgentRunBudget(runtime="agent"),
            guard=AsyncMock(),
        )
        prepared = gw.model.prepare(req)
        result = await gw.model_call("model:capacity", "CHECK", req)
        assert await gw.model_call("model:capacity", "CHECK", req) == result
    assert len(calls) == 1 and calls[0].content == prepared.wire
    assert ledger.rows["model:capacity"]["hash"] == hashlib.sha256(prepared.identity).hexdigest()
    assert (await ledger.summary(None, None))["outputTokens"] == 1536
    assert len(canonical(result.value)) > 4096  # controlled output, no tokenizer claim


@pytest.mark.parametrize(
    "changes,purpose",
    [
        ({"name": "AgentDecision"}, "DECISION"),
        ({}, "DECISION"),
        ({"request_binding": {}}, "CHECK"),
        ({"request_binding": {"instruction_policy": LEGACY_POLICY_VERSION}}, "CHECK"),
        (
            {"request_binding": {"instruction_policy": "foreign"}, "max_output_tokens": 1024},
            "CHECK",
        ),
        ({"max_output_tokens": 4097}, "CHECK"),
    ],
)
async def test_gateway_denies_bypassed_dto_growth_and_unknown_policy_before_prepare(
    changes, purpose
):
    req = request().model_copy(update=changes)
    model = OpenAIAgentModel(json_settings(), None)
    model.prepare = AsyncMock(side_effect=AssertionError("No preparation"))
    ledger = LedgerSubstitute()
    gw = AgentBudgetGateway(
        run_id="offline",
        claim_token="claim",
        ledger=ledger,
        model=model,
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
    )
    with pytest.raises(ModelCallError):
        await gw.model_call("model:bad", purpose, req)
    model.prepare.assert_not_called()
    assert not ledger.rows
    if changes.get("name") == "AgentDecision":
        with pytest.raises(ValidationError):
            ModelRequest.model_validate(req.model_dump(by_alias=True))


async def test_output_beyond_reservation_stops_after_one_measured_receipt():
    ledger = LedgerSubstitute()
    model = SimpleNamespace(
        invoke=AsyncMock(
            return_value=ModelResult(value=check_fixture()[1], input_tokens=23, output_tokens=4097)
        )
    )
    gw = AgentBudgetGateway(
        run_id="offline",
        claim_token="claim",
        ledger=ledger,
        model=model,
        budget=AgentRunBudget(runtime="agent"),
        guard=AsyncMock(),
    )
    with pytest.raises(RunBudgetExceededError):
        await gw.model_call("model:over", "CHECK", request())
    assert ledger.rows["model:over"]["usage"]["output_tokens"] == 4097
    assert model.invoke.await_count == 1


async def frozen_check_state():
    _, _, _, _, state = await context(POLICY_VERSION)
    state = copy.deepcopy(state)
    binding = state["requirement_bindings"][0]
    state.update(decision_steps=2, action_sequence=2)
    state["decision"] = {
        "planner_contract": "agent-planning-obligations/3",
        "claims_contract": "agent-obligation-claims/1",
        "continuation_contract": "agent-frozen-requirements/2",
        "requirements_ref": state["original_requirements"]["manifest_sha256"],
        "action": "check_claims",
        "task_id": state["tasks"][0]["task_id"],
        "reason": "Controlled current original check",
        "claims": [{"text": "Both domains are reserved", **binding}],
    }
    return state


@pytest.mark.parametrize("window", ["checkpoint", "settled_tool", "unknown_tool"])
async def test_unusable_check_resume_stops_without_planning_recheck_or_duplicate_publication(
    window,
):
    state = await frozen_check_state()

    class Crash(BaseException):
        pass

    class CrashLedger(LedgerSubstitute):
        crash = window == "unknown_tool"

        async def settle(self, run, claim, key, attempt, value, usage, unknown=False):
            if self.crash and self.rows[key]["kind"] == "TOOL":
                self.crash = False
                raise Crash("Crash before outer TOOL settlement")
            await super().settle(run, claim, key, attempt, value, usage, unknown=unknown)

    ledger = CrashLedger()
    prepared, _ = check_fixture()
    provider_calls = []

    def truncated(wire):
        provider_calls.append(wire)
        assert json.loads(wire.content)["max_tokens"] == 4096
        return httpx.Response(
            200,
            json=envelope(
                '{"claims": [',
                choices=[{"finish_reason": "length", "message": {"content": '{"claims": ['}}],
                usage={"prompt_tokens": 71, "completion_tokens": 4096},
            ),
        )

    client = httpx.AsyncClient(transport=httpx.MockTransport(truncated))
    model = OpenAIAgentModel(json_settings(), client)
    runtime = graph(model, ledger, legacy_fixture=False)
    backend = HttpEvidenceBackend(
        client=None, java_base_url="http://native.test", service_tokens=None
    )
    backend.post = AsyncMock(
        return_value={
            "check_id": prepared["check_id"],
            "investigation_id": prepared["investigation_id"],
            "request": prepared,
            "request_sha256": hashlib.sha256(canonical(prepared).encode()).hexdigest(),
            "requires_model": True,
        }
    )
    backend.check = AsyncMock(wraps=backend.check)
    backend.publish = AsyncMock(
        return_value={
            "approved": True,
            "terminal_status": "INSUFFICIENT_EVIDENCE",
            "report_status": "insufficient",
            "answer": "Original obligations remain unchecked",
            "citations": [],
        }
    )
    runtime.evidence = backend
    state["context_snapshot"] = {"agent_scope": {"project_id": "project"}}
    if window == "unknown_tool":
        with pytest.raises(Crash):
            await runtime.act(copy.deepcopy(state))
        assert next(row for row in ledger.rows.values() if row["kind"] == "TOOL")["result"] is None
        runtime.claim_token = "new-claim-after-crash"
    update = await runtime.act(copy.deepcopy(state))
    if window == "settled_tool":
        # Crash after tool settlement, before its state checkpoint: replay same act.
        update = await runtime.act(copy.deepcopy(state))
        assert runtime.evidence.check.await_count == 1
    stopped = {**state, **update}
    assert stopped["unusable_check"]["criterion_ids"]
    assert stopped["tasks"][0]["status"] == "blocked"
    assert not stopped["packet"]
    assert stopped["agent_usage"]["outputTokens"] == 4096
    if window == "unknown_tool":
        assert runtime.evidence.check.await_count == 1  # never re-enter after crash
        assert "check_id" not in stopped["unusable_check"]
    else:
        assert stopped["unusable_check"]["check_id"] == "check-controlled"
    saver, config = InMemorySaver(), {"configurable": {"thread_id": "unusable-" + window}}
    compiled = runtime.compile(checkpointer=saver, interrupt_after=["decide"])
    # Invoke from checkpointed state at decide, bypass fresh initialization naturally.
    await compiled.ainvoke(stopped, config)
    saved = await compiled.aget_state(config)
    assert saved.values["unusable_check"] == stopped["unusable_check"]
    assert saved.values["decision"]["action"] == "stop_with_gaps"
    assert saved.values["decision_steps"] == 2
    await compiled.ainvoke(None, config)
    final = (await compiled.aget_state(config)).values
    assert final["final_status"] == "INSUFFICIENT_EVIDENCE"
    await runtime.act(final)
    runtime.evidence.publish.assert_awaited_once()
    assert len(provider_calls) == 1
    row = next(row for row in ledger.rows.values() if row["kind"] == "MODEL")
    assert row["result"] is None and row["usage"]["model_failure"]["retryable"] is False
    assert row["usage"]["model_failure"]["error_class"] == "output_truncated"
    assert row["usage"]["input_tokens"] == 71 and row["usage"]["output_tokens"] == 4096
    assert backend.post.await_count == 1  # only prepare; never complete/promote
    await client.aclose()


@pytest.mark.parametrize(
    "policy,cap", [(None, 1024), (LEGACY_POLICY_VERSION, 1024), (POLICY_VERSION, 4096)]
)
async def test_backend_selection_retains_legacy_bindings_and_unusable_prepared_ids(policy, cap):
    prepared, _ = check_fixture()
    paths, requests = [], []

    def transport(req):
        paths.append(req.url.path)
        assert req.url.path.endswith("prepare")
        return httpx.Response(
            200,
            json={
                "check_id": prepared["check_id"],
                "investigation_id": prepared["investigation_id"],
                "request": prepared,
                "request_sha256": hashlib.sha256(canonical(prepared).encode()).hexdigest(),
                "requires_model": True,
            },
        )

    class Gateway:
        async def model_call(self, key, purpose, req, validate):
            requests.append(req)
            assert purpose == "CHECK" and req.max_output_tokens == cap
            raise ModelCallError(
                key,
                attempt=1,
                failure_kind="SCHEMA",
                error_class="output_truncated",
                retryable=False,
            )

    state = {
        "run_id": "offline",
        "claim_token": "claim",
        "context_snapshot": {"agent_scope": {"project_id": "project"}},
        "evidence": [],
    }
    if policy:
        state["instruction_policy"] = policy
    async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as client:
        backend = HttpEvidenceBackend(
            client=client,
            java_base_url="http://native.test",
            service_tokens=SimpleNamespace(authorization_header=lambda: "fixture"),
        )
        if policy == POLICY_VERSION:
            result = await backend.check(state, {"task_id": "task"}, "tool-call", [], Gateway())
            assert result["non_retryable_check"] and result["check_id"] == prepared["check_id"]
            assert (
                result["investigation_id"] == prepared["investigation_id"]
                and result["model_call_id"]
            )
        else:
            with pytest.raises(ModelCallError):
                await backend.check(state, {"task_id": "task"}, "tool-call", [], Gateway())
            assert set(requests[0].request_binding) == {"check_id", "request_sha256"}
    assert len(paths) == 1


@pytest.mark.parametrize(
    "error", [RunBudgetExceededError, RunCancelledError, RunTimedOutError, StaleClaimError]
)
async def test_backend_does_not_relabel_cancel_timeout_budget_or_stale(error):
    prepared, _ = check_fixture()
    backend = HttpEvidenceBackend(
        client=None, java_base_url="http://native.test", service_tokens=None
    )
    backend.post = AsyncMock(
        return_value={
            "check_id": prepared["check_id"],
            "request": prepared,
            "request_sha256": hashlib.sha256(canonical(prepared).encode()).hexdigest(),
            "requires_model": True,
        }
    )
    state = {
        "instruction_policy": POLICY_VERSION,
        "run_id": "offline",
        "claim_token": "claim",
        "context_snapshot": {"agent_scope": {"project_id": "project"}},
    }
    with pytest.raises(error):
        await backend.check(
            state,
            {"task_id": "task"},
            "tool-call",
            [],
            SimpleNamespace(model_call=AsyncMock(side_effect=error("existing terminal"))),
        )


async def test_retrieval_origin_is_receipt_based_and_card_url_never_authorizes_read():
    _, _, _, _, state = await context(POLICY_VERSION)
    state["grant_id"] = "controlled-grant"
    ledger, runtime = LedgerSubstitute(), None
    runtime = graph(None, ledger, legacy_fixture=False)
    state["requested_scopes"] = ["kb_search", "web_search", "read_source", "check_claims"]
    for tool in ("kb_search", "web_search"):
        state["decision_steps"] += 1
        state["action_sequence"] = state["decision_steps"]
        state["decision"] = {
            "planner_contract": "agent-planning-obligations/3",
            "claims_contract": "agent-obligation-claims/1",
            "continuation_contract": "agent-frozen-requirements/2",
            "requirements_ref": state["original_requirements"]["manifest_sha256"],
            "action": "search",
            "tool": tool,
            "query": "missing authorized original",
            "reason": "Controlled coverage search",
        }
        runtime.tools = SimpleNamespace(
            execute=AsyncMock(
                return_value=ToolExecutionResult(
                    evidence=[
                        ToolEvidence(
                            source_id=tool,
                            content="Card mentions https://unsearched.test/original",
                            source_uri="https://discovered.test/original",
                        )
                    ],
                    call_id="controlled",
                )
            )
        )
        state.update(await runtime.act(state))
    for candidate in state["candidates"]:
        assert candidate["origin"]["tool"] == candidate["source_id"]
        assert candidate["origin"]["receipt_call_id"] == candidate["parent_call_id"]
        assert candidate["source_uri"] == "https://discovered.test/original"
    state["evidence"] = [
        {
            "evidence_id": "read-kb",
            "source": {
                "kind": "knowledge",
                "source_id": "kb_search",
                "locator": {"kind": "knowledge_chunk"},
            },
            "snapshot": {"text": "Card mentions https://unsearched.test/original"},
        }
    ]
    payload = decision_context(state, runtime.budget)
    assert payload["actual_read_source_kinds"] == ["knowledge"]
    assert payload["evidence"][0]["source"]["locator"]["kind"] == "knowledge_chunk"
    state["decision_steps"] += 1
    state["action_sequence"] = state["decision_steps"]
    state["decision"].update(action="read_source", source_id="https://unsearched.test/original")
    state["decision"].pop("tool")
    state["decision"].pop("query")
    runtime.evidence.read = AsyncMock(side_effect=AssertionError("No authorization"))
    update = await runtime.act(state)
    assert update["observations"][-1]["errorCode"] == "SOURCE_NOT_IN_CURRENT_SEARCH"
    runtime.evidence.read.assert_not_called()
