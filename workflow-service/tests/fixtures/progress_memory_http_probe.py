"""Disposable M1 HTTP/SQL demonstration, controlled provider unless explicitly live."""

import asyncio
import json
import os
import sys

import httpx
from anyio import Path
from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

from deepresearch_workflow.agent_budget import SqlAgentLedger
from deepresearch_workflow.agent_model import OpenAIAgentModel
from deepresearch_workflow.agent_runtime import AutonomousResearchGraph
from deepresearch_workflow.control_plane import HttpControlPlaneClient
from deepresearch_workflow.domain import ToolExecutionResult
from deepresearch_workflow.evidence_client import HttpEvidenceBackend
from deepresearch_workflow.ports import RepositoryEventSink
from deepresearch_workflow.progress_memory import HttpProgressMemoryClient
from deepresearch_workflow.project_summary import ProjectSummaryCoordinator, SqlProjectSummaryStore
from deepresearch_workflow.repository import PostgresWorkflowRepository, checkpoint_pool
from deepresearch_workflow.runner import WorkflowRunner
from deepresearch_workflow.settings import Settings


class Tokens:
    def __init__(self, token, config_path=None):
        self.token, self.config_path = token, config_path

    def authorization_header(self):
        if self.config_path:
            # Native test owner refreshes its short-lived service token atomically.
            from pathlib import Path as LocalPath
            return json.loads(LocalPath(self.config_path).read_text())["token"]
        return self.token


class NoTools:
    async def execute(self, request):
        raise AssertionError("M1 controlled scenario must not retrieve or measure invented data")


class EmptyTools:
    """Explicit synthetic empty retrieval: no external search/KB access or benchmark data."""

    async def execute(self, request):
        return ToolExecutionResult(call_id=request.call_id, evidence=[])


def controlled_decision(payload):
    fields = {"planner_contract": "agent-planning-obligations/3",
              "claims_contract": "agent-obligation-claims/1"}
    if payload.get("original_requirements"):
        return {**fields, "continuation_contract": "agent-frozen-requirements/2",
                "requirements_ref": payload["original_requirements"]["manifest_sha256"],
                "action": "stop_with_gaps", "reason": "无基准测量工具\uff0c保留延迟待办和效果争议",
                "gaps": ["尚缺同数据集、同硬件的延迟测量\uff1b不得编造数值",
                         "历史效果结论仍有争议\uff0c不能作为当前事实"]}
    memory = payload.get("prior_progress")
    text = "在同数据集、同硬件下测量延迟\uff1b保留效果争议" if memory else "澄清要继续的研究目标"
    return {**fields, "action": "revise_plan", "reason": "承接历史未完成延迟待办" if memory
            else "没有历史项目\uff0c需要明确目标", "obligations": [{
                "text": text, "segment_ids": [r["segment_id"]
                    for r in payload["question_segments"]["segments"]], "kind": "factual",
                "applicability": {"subject": "方案 A 与 B 的延迟",
                    "version": {"status": "unknown", "value": None, "reason": "未指定版本"},
                    "valid_at": {"status": "unknown", "value": None, "reason": "无测量时间"},
                    "conditions": ["同数据集", "同硬件", "没有测量数据不得编造数值"]}}],
            "constraints": [], "tasks": [{"objective": text,
                "acceptance_criteria": ["可追溯的实测数据或明确缺口\uff0c不把争议当结论"]}]}


async def main():
    cfg = json.loads(await Path(os.environ["MEMORY_TEST_CONFIG"]).read_text())
    live = cfg.get("provider") == "live-deepseek"
    captures = []
    output = Path(cfg["output"])
    repo = PostgresWorkflowRepository(os.environ["TEST_AGENT_DATABASE_URL"])
    await repo.open()
    connections = checkpoint_pool(os.environ["TEST_AGENT_DATABASE_URL"], "langgraph")
    await connections.open(wait=True)
    saver = AsyncPostgresSaver(connections)
    await saver.setup()
    try:
        async with (
            httpx.AsyncClient(timeout=40) as native,
            httpx.AsyncClient(timeout=90) as provider,
        ):
            async def transport(wire):
                body = json.loads(wire.content)
                payload = json.loads(body["messages"][1]["content"])
                capture = {"provider": cfg.get("provider", "controlled"), "wire": body}
                captures.append(capture)
                # Store no Authorization headers, private keys, or provider raw failure body.
                await output.write_text(json.dumps({"calls": captures},
                                                  ensure_ascii=False, indent=2))
                if live:
                    secrets = json.loads(await Path(cfg["private_credentials"]).read_text())
                    key = secrets["DEEPSEEK_API_KEY"]
                    response = await provider.post("https://api.deepseek.com/chat/completions",
                        content=wire.content, headers={"Authorization": "Bearer " + key,
                                                      "Content-Type": "application/json"})
                    data = response.json()
                    capture.update(status=response.status_code,
                                   usage=data.get("usage"), model=data.get("model"))
                    if response.status_code == 200:
                        capture["result"] = data["choices"][0]
                    await output.write_text(json.dumps({"calls": captures},
                                                      ensure_ascii=False, indent=2))
                    return httpx.Response(response.status_code, json=data)
                result = ({"selected_segment_ids": [payload["source_segments"][0]["segment_id"]]}
                          if "source_segments" in payload else controlled_decision(payload))
                capture["result"] = result
                return httpx.Response(200, json={"model": "deepseek-flash",
                    "choices": [{"finish_reason": "stop", "message": {
                        "content": json.dumps(result, ensure_ascii=False)}}],
                    "usage": {"prompt_tokens": 120, "completion_tokens": 100}})

            async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as model_client:
                tokens = Tokens(cfg["token"], os.environ["MEMORY_TEST_CONFIG"]
                                if "--serve" in sys.argv else None)
                memory = HttpProgressMemoryClient(client=native, java_base_url=cfg["url"],
                                                  service_tokens=tokens)
                model = OpenAIAgentModel(Settings(_env_file=None, openai_api_key="transport-owned",
                    openai_base_url="https://api.deepseek.com", model_name="deepseek-flash",
                    agent_result_transport="deepseek_json_object"), model_client)
                ledger = SqlAgentLedger(repo)

                sink = RepositoryEventSink(repo)

                class InterruptAfterSettledDecision:
                    async def emit(self, event, claim):
                        if event.event_type == "AGENT_ACTION_SELECTED":
                            operation.cancel()  # Interrupt runner ownership, as shutdown does.
                            await asyncio.Event().wait()
                        await sink.emit(event, claim)

                def graph(claim, budget):
                    return AutonomousResearchGraph(model=model,
                        tools=EmptyTools() if live else NoTools(), repository=repo,
                        ledger=ledger, evidence=HttpEvidenceBackend(client=native,
                            java_base_url=cfg["url"], service_tokens=tokens),
                        events=(InterruptAfterSettledDecision()
                                if cfg.get("interrupt_after_decision") else sink),
                        budget=budget, claim_token=claim,
                        progress_memory=memory,
                        project_summaries=ProjectSummaryCoordinator(SqlProjectSummaryStore(repo))).compile(checkpointer=saver)

                runner = WorkflowRunner(repository=repo,
                    control_plane=HttpControlPlaneClient(client=native,
                        java_base_url=cfg["url"], service_tokens=tokens, timeout_seconds=20),
                    graph_factory=graph, settings=Settings(_env_file=None, runner_enabled=False),
                    agent_ledger_factory=lambda _: ledger, progress_memory=memory)
                if "--serve" in sys.argv:
                    await runner.serve_forever()
                else:
                    run = await repo.claim_next("m1-isolated-probe", 120)
                    assert run is not None and run.run_id == cfg["run"]
                    operation = asyncio.create_task(runner.run_claimed(run))
                    try:
                        await operation
                    except asyncio.CancelledError:
                        if not cfg.get("interrupt_after_decision"):
                            raise
                        await output.write_text(json.dumps({"interrupted": True,
                                                            "calls": captures}))
                        return
                    view = await native.get(cfg["url"] + "/api/research/workflows/" + run.run_id,
                                            headers={"Authorization": cfg["viewer_token"]})
                    view.raise_for_status()
                    result = view.json()
                    await output.write_text(json.dumps({"calls": captures, "status": result},
                                                ensure_ascii=False, indent=2))
                    expected = cfg.get("expected_status", "INSUFFICIENT_EVIDENCE")
                    assert result["status"] == expected, result
                    if cfg.get("expected_error"):
                        assert result["errorCode"] == cfg["expected_error"] and not captures
                    else:
                        assert all(c["wire"]["messages"] for c in captures)
    finally:
        await connections.close()
        await repo.close()


if __name__ == "__main__":
    asyncio.run(main())
