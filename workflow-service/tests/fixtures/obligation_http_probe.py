"""New contract through random-port native HTTP/SQL; all verifier I/O is substituted."""

import asyncio
import copy
import json
import os
from datetime import UTC, datetime, timedelta

import httpx
from anyio import Path
from langgraph.checkpoint.memory import InMemorySaver
from psycopg import AsyncConnection
from psycopg.types.json import Jsonb

from deepresearch_workflow.agent_budget import SqlAgentLedger
from deepresearch_workflow.agent_completion import ensure_criteria
from deepresearch_workflow.agent_decision_instruction import POLICY_VERSION
from deepresearch_workflow.agent_obligations import (
    CLAIMS_VERSION,
    CONTINUATION_VERSION,
    PLANNER_VERSION,
)
from deepresearch_workflow.agent_protocol import AgentRunBudget, AgentTask, ModelResult
from deepresearch_workflow.agent_question_segments import question_segments
from deepresearch_workflow.agent_requirements import bind_requirements, freeze_requirements
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.domain import ToolEvidence, ToolExecutionResult
from deepresearch_workflow.evidence_client import HttpEvidenceBackend
from deepresearch_workflow.ports import RepositoryEventSink
from deepresearch_workflow.repository import PostgresWorkflowRepository


class Tokens:
    def __init__(self, header):
        self.header = header

    def authorization_header(self):
        return self.header


class Verifier:
    def __init__(self, cfg):
        self.cfg, self.calls = cfg, []

    async def invoke(self, request):
        self.calls.append(request)
        cfg = self.cfg
        if (
            request.name == "AgentDecision"
            and self.cfg["mode"].startswith("obligations-auto")
            and request.payload.get("original_requirements")
        ):
            payload = request.payload
            fields = {
                "planner_contract": PLANNER_VERSION,
                "claims_contract": CLAIMS_VERSION,
                "continuation_contract": CONTINUATION_VERSION,
                "requirements_ref": payload["original_requirements"]["manifest_sha256"],
            }
            unread = [
                c
                for c in payload["candidates"]
                if c["source_id"] not in {e["source"]["source_id"] for e in payload["evidence"]}
            ]
            if unread:
                value = {
                    **fields,
                    "action": "read_source",
                    "source_id": unread[-1]["source_id"],
                    "reason": "Read authorized current candidate",
                }
            elif (
                cfg["mode"] == "obligations-auto-mixed"
                and "web" not in payload["actual_read_source_kinds"]
            ):
                assert payload["actual_read_source_kinds"] == ["knowledge"]
                assert payload["candidates"][0]["origin"]["tool"] == "kb_search"
                value = {
                    **fields,
                    "action": "search",
                    "tool": "web_search",
                    "query": "Missing original web policy",
                    "reason": "Knowledge read does not cover the requested web original",
                }
            elif payload["observations"][-1].get("action") == "check_claims":
                value = {
                    **fields,
                    "action": "search",
                    "tool": "kb_search",
                    "query": "Original source policy exact named original",
                    "reason": "Wrong named source requires changed targeted search",
                }
            else:
                bindings = {
                    b["criterion_id"]: b["requirement_id"] for b in payload["requirement_bindings"]
                }
                task = payload["tasks"][0]
                texts = {
                    self.cfg["criteria"][0]: "Document version is 2.0.",
                    self.cfg["criteria"][1]: "Version 2.0 allows 100 requests per minute.",
                }
                value = {
                    **fields,
                    "action": "check_claims",
                    "task_id": task["task_id"],
                    "reason": "Check referenced answers with actual originals",
                    "claims": [
                        {
                            "text": texts[c["text"]],
                            "criterion_id": c["criterion_id"],
                            "requirement_id": bindings[c["criterion_id"]],
                        }
                        for c in task["criteria"]
                    ],
                }
        elif request.name == "AgentDecision":
            units = question_segments(cfg["objective"])["segments"]
            unknown = {
                "status": "unknown",
                "value": None,
                "reason": "Not independently established",
            }
            value = {
                "planner_contract": PLANNER_VERSION,
                "claims_contract": CLAIMS_VERSION,
                "action": "read_source",
                "task_id": "task-main",
                "source_id": "source-managed",
                "reason": "Controlled initial declaration and authorized existing candidate",
                "obligations": [
                    {
                        "text": text,
                        "kind": "factual",
                        "segment_ids": [units[i + 1]["segment_id"]],
                        "applicability": {
                            "subject": "API limits",
                            "version": {"status": "known", "value": "2.0"},
                            "valid_at": unknown,
                            "conditions": [],
                        },
                    }
                    for i, text in enumerate(cfg["criteria"])
                ],
                "constraints": [
                    {
                        "role": "source",
                        "segment_ids": [units[0]["segment_id"]],
                        "obligation_indices": [0, 1],
                    },
                    {
                        "role": "output",
                        "segment_ids": [units[3]["segment_id"]],
                        "obligation_indices": [0, 1],
                    },
                ],
            }
            if cfg["mode"] == "obligations-partition":
                value["constraints"][0]["role"] = "output"
            if cfg["mode"].startswith("obligations-auto"):
                value.pop("source_id")
                value.pop("task_id")
                value.update(action="search", tool="kb_search", query="Original source policy")
        else:
            assert (
                request.name == "EvidenceCheck"
                and request.payload["protocol_version"] == "evidence-check/3"
            )
            assert request.payload["original_context"]["question"] == cfg["objective"]
            async with httpx.AsyncClient() as viewer:
                pending = await viewer.get(
                    cfg["url"] + "/api/research/workflows/" + cfg["run"] + "/evidence",
                    headers={"Authorization": cfg["viewer_token"]},
                )
            assert pending.status_code == 200, pending.text
            assert pending.json()["availability"] == "RECORDED_INCOMPLETE"
            assert any(c["status"] == "AWAITING_MODEL" for c in pending.json()["checks"])
            proposals = []
            for claim in request.payload["claims"]:
                answer = "answers"
                if cfg["mode"] == "obligations-irrelevant":
                    answer = "irrelevant"
                elif cfg["mode"] == "obligations-absence":
                    answer = "absence_only"
                proposals.append(
                    {
                        "claim_id": claim["claim_id"],
                        "answer_alignment": answer,
                        "limitations": [],
                        "relations": [
                            {
                                "evidence_id": e["evidence_id"],
                                "relation": "supports",
                                "quote": e["snapshot"]["text"],
                                "reason": ("Controlled exact original assessment. " * 20)
                                if cfg["mode"] == "obligations-auto-mixed"
                                else "Controlled exact original assessment",
                                "source_alignment": "wrong_source"
                                if cfg["mode"] == "obligations-source"
                                or e["source"]["source_id"] == "source-managed-wrong"
                                else "qualifies",
                            }
                            for e in request.payload["evidence"]
                        ],
                    }
                )
            value = {
                "claims": proposals,
                "follow_up_actions": [],
                "planning_alignment": {
                    "status": "incomplete"
                    if cfg["mode"] == "obligations-partition"
                    else "complete",
                    "reason": ("Controlled full original question classification. " * 18)
                    if cfg["mode"] == "obligations-auto-mixed"
                    else "Controlled full original question classification",
                },
            }
        if request.name == "EvidenceCheck" and cfg["mode"] == "obligations-auto-mixed":
            assert request.max_output_tokens == 4096 and len(json.dumps(value)) > 4096
            return ModelResult(value=value, input_tokens=30, output_tokens=1536)
        return ModelResult(value=value, input_tokens=30, output_tokens=30)


class BoundaryBackend(HttpEvidenceBackend):
    async def check(self, state, task, call_id, claims, gateway):
        refs = [
            {k: p[k] for k in ("requirement_id", "criterion_id")} for p in state["claim_references"]
        ]
        payload = {
            "identifiers": self.identifiers(state, task, call_id),
            "claims": claims,
            "evidence_ids": [e["evidence_id"] for e in state["evidence"]],
            "dispute_round": 0,
            "claims_contract": CLAIMS_VERSION,
            "requirements_ref": state["original_requirements"]["manifest_sha256"],
            "claim_references": refs,
        }
        variants = []
        legacy = copy.deepcopy(payload)
        for key in ("claims_contract", "requirements_ref", "claim_references"):
            legacy.pop(key)
        variants.append((legacy, "CHECK_CONTRACT_REQUIRED"))
        foreign = copy.deepcopy(payload)
        foreign["claim_references"][0]["requirement_id"] = "foreign"
        variants.append((foreign, "REQUIREMENT_CLAIM_REFERENCE_INVALID"))
        stale = copy.deepcopy(payload)
        stale["requirements_ref"] = "0" * 64
        variants.append((stale, "ORIGINAL_CONTEXT_INVALID"))
        override = copy.deepcopy(payload)
        override["claims"][0]["kind"] = "recommendation"
        variants.append((override, "REQUIREMENT_CLAIM_SCOPE_CHANGED"))
        duplicate = copy.deepcopy(payload)
        duplicate["claim_references"][1] = duplicate["claim_references"][0]
        variants.append((duplicate, "REQUIREMENT_CLAIM_REFERENCE_INVALID"))
        for variant, expected in variants:
            rejection = await self.post("/internal/agent/evidence/checks/prepare", variant)
            assert rejection.get("errorCode") == expected, rejection
        return await super().check(state, task, call_id, claims, gateway)


class ControlledSearch:
    """Substitute only retrieval transport, recording its authoritative native MCP receipt."""

    def __init__(self, repo, cfg):
        self.repo, self.cfg, self.calls = repo, cfg, []

    async def execute(self, request):
        self.calls.append(request.task.query)
        web = request.task.tool == "web_search"
        wrong = self.cfg["mode"] == "obligations-auto-recovery" and len(self.calls) == 1
        identity = "source-web" if web else "source-managed-wrong" if wrong else "source-managed"
        body = {
            "success": True,
            "tool": request.task.tool,
            "evidence": [
                {
                    "evidenceId": identity,
                    "uriOrChunkKey": "https://docs.example.test/policy"
                    if web
                    else "ragflow:dataset-http:document-http:chunk-old",
                    "title": "News update" if wrong else "Original source policy",
                }
            ],
        }
        # Native retrieval substitute uses the isolated native-owner test connection;
        # the real sidecar role deliberately cannot forge MCP authority columns.
        async with await AsyncConnection.connect(
            os.environ["TEST_NATIVE_FIXTURE_DATABASE_URL"]
        ) as conn:
            await conn.execute(
                """INSERT INTO agent_workflow_tool_receipt(
                run_id,call_id,task_id,tool_name,request_fingerprint,
                status,safe_result,completed_at,claim_token,
                mcp_execution_status,mcp_safe_result,mcp_claim_token,mcp_started_at,mcp_completed_at)
                VALUES (%s,%s,%s,%s,%s,'COMPLETED','{}',now(),%s::uuid,
                'COMPLETED',%s,%s::uuid,now(),now())""",
                (
                    request.run_id,
                    request.call_id,
                    request.task.task_id,
                    request.task.tool,
                    "0" * 64,
                    self.cfg["claim"],
                    Jsonb(body),
                    self.cfg["claim"],
                ),
            )
        return ToolExecutionResult(
            call_id=request.call_id,
            evidence=[
                ToolEvidence(
                    source_id=identity,
                    content="Controlled retrieval lead",
                    source_uri="https://docs.example.test/policy" if web else None,
                )
            ],
        )


async def autonomous(repo, client, cfg):
    model = Verifier(cfg)
    tools = ControlledSearch(repo, cfg)
    graph = AutonomousResearchGraph(
        model=model,
        tools=tools,
        repository=repo,
        ledger=SqlAgentLedger(repo),
        evidence=HttpEvidenceBackend(
            client=client, java_base_url=cfg["url"], service_tokens=Tokens(cfg["token"])
        ),
        events=RepositoryEventSink(repo),
        budget=AgentRunBudget.model_validate(cfg["budget"]),
        claim_token=cfg["claim"],
    )
    initial = {
        "run_id": cfg["run"],
        "question": cfg["objective"],
        "grant_id": cfg["grant"],
        "requested_scopes": ["kb_search", "read_source", "check_claims"]
        + (["web_search"] if cfg["mode"] == "obligations-auto-mixed" else []),
        "deadline_at": (datetime.now(UTC) + timedelta(seconds=150)).isoformat(),
    }
    result = await graph.compile(checkpointer=InMemorySaver()).ainvoke(
        initial, {"configurable": {"thread_id": cfg["run"]}, "recursion_limit": 40}
    )
    assert (
        result["planner_contract"] == PLANNER_VERSION
        and result["continuation_contract"] == CONTINUATION_VERSION
    )
    assert result["final_status"] == "SUCCEEDED", result
    assert len(result["original_requirements"]["requirements"]) == 2
    if cfg["mode"] == "obligations-auto-mixed":
        assert len(tools.calls) == 2 and len(model.calls) == 6
        assert {e["source"]["kind"] for e in result["evidence"]} == {"knowledge", "web"}
        assert result["requirement_coverage"]["complete"]
        assert all(c["status"] == "resolved" for t in result["tasks"] for c in t["criteria"])
        assert result["instruction_policy"] == POLICY_VERSION
    elif cfg["mode"] == "obligations-auto-recovery":
        assert len(tools.calls) == 2 and tools.calls[0] != tools.calls[1]
        assert len(model.calls) == 8 and len(result["evidence"]) == 2
        assert any(
            o.get("action") == "check_claims" and o.get("gaps") for o in result["observations"]
        )
    else:
        assert len(tools.calls) == 1 and len(model.calls) == 4
    assert all(
        r.max_output_tokens == (4096 if r.name == "EvidenceCheck" else 1024) for r in model.calls
    )
    await Path(cfg["output"]).write_text(
        json.dumps(result, ensure_ascii=False, indent=2, default=str)
    )
    print(
        json.dumps(
            {
                "mode": cfg["mode"],
                "default_initialize": True,
                "model_calls": len(model.calls),
                "searches": len(tools.calls),
            }
        )
    )


async def main():
    cfg = json.loads(await Path(os.environ["COMPLETION_TEST_CONFIG"]).read_text())
    repo = PostgresWorkflowRepository(os.environ["TEST_AGENT_DATABASE_URL"])
    await repo.open()
    try:
        async with httpx.AsyncClient() as client:
            if cfg["mode"].startswith("obligations-auto"):
                await autonomous(repo, client, cfg)
                return
            model = Verifier(cfg)
            graph = AutonomousResearchGraph(
                model=model,
                tools=None,
                repository=repo,
                ledger=SqlAgentLedger(repo),
                evidence=BoundaryBackend(
                    client=client, java_base_url=cfg["url"], service_tokens=Tokens(cfg["token"])
                ),
                events=RepositoryEventSink(repo),
                budget=AgentRunBudget.model_validate(cfg["budget"]),
                claim_token=cfg["claim"],
            )
            state = {
                "run_id": cfg["run"],
                "question": cfg["objective"],
                "grant_id": cfg["grant"],
                "claim_token": cfg["claim"],
                "requested_scopes": ["kb_search", "read_source", "check_claims"],
                "deadline_at": (datetime.now(UTC) + timedelta(seconds=150)).isoformat(),
            }
            state.update(await graph.initialize(state))
            state["tasks"] = [
                AgentTask(
                    task_id="task-main",
                    objective=cfg["objective"],
                    acceptance_criteria=cfg["criteria"],
                    plan_version=1,
                ).model_dump(mode="json")
            ]
            state["candidates"] = [{"source_id": "source-managed"}]
            ensure_criteria(state["run_id"], state["tasks"])
            state.update(await graph.decide(state))
            drafts = [
                r.model_dump(mode="json")
                for r in graph.planning_decision(state["decision"], state).requirements
            ]
            manifest = freeze_requirements(state["run_id"], state["question"], drafts)
            criteria = {c["text"]: c["criterion_id"] for c in state["tasks"][0]["criteria"]}
            associations = bind_requirements(
                manifest,
                state["tasks"],
                [
                    {"requirement_id": r["requirement_id"], "criterion_id": criteria[r["text"]]}
                    for r in manifest["requirements"]
                ],
            )
            await graph.ledger.save_tasks(state["run_id"], cfg["claim"], state["tasks"])
            await graph.ledger.save_requirements(
                state["run_id"],
                cfg["claim"],
                manifest,
                associations,
                state["tasks"],
                "model:agent:decision-1",
            )
            state.update(original_requirements=manifest, requirement_bindings=associations)
            state["decision"].pop("obligations")
            state["decision"].pop("constraints")
            state["decision"].update(
                continuation_contract=CONTINUATION_VERSION,
                requirements_ref=manifest["manifest_sha256"],
            )
            state.update(await graph.act(state))
            assert state["evidence"]
            by_criterion = {b["criterion_id"]: b["requirement_id"] for b in associations}
            texts = ["Document version is 2.0.", "Version 2.0 allows 100 requests per minute."]
            if cfg["mode"] == "obligations-irrelevant":
                texts = ["The source is a text document.", "The source contains a version line."]
            elif cfg["mode"] == "obligations-absence":
                texts = [
                    "The source does not mention unrelated registration rules.",
                    "The source does not mention unrelated transfer rules.",
                ]
            state.update(
                decision_steps=2,
                action_sequence=2,
                decision={
                    "planner_contract": PLANNER_VERSION,
                    "claims_contract": CLAIMS_VERSION,
                    "continuation_contract": CONTINUATION_VERSION,
                    "requirements_ref": manifest["manifest_sha256"],
                    "action": "check_claims",
                    "task_id": "task-main",
                    "claims": [
                        {
                            "text": text,
                            "criterion_id": c["criterion_id"],
                            "requirement_id": by_criterion[c["criterion_id"]],
                        }
                        for text, c in zip(texts, state["tasks"][0]["criteria"], strict=True)
                    ],
                    "reason": "Controlled referenced claims without copied immutable scopes",
                },
            )
            state.update(await graph.act(state))
            expected = (
                "SUCCEEDED" if cfg["mode"] == "obligations-complete" else "INSUFFICIENT_EVIDENCE"
            )
            assert (state["tasks"][0]["status"] == "done") == (expected == "SUCCEEDED"), state[
                "observations"
            ]
            assert len(model.calls) == 2 and model.calls[1].max_output_tokens == 1024
            state.update(
                decision_steps=3,
                action_sequence=3,
                decision={
                    "planner_contract": PLANNER_VERSION,
                    "claims_contract": CLAIMS_VERSION,
                    "continuation_contract": CONTINUATION_VERSION,
                    "requirements_ref": manifest["manifest_sha256"],
                    "action": "finish" if expected == "SUCCEEDED" else "stop_with_gaps",
                    "gaps": []
                    if expected == "SUCCEEDED"
                    else ["Original question/source alignment remains unresolved"],
                    "reason": "Request native whole-report adjudication",
                },
            )
            result = await graph.act(state)
            assert result["final_status"] == expected, result
            await Path(cfg["output"]).write_text(
                json.dumps(result, ensure_ascii=False, indent=2, default=str)
            )
            print(
                json.dumps(
                    {
                        "mode": cfg["mode"],
                        "terminal": expected,
                        "model_calls": len(model.calls),
                        "native_negative_prepares": 5,
                    }
                )
            )
    finally:
        await repo.close()


asyncio.run(main())
