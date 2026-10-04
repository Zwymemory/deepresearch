"""Invoked only by an isolated random-port Spring/PostgreSQL integration test."""

import asyncio
import json
import os
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import httpx

from deepresearch_workflow.agent_budget import SqlAgentLedger
from deepresearch_workflow.agent_completion import ensure_criteria
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_protocol import AgentRunBudget, AgentTask, ModelResult
from deepresearch_workflow.agent_question_segments import LEGACY_PLANNER_VERSION, PLANNER_VERSION
from deepresearch_workflow.agent_requirements import bind_requirements, freeze_requirements
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.evidence_client import HttpEvidenceBackend
from deepresearch_workflow.ports import RepositoryEventSink
from deepresearch_workflow.repository import PostgresWorkflowRepository
from deepresearch_workflow.settings import Settings


class Tokens:
    def __init__(self, header):
        self.header = header

    def authorization_header(self):
        return self.header


class ExactSourceVerifier:
    def __init__(self, config):
        self.config = config

    async def invoke(self, request):
        if request.name == "AgentDecision":
            result = ModelResult(
                value={
                    "action": "read_source",
                    "task_id": "task-main",
                    "source_id": "source-managed",
                    "reason": "Declare each original standard before reading its source",
                    "requirements": [
                        {
                            "text": text,
                            "question_spans": [{"start": 0, "end": len(self.config["objective"])}],
                            "kind": "factual",
                            "applicability": {
                                "subject": "API limits",
                                "version": {"status": "known", "value": "2.0"},
                                "valid_at": {
                                    "status": "unknown",
                                    "value": None,
                                    "reason": "Date not established",
                                },
                                "conditions": [],
                            },
                        }
                        for text in self.config["criteria"]
                    ],
                },
                input_tokens=30,
                output_tokens=30,
            )
            if "question_segments" in request.payload:
                result.value["planner_contract"] = PLANNER_VERSION
                units = request.payload["question_segments"]["segments"]
                for index, draft in enumerate(result.value["requirements"]):
                    draft.pop("question_spans")
                    draft["segment_ids"] = [units[index]["segment_id"]]
                    draft["applicability"].pop("conditions")  # Legal optional-schema default.
            return result
        assert request.name == "EvidenceCheck"
        proposals = []
        for claim in request.payload["claims"]:
            text = claim["text"]
            assert text in {
                "Document version is 2.0.",
                "Version 2.0 allows 100 requests per minute.",
                "Document version is 3.0.",
            }
            relations = []
            for evidence in request.payload["evidence"]:
                original = evidence["snapshot"]["text"]
                assert "Document version: 2.0" in original
                if "per minute." in text:
                    assert "100 requests per minute" in original
                relations.append(
                    {
                        "evidence_id": evidence["evidence_id"],
                        "relation": "refutes" if "3.0" in text else "supports",
                        "quote": original,
                        "reason": "The original declares the selected fact or contrary version.",
                    }
                )
            proposals.append(
                {"claim_id": claim["claim_id"], "relations": relations, "limitations": []}
            )
        return ModelResult(
            value={"claims": proposals, "follow_up_actions": []}, input_tokens=30, output_tokens=30
        )


async def main():
    cfg = json.loads(await asyncio.to_thread(Path(os.environ["COMPLETION_TEST_CONFIG"]).read_text))
    repository = PostgresWorkflowRepository(os.environ["TEST_AGENT_DATABASE_URL"])
    await repository.open()
    try:
        async with httpx.AsyncClient() as client:
            verifier = ExactSourceVerifier(cfg)

            async def offline_model_transport(request):
                body = json.loads(request.content)
                payload = json.loads(body["messages"][-1]["content"])
                name = "AgentDecision" if "question_segments" in payload else "EvidenceCheck"
                result = await verifier.invoke(SimpleNamespace(name=name, payload=payload))
                return httpx.Response(200, json={"model": "deepseek-flash", "choices": [{
                    "finish_reason": "stop", "message": {"content": json.dumps(result.value)}
                }], "usage": {"prompt_tokens": 30, "completion_tokens": 30}})

            model_client = httpx.AsyncClient(transport=httpx.MockTransport(offline_model_transport))
            graph = AutonomousResearchGraph(
                model=OpenAIAgentModel(Settings(
                    _env_file=None, openai_api_key="offline-fixture", model_name="deepseek-flash",
                    openai_base_url="https://api.deepseek.com",
                    agent_result_transport="deepseek_json_object",
                ), model_client) if cfg["mode"] == "segments-complete" else verifier,
                tools=None,
                repository=repository,
                ledger=SqlAgentLedger(repository),
                evidence=HttpEvidenceBackend(
                    client=client, java_base_url=cfg["url"], service_tokens=Tokens(cfg["token"])
                ),
                events=RepositoryEventSink(repository),
                budget=AgentRunBudget.model_validate(cfg["budget"]),
                claim_token=cfg["claim"],
            )
            state = {
                "run_id": cfg["run"],
                "grant_id": cfg["grant"],
                "requested_scopes": ["kb_search", "read_source", "check_claims"],
                "claim_token": cfg["claim"],
                "deadline_at": (datetime.now(UTC) + timedelta(seconds=150)).isoformat(),
                "question": cfg["objective"],
            }
            state.update(await graph.initialize(state))
            if cfg["mode"] != "segments-complete":
                # Existing fixture cases continue exercising saved v1 declarations.
                state["planner_contract"] = LEGACY_PLANNER_VERSION
            state["tasks"] = [
                AgentTask(
                    task_id="task-main",
                    objective=cfg["objective"],
                    acceptance_criteria=cfg["criteria"],
                    status="running",
                    plan_version=1,
                ).model_dump(mode="json")
            ]
            state["candidates"] = [{"source_id": "source-managed"}]
            ensure_criteria(state["run_id"], state["tasks"])
            if cfg["mode"] != "legacy":
                state.update(await graph.decide(state))
                manifest = freeze_requirements(
                    state["run_id"], state["question"],
                    [r.model_dump(mode="json") for r in graph.planning_decision(
                        state["decision"], state
                    ).requirements],
                )
                criteria = {
                    row["text"]: row["criterion_id"] for row in state["tasks"][0]["criteria"]
                }
                bindings = bind_requirements(
                    manifest,
                    state["tasks"],
                    [
                        {
                            "requirement_id": row["requirement_id"],
                            "criterion_id": criteria[row["text"]],
                        }
                        for row in manifest["requirements"]
                    ],
                )
                await graph.ledger.save_tasks(state["run_id"], cfg["claim"], state["tasks"])
                await graph.ledger.save_requirements(
                    state["run_id"],
                    cfg["claim"],
                    manifest,
                    bindings,
                    state["tasks"],
                    "model:agent:decision-1",
                )
                state["original_requirements"] = manifest
                state["requirement_bindings"] = bindings
            else:
                state["decision_steps"] = 1
                state["decision"] = {
                    "action": "read_source",
                    "task_id": "task-main",
                    "source_id": "source-managed",
                    "reason": "Replay a legacy task without an original requirement manifest",
                }
            state.update(await graph.act(state))
            assert state["evidence"]
            unknown = {"status": "unknown", "value": None, "reason": "Date not established"}
            scope = {
                "subject": "API limits",
                "version": {"status": "known", "value": "2.0"},
                "valid_at": unknown,
                "conditions": [],
            }
            texts = (
                ["Document version is 3.0."]
                if cfg["mode"] == "refuted"
                else ["Document version is 2.0."]
            )
            if cfg["mode"] in {"complete", "segments-complete"}:
                texts.append("Version 2.0 allows 100 requests per minute.")
            state["decision_steps"] = 2
            state["decision"] = {
                "action": "check_claims",
                "task_id": "task-main",
                "claims": [
                    {"text": text, "kind": "factual", "applicability": scope} for text in texts
                ],
                "criterion_bindings": []
                if cfg["mode"] == "legacy"
                else [
                    {
                        "criterion_id": state["tasks"][0]["criteria"][index]["criterion_id"],
                        "claim_index": index,
                    }
                    for index in range(len(texts))
                ],
                "reason": "Check only the explicitly bound standards",
            }
            if cfg["mode"] == "segments-complete":
                state["decision"]["planner_contract"] = PLANNER_VERSION
            state.update(await graph.act(state))
            expected = (
                "SUCCEEDED" if cfg["mode"] in {"complete", "segments-complete", "refuted"}
                else "INSUFFICIENT_EVIDENCE"
            )
            assert (state["tasks"][0]["status"] == "done") == (expected == "SUCCEEDED"), state[
                "observations"
            ]
            state["decision_steps"] = 3
            state["decision"] = {
                "action": "finish" if expected == "SUCCEEDED" else "stop_with_gaps",
                "reason": "Request the independently verified whole report",
                "gaps": []
                if expected == "SUCCEEDED"
                else ["Original standards remain uncovered by current bound checks"],
            }
            if cfg["mode"] == "segments-complete":
                state["decision"]["planner_contract"] = PLANNER_VERSION
            result = await graph.act(state)
            assert result["final_status"] == expected, result
            await asyncio.to_thread(
                Path(cfg["output"]).write_text,
                json.dumps(result, ensure_ascii=False, indent=2, default=str),
            )
            print(
                json.dumps(
                    {
                        "mode": cfg["mode"],
                        "criteria": [
                            {"id": c["criterion_id"], "text": c["text"], "status": c["status"]}
                            for c in state["tasks"][0]["criteria"]
                        ],
                        "task_status": state["tasks"][0]["status"],
                        "terminal_status": result["final_status"],
                        "report_status": result["report"]["report_status"],
                    },
                    ensure_ascii=False,
                )
            )
            await model_client.aclose()
    finally:
        await repository.close()


asyncio.run(main())
