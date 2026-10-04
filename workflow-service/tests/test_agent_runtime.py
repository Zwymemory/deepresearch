"""Actual runner/graph with semantic and transport substitutes, never live capability claims."""

from __future__ import annotations

import asyncio
import copy
import hashlib
import json
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from langgraph.checkpoint.memory import InMemorySaver

from deepresearch_workflow.agent_protocol import AgentRunBudget, ModelRequest, ModelResult
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.domain import ClaimedRun, ToolEvidence, ToolExecutionResult
from deepresearch_workflow.graph import RunBudgetExceededError, WorkflowExecutionError
from deepresearch_workflow.ports import RepositoryEventSink
from deepresearch_workflow.runner import WorkflowRunner
from deepresearch_workflow.settings import Settings

from .conftest import FakeRepository

ROOT = Path(__file__).resolve().parents[2]


class LedgerSubstitute:
    """Unit substitute only. SQL atomicity/role fences are covered by PostgreSQL tests."""

    def __init__(self):
        self.rows = {}
        self.tasks = []

    async def scope(self, *_):
        return {"project_id": "project-test", "tenant_id": "tenant", "owner_id": "owner"}

    async def reserve(self, run, claim, key, kind, purpose, digest, inp, out, budget):
        row = self.rows.get(key)
        if row and row["hash"] != digest:
            raise WorkflowExecutionError("replay mismatch")
        if row and row.get("result") is not None:
            return {"replay": row["result"], "attempt": 1}
        if row:
            raise WorkflowExecutionError(
                "ambiguous operation", error_code="AGENT_OPERATION_UNKNOWN"
            )
        models = sum(r["kind"] == "MODEL" for r in self.rows.values())
        tools = sum(r["kind"] == "TOOL" for r in self.rows.values())
        if (kind == "MODEL" and models >= budget.max_model_calls) or (
            kind == "TOOL" and tools >= budget.max_tool_calls
        ):
            raise RunBudgetExceededError("durable test ledger exhausted")
        self.rows[key] = {
            "kind": kind,
            "hash": digest,
            "result": None,
            "usage": {},
            "input_reserved": inp,
            "output_reserved": out,
        }
        return {"replay": None, "attempt": 1}

    async def settle(self, run, claim, key, attempt, value, usage, unknown=False):
        self.rows[key].update(result=None if unknown else value, usage=usage)

    async def summary(self, *_):
        models = [r for r in self.rows.values() if r["kind"] == "MODEL"]
        known = all(r["usage"].get("input_tokens") is not None for r in models)
        return {
            "modelCalls": len(models),
            "toolCalls": sum(r["kind"] == "TOOL" for r in self.rows.values()),
            "inputTokens": sum(r["usage"].get("input_tokens") or 0 for r in models)
            if known
            else None,
            "outputTokens": sum(r["usage"].get("output_tokens") or 0 for r in models)
            if known
            else None,
            "inputTokensStatus": "known" if known else "unknown",
            "outputTokensStatus": "known" if known else "unknown",
            "estimatedCost": None,
            "costStatus": "unknown",
            "currency": "CNY",
        }

    async def save_tasks(self, run, claim, tasks):
        self.tasks = copy.deepcopy(tasks)


class ObservationDrivenModel:
    """Small synthetic semantic oracle. Actions depend on input, never fixture IDs."""

    def __init__(self):
        self.requests = []

    async def invoke(self, request):
        self.requests.append(request)
        payload = dict(request.payload)
        if "question_segments" in payload:
            payload["original_question"] = "".join(
                row["text"] for row in payload["question_segments"]["segments"]
            )
        if request.name == "SyntheticCheck":
            texts = payload["texts"]
            pairs = [
                tuple(re.search(r"Version ([\d.]+) allows (\d+) requests", t).groups())
                for t in texts
            ]
            oracle = any("synthetic oracle" in t for t in texts)
            corrupted = any("deliberately corrupted" in t for t in texts)
            conflict = any(len({n for v, n in pairs if v == version}) > 1 for version, _ in pairs)
            status = (
                "supported"
                if oracle or (not corrupted and not conflict)
                else "contested"
                if conflict
                else "insufficient"
            )
            value = {"status": status, "requires_counterevidence": status != "supported"}
        else:
            observations = payload["observations"]
            last = observations[-1] if observations else {}
            packet = payload["packet"]
            unread = [
                c
                for c in payload["candidates"]
                if c["source_id"] not in {e["source"]["source_id"] for e in payload["evidence"]}
            ]
            if last.get("action") == "revise_plan":
                value = {
                    "action": "search",
                    "query": payload["original_question"] + " counterevidence",
                    "tool": "web_search",
                    "reason": "按未解决裁决查反证",
                }
            elif unread:
                value = {
                    "action": "read_source",
                    "source_id": unread[0]["source_id"],
                    "reason": "摘要缺少完整适用条件\uff0c读取原文",
                }
            elif packet and packet.get("status") == "supported":
                value = {
                    "action": "finish",
                    "answer": "server validates this candidate",
                    "citations": ["placeholder"],
                    "reason": "已有适用范围内的裁决",
                }
            elif last.get("action") == "check_claims" and packet.get("status") != "supported":
                value = {
                    "action": "revise_plan",
                    "tasks": [
                        {
                            "objective": "核查相反资料与适用版本",
                            "acceptance_criteria": ["回源读取并解决冲突或保留争议"],
                        }
                    ],
                    "reason": "观察到反证或缺口\uff0c增加核查目标",
                }
            elif not payload["candidates"] or (
                last.get("action") == "search" and not last.get("candidates")
            ):
                if packet:
                    value = {
                        "action": "stop_with_gaps",
                        "gaps": ["补查仍未获得能排除冲突的独立证据"],
                        "reason": "保留争议",
                    }
                else:
                    value = {
                        "action": "search",
                        "query": payload["original_question"]
                        + (" exact primary source" if observations else ""),
                        "tool": "web_search",
                        "reason": "空结果后改查" if observations else "查原题候选材料",
                    }
            else:
                unknown = {
                    "status": "unknown",
                    "value": None,
                    "reason": "Synthetic scope not established",
                }
                value = {
                    "action": "check_claims",
                    "claims": [
                        {
                            "text": payload["original_question"],
                            "kind": "factual",
                            "applicability": {
                                "subject": payload["original_question"],
                                "version": unknown,
                                "valid_at": unknown,
                                "conditions": [],
                            },
                        }
                    ],
                    "reason": "核对原文的支持、反证与版本",
                }
        if (
            request.name == "AgentDecision"
            and not payload.get("original_requirements")
            and not payload["tasks"]
        ):
            unknown = {
                "status": "unknown",
                "value": None,
                "reason": "Synthetic scope not established",
            }
            value["requirements"] = [
                {
                    "text": payload["original_question"][:400],
                    "question_spans": [{"start": 0, "end": len(payload["original_question"])}],
                    "kind": "factual",
                    "applicability": {
                        "subject": payload["original_question"],
                        "version": unknown,
                        "valid_at": unknown,
                        "conditions": [],
                    },
                }
            ]
        if value.get("action") == "check_claims":
            task = next(
                (t for t in payload["tasks"] if t["status"] in {"pending", "running", "blocked"}),
                payload["tasks"][-1],
            )
            value["criterion_bindings"] = [
                {"criterion_id": task["criteria"][0]["criterion_id"], "claim_index": 0}
            ]
        if request.request_binding.get("planner_contract"):
            value["planner_contract"] = request.request_binding["planner_contract"]
            for requirement in value.get("requirements", []):
                requirement.pop("question_spans")
                requirement["segment_ids"] = [
                    row["segment_id"] for row in payload["question_segments"]["segments"]
                ]
        return ModelResult(value=value, input_tokens=120, output_tokens=100)


class SourceTransport:
    def __init__(self, records, repository):
        self.records = records
        self.calls = []
        self.repository = repository

    async def execute(self, request):
        self.calls.append(request)
        matches = [r for r in self.records if "synthetic oracle" not in r["snapshot"]["text"]]
        if "counterevidence" in request.task.query:
            matches = [r for r in self.records if "synthetic oracle" in r["snapshot"]["text"]]
        return ToolExecutionResult(
            call_id=request.call_id,
            evidence=[
                ToolEvidence(source_id=r["source"]["source_id"], content=r["snapshot"]["text"][:80])
                for r in matches
            ],
        )


class EvidenceSubstitute:
    """Substitutes B protocol; real authority/reader/parser integration is a separate gate."""

    def __init__(self, records):
        self.records = records
        self.checks = []
        self.publications = []

    async def read(self, state, task, call_id, source_id):
        record = copy.deepcopy(
            next(r for r in self.records if r["source"]["source_id"] == source_id)
        )
        assert (
            hashlib.sha256(record["snapshot"]["text"].encode()).hexdigest()
            == record["snapshot"]["sha256"]
        )
        return {"records": [record]}

    async def check(self, state, task, call_id, claims, gateway):
        self.checks.append(claims)
        schema = {
            "type": "object",
            "properties": {
                "status": {"enum": ["supported", "contested", "insufficient"]},
                "requires_counterevidence": {"type": "boolean"},
            },
            "required": ["status", "requires_counterevidence"],
            "additionalProperties": False,
        }
        result = await gateway.model_call(
            "model:check:" + call_id,
            "CHECK",
            ModelRequest(
                name="SyntheticCheck",
                schema=schema,
                instruction="Synthetic test oracle only",
                payload={"texts": [e["snapshot"]["text"] for e in state["evidence"]]},
            ),
        )
        records = []
        for index, claim in enumerate(claims):
            identity = "claim-" + hashlib.sha256((call_id + str(index)).encode()).hexdigest()[:40]
            status = result.value["status"]
            evidence_ids = [e["evidence_id"] for e in state["evidence"]]
            records.extend(
                [
                    {
                        "record_type": "Claim",
                        **claim,
                        "claim_id": identity,
                        "run_id": state["run_id"],
                        "freshness": "fresh",
                        "decision_status": status,
                        "evidence_links": [
                            {"evidence_id": eid, "relation": "supports"} for eid in evidence_ids
                        ],
                    },
                    {
                        "record_type": "DecisionRecord",
                        "decision_id": "decision-" + identity,
                        "claim_id": identity,
                        "run_id": state["run_id"],
                        "decision_status": status,
                        "adopted_evidence_ids": evidence_ids if status == "supported" else [],
                        "unresolved_evidence_ids": [] if status == "supported" else evidence_ids,
                        "gaps": [] if status == "supported" else ["counterevidence required"],
                    },
                ]
            )
        return {
            "check_id": "check-" + call_id,
            "status": result.value["status"],
            "records": records,
            "gaps": [] if result.value["status"] == "supported" else ["counterevidence required"],
            "follow_up_actions": []
            if result.value["status"] == "supported"
            else [{"action": "seek_counterevidence"}],
        }

    async def publish(self, state, task, call_id, decision):
        from deepresearch_workflow.agent_investigations import current_packets

        self.publications.append(call_id)
        packets = current_packets(state)
        supported = any(p.get("status") == "supported" for p in packets)
        complete = (
            bool(packets)
            and all(p.get("status") == "supported" for p in packets)
            and all(t["status"] == "done" for t in state["tasks"])
            and state.get("requirement_coverage", {}).get("complete", False)
        )
        return {
            "approved": True,
            "report_status": "complete" if complete else "partial" if supported else "insufficient",
            "terminal_status": "SUCCEEDED" if complete else "INSUFFICIENT_EVIDENCE",
            "answer": "测试裁决的条件性结论[来源1]"
            if supported
            else "仍有待核查事项\uff1a测试来源未解决",
            "citations": [state["evidence"][-1]["source"]["source_id"]] if supported else [],
        }


class Finalizer:
    def __init__(self):
        self.requests = []

    async def finalize(self, run_id, request):
        self.requests.append(request)


def setup(case, *, budget=None, events=None, model=None):
    fixture = json.loads(
        (ROOT / "testdata/agent-foundation/evidence" / (case + ".json")).read_text()
    )
    records = [r for r in fixture["records"] if r["record_type"] == "Evidence"]
    repository = FakeRepository()
    ledger = LedgerSubstitute()
    model = model or ObservationDrivenModel()
    tools = SourceTransport(records, repository)
    evidence = EvidenceSubstitute(records)
    finalizer = Finalizer()
    saver = InMemorySaver()
    budget = budget or AgentRunBudget(runtime="agent")

    def factory(claim, effective):
        return AutonomousResearchGraph(
            model=model,
            tools=tools,
            repository=repository,
            ledger=ledger,
            evidence=evidence,
            events=events or RepositoryEventSink(repository),
            budget=effective,
            claim_token=claim,
        ).compile(checkpointer=saver)

    runner = WorkflowRunner(
        repository=repository,
        control_plane=finalizer,
        graph_factory=factory,
        settings=Settings(runner_enabled=False),
        agent_ledger_factory=lambda _: ledger,
    )
    run = ClaimedRun(
        run_id="wf-test",
        session_id="session",
        user_id="opaque:user:with:colon",
        question=fixture["question"],
        endpoint="/api/research/agents",
        graph_thread_id="wf-test",
        requested_scopes=["web_search", "read_source", "check_claims"],
        grant_id="grant-test",
        budget=budget,
        claim_token="claim-first",
        deadline_at=datetime.now(UTC) + timedelta(seconds=180),
        status="QUEUED",
        stage="QUEUED",
    )
    return runner, run, repository, ledger, model, tools, evidence, finalizer, saver


@pytest.mark.parametrize(
    "case,status",
    [
        ("empty-retrieval", "INSUFFICIENT_EVIDENCE"),
        # The substitute resolves its claim but leaves its added native goal pending.
        # Whole-run publication must expose that incompleteness.
        ("wrong-material", "INSUFFICIENT_EVIDENCE"),
        ("version-difference", "SUCCEEDED"),
        ("unresolved-conflict", "INSUFFICIENT_EVIDENCE"),
    ],
)
async def test_four_shared_fixtures_execute_real_runner_and_branch_from_observations(case, status):
    runner, run, repo, ledger, model, tools, evidence, finalizer, _ = setup(case)
    await runner.run_claimed(run)
    assert finalizer.requests[-1].status == status
    assert finalizer.requests[-1].usage["costStatus"] == "unknown"
    actions = [
        e.safe_payload["action"] for e in repo.events if e.event_type == "AGENT_ACTION_SELECTED"
    ]
    assert actions[0] == "search"
    if case == "empty-retrieval":
        assert len(tools.calls) == 2 and tools.calls[0].task.query != tools.calls[1].task.query
    else:
        assert "read_source" in actions and "check_claims" in actions
    if case in {"wrong-material", "unresolved-conflict"}:
        assert "revise_plan" in actions
    if case == "wrong-material":
        assert len(evidence.checks) == 2 and actions.index("revise_plan") < actions.index("finish")
    assert len(model.requests) <= 16 and len(ledger.rows) <= 32


async def test_source_text_mutation_changes_subsequent_actions():
    first = setup("wrong-material")
    await first[0].run_claimed(first[1])
    second = setup("wrong-material")
    record = second[5].records[0]
    record["snapshot"]["text"] = record["snapshot"]["text"].replace(
        "This controlled guide was deliberately corrupted.",
        "No conflict is asserted in this synthetic source.",
    )
    record["snapshot"]["sha256"] = hashlib.sha256(record["snapshot"]["text"].encode()).hexdigest()
    await second[0].run_claimed(second[1])
    assert any(e.event_type == "AGENT_PLAN_REVISED" for e in first[2].events)
    assert not any(e.event_type == "AGENT_PLAN_REVISED" for e in second[2].events)
    assert len(second[4].requests) < len(first[4].requests)


async def test_empty_web_search_can_switch_to_kb_without_rebinding_task_scope():
    context = setup("version-difference")
    model, tools = context[4], context[5]
    original_invoke, original_execute = model.invoke, tools.execute
    granted_tools = {}

    async def decide_from_empty_result(request):
        observations = request.payload.get("observations", [])
        last = observations[-1] if observations else {}
        if last.get("action") == "search" and not last.get("candidates"):
            model.requests.append(request)
            return ModelResult(
                value={
                    "action": "search",
                    "query": "".join(
                        row["text"] for row in request.payload["question_segments"]["segments"]
                    ),
                    "planner_contract": request.request_binding["planner_contract"],
                    "tool": "kb_search",
                    "reason": "网页检索为空\uff0c改查获准的知识库",
                },
                input_tokens=120,
                output_tokens=100,
            )
        return await original_invoke(request)

    async def enforce_single_tool_grant(request):
        # Same invariant as WorkflowAccessService: an execution task cannot
        # acquire a second tool scope. It must remain possible to change tools.
        prior = granted_tools.setdefault(request.task.task_id, request.task.tool)
        assert prior == request.task.tool
        if request.task.tool == "web_search":
            tools.calls.append(request)
            return ToolExecutionResult(call_id=request.call_id, evidence=[])
        return await original_execute(request)

    model.invoke, tools.execute = decide_from_empty_result, enforce_single_tool_grant
    run = context[1].model_copy(
        update={"requested_scopes": [*context[1].requested_scopes, "kb_search"]}
    )
    await context[0].run_claimed(run)

    assert context[7].requests[-1].status == "SUCCEEDED"
    assert [request.task.tool for request in tools.calls] == ["web_search", "kb_search"]
    assert len(granted_tools) == 2
    assert all(request.task.task_id == request.call_id for request in tools.calls)
    assert len(context[3].tasks) == 1
    assert context[3].tasks[0]["task_id"] not in granted_tools
    assert context[3].tasks[0]["evidence_ids"]


async def test_unified_model_budget_includes_decision_and_verifier_calls():
    context = setup("version-difference", budget=AgentRunBudget(runtime="agent", maxModelCalls=4))
    await context[0].run_claimed(context[1])
    assert context[7].requests[-1].status == "BUDGET_EXCEEDED"
    assert len(context[4].requests) == 4
    assert len(context[6].checks) == 1  # Verifier attempted through the same exhausted ledger.
    assert not context[6].publications


async def test_cancel_before_execution_never_calls_provider_or_tools():
    context = setup("wrong-material")
    context[2].cancelled = True
    await context[0].run_claimed(context[1])
    assert not context[4].requests and not context[5].calls and not context[7].requests


async def test_deadline_before_execution_never_calls_provider_or_tools():
    context = setup("wrong-material")
    run = context[1].model_copy(update={"deadline_at": datetime.now(UTC) - timedelta(seconds=1)})
    await context[0].run_claimed(run)
    assert context[7].requests[-1].status == "TIMED_OUT"
    assert not context[4].requests and not context[5].calls


@pytest.mark.parametrize(
    "crash_event", ["AGENT_ACTION_SELECTED", "AGENT_OBSERVATION", "AGENT_PUBLICATION_VALIDATED"]
)
async def test_checkpoint_recovery_reuses_settled_calls_without_repeating_external_effects(
    crash_event,
):
    class CrashOnce:
        fired = False

        async def emit(self, event, claim):
            if event.event_type == crash_event and not self.fired:
                self.fired = True
                operation.cancel()  # Interrupt the runner, as process shutdown does.
                await asyncio.Event().wait()
            await sink.emit(event, claim)

    crash = CrashOnce()
    context = setup("version-difference", events=crash)
    sink = RepositoryEventSink(context[2])
    operation = asyncio.create_task(context[0].run_claimed(context[1]))
    with pytest.raises(asyncio.CancelledError):
        await operation
    assert not context[7].requests
    await context[0].run_claimed(context[1].model_copy(update={"claim_token": "claim-recovered"}))
    assert context[7].requests[-1].status == "SUCCEEDED"
    assert len(context[5].calls) == 1
    assert len(context[6].publications) == 1
    assert len(context[4].requests) == 5


async def test_cancel_during_external_tool_prevents_evidence_and_publication():
    context = setup("version-difference")
    original = context[5].execute

    async def cancel(request):
        result = await original(request)
        context[2].cancelled = True
        return result

    context[5].execute = cancel
    await context[0].run_claimed(context[1])
    assert not context[6].checks and not context[6].publications and not context[7].requests


def test_new_packet_identity_does_not_count_as_new_evidence():
    base = {
        "candidates": [],
        "evidence": [],
        "packet": {
            "packet_id": "first",
            "check_id": "a",
            "recorded_at": "old",
            "records": [
                {
                    "record_type": "Claim",
                    "claim_id": "first",
                    "text": "scoped assertion",
                    "decision_status": "insufficient",
                }
            ],
            "gaps": ["source missing"],
        },
    }
    newer = copy.deepcopy(base)
    newer["packet"].update(packet_id="next", check_id="b", recorded_at="new")
    newer["packet"]["records"][0]["claim_id"] = "next"
    assert AutonomousResearchGraph.progress_marker(base) == AutonomousResearchGraph.progress_marker(
        newer
    )
