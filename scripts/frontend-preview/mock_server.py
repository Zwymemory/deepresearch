#!/usr/bin/env python3
"""DeepResearch 前端本地预览服务器（仅合成演示数据）。

提供 src/main/resources/static/demo.html，并在同源模拟页面使用的 REST/SSE 契约，
用于在不启动 Java、不调用模型、网页搜索或 RAGFlow 的情况下预览各种界面状态。

    python3 scripts/frontend-preview/mock_server.py            # http://127.0.0.1:8090/demo.html
    python3 scripts/frontend-preview/mock_server.py --port 8091

场景通过页面地址的 ?scenario= 选择（API 请求的同源 Referer 会携带它）：
    success（默认）| insufficient | failed | budget | slow | disconnect | unknown | noweb | langgraph | evidence-disabled

运行归属于创建它的 Bearer Token：换一个 Token 读取或取消会得到 404，用于验证前端的身份隔离。

所有标题、链接、正文均为合成内容，页面会显示“本地演示数据”标识。
这里的成功不代表真实后端、模型或检索质量。
"""
from __future__ import annotations

import argparse
import json
import threading
import time
import uuid
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[2]
DEMO_HTML = ROOT / "src" / "main" / "resources" / "static" / "demo.html"
FAKE_TOKEN = "local-preview-token-not-a-real-credential"

RIBBON = """
<style>@media (max-width:720px){#previewRibbon{font-size:11px!important;bottom:8px!important}}</style>
<div id="previewRibbon" role="note" style="position:fixed;left:50%;bottom:14px;z-index:95;transform:translateX(-50%);
max-width:calc(100vw - 32px);padding:6px 14px;border-radius:999px;background:#2b2140;color:#fff6e0;
font:600 12.5px/1.5 -apple-system,'PingFang SC',sans-serif;box-shadow:0 10px 30px -10px rgba(0,0,0,.4);
pointer-events:none;text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis">本地演示数据 · 预览服务器模拟 API，未连接真实后端或模型 · 场景：<span id="previewScenario"></span></div>
<script>document.getElementById("previewScenario").textContent=(new URLSearchParams(location.search).get("scenario")||"success");</script>
"""

KB_DOC = {
    "sourceId": "kb:demo-architecture:chunk-07",
    "kind": "KNOWLEDGE_CHUNK",
    "title": "【演示】architecture-and-trust-boundaries.md",
    "excerpt": "[UNTRUSTED_DATA_BEGIN source=knowledge-base]\n## 演示片段\n\n这是一段由本地预览服务器生成的合成知识库摘录，用于展示知识库来源卡片的标题、摘录与折叠记录。"
               "它说明 Java 控制面负责身份、工具权限与最终来源边界，Worker 只能通过受限凭证调用获准工具。"
               "\n\n[UNTRUSTED_DATA_END source=knowledge-base]",
}
KB_DOC_2 = {
    "sourceId": "kb:demo-sse-replay:chunk-02",
    "kind": "KNOWLEDGE_CHUNK",
    "title": "【演示】sse-durable-replay.md",
    "excerpt": "合成摘录：持久事件按单调递增的游标保存；客户端断线后携带 Last-Event-ID 重新连接，服务端从下一条事件开始重放，"
               "因此刷新页面不会重新创建任务。此段文字仅用于界面预览。",
}
WEB_DOC = {
    "sourceId": "web:demo:" + "a" * 64,
    "kind": "WEB_SEARCH_SNAPSHOT",
    "title": "【演示】Reciprocal Rank Fusion 简介（合成网页摘要）",
    "url": "https://example.com/deepresearch-preview/reciprocal-rank-fusion",
    "excerpt": "导航\n\n合成网页摘要：RRF 将多个排序列表中的名次转换为 1/(k+rank) 并求和，常见取值 k=60。"
               "它不依赖各检索器分数的量纲，因此适合融合向量召回与 BM25。此摘要为本地演示数据，不是真实搜索结果。",
}

SUCCESS_ANSWER = """> 本地演示数据：以下报告由预览服务器合成，用于展示排版、引用与状态，不代表真实模型输出或研究结论。

## 结论概览

本项目把**检索质量**和**运行可靠性**分开处理：混合检索负责找全证据，持久工作流负责在断线或崩溃后继续执行 [来源1]。

### 检索如何融合

1. 向量召回与 BM25 各自给出排序列表，再用 RRF 按名次融合 [来源3]。
2. 在 `k=60` 时，第 1 名与第 3 名的贡献分别约为 0.0164 与 0.0159，二者之和约 0.0323 [来源3]。
3. 知识库片段会保留文档名与摘录，便于核对，而不会伪造公网链接 [来源1]。

### 断线后如何恢复

事件以单调游标持久化，客户端带上 `Last-Event-ID` 重连即可从下一条继续 [来源2]。这意味着刷新页面**不会重新创建任务** [来源2]。

---

仍需人工判断的是：演示数据中的数值只用于展示计算器结果的排版。"""

PARTIAL_ANSWER = """> 本地演示数据：以下报告由预览服务器合成。

## 已支持的部分

持久事件可以通过游标重放，断线后不会重新创建任务 [来源1]。

## 尚未核实的部分

关于“生产环境中的真实断线率”，现有证据没有给出可核对的数字，系统保留为待查事项，没有自行补全。"""


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def ev(at, role, type_, payload=None, status=None, stage=None, progress=None, task=None):
    return {"at": at, "role": role, "type": type_, "payload": payload or {}, "status": status,
            "stage": stage, "progress": progress, "taskId": task}


def workflow_timeline(scenario: str, tools: list[str]):
    use_web = "web_search" in tools
    use_kb = "kb_search" in tools or not use_web
    t = [
        ev(0.0, "SYSTEM", "QUEUED", {}, "QUEUED", "QUEUED", 2),
        ev(0.9, "SYSTEM", "STAGE_CHANGED", {"stage": "PLANNING"}, "PLANNING", "PLANNING", 10),
        ev(2.0, "PLANNER", "PLAN_COMPLETED", {"taskCount": 2 if use_web and use_kb else 1,
                                              "tools": [x for x in ["kb_search", "web_search", "calculator"] if x in tools]},
           None, None, 18),
        ev(2.6, "SYSTEM", "STAGE_CHANGED", {"stage": "WORKING"}, "WORKING", "WORKING", 26),
    ]
    at = 3.0
    slow = scenario == "slow"
    if use_kb:
        t.append(ev(at, "WORKER", "TASK_STARTED", {"tool": "kb_search"}, None, None, 32, "task-kb"))
        at += 0.3
    if use_web:
        t.append(ev(at, "WORKER", "TASK_STARTED", {"tool": "web_search"}, None, None, 34, "task-web"))
    at += 9.0 if slow else 1.4
    if use_kb:
        t.append(ev(at, "WORKER", "TASK_COMPLETED", {"tool": "kb_search", "evidenceCount": 4}, None, None, 46, "task-kb"))
        at += 0.6
    if use_web:
        t.append(ev(at, "WORKER", "TASK_COMPLETED", {"tool": "web_search", "evidenceCount": 3}, None, None, 52, "task-web"))
    at += 30.0 if slow else 0.7
    t.append(ev(at, "SYSTEM", "STAGE_CHANGED", {"stage": "REVIEWING"}, "REVIEWING", "REVIEWING", 60))
    at += 1.2
    if scenario == "insufficient":
        t.append(ev(at, "REVIEWER", "REVIEW_COMPLETED", {"sufficient": False, "revisionTaskCount": 1}, None, None, 64))
        at += 0.6
        t.append(ev(at, "SYSTEM", "REVISION_STARTED", {"round": 1, "taskCount": 1}, "WORKING", "WORKING", 66))
        at += 1.6
        t.append(ev(at, "WORKER", "TASK_COMPLETED", {"tool": "kb_search", "evidenceCount": 0}, None, None, 72, "task-revision"))
        at += 0.8
        t.append(ev(at, "SYSTEM", "STAGE_CHANGED", {"stage": "SYNTHESIZING"}, "SYNTHESIZING", "SYNTHESIZING", 80))
        at += 1.4
        t.append(ev(at, "SYNTHESIZER", "SYNTHESIS_COMPLETED", {"grounded": True, "citationCount": 1}, None, None, 90))
        at += 0.7
        t.append(ev(at, "SYSTEM", "INSUFFICIENT_EVIDENCE", {}, "INSUFFICIENT_EVIDENCE", "TERMINAL", 100))
        return t
    t.append(ev(at, "REVIEWER", "REVIEW_COMPLETED", {"sufficient": True, "revisionTaskCount": 0}, None, None, 70))
    at += 0.6
    t.append(ev(at, "SYSTEM", "STAGE_CHANGED", {"stage": "SYNTHESIZING"}, "SYNTHESIZING", "SYNTHESIZING", 78))
    at += 1.4
    if scenario == "budget":
        t.append(ev(at, "SYSTEM", "BUDGET_EXCEEDED", {"errorCode": "BUDGET_EXCEEDED"}, "BUDGET_EXCEEDED", "TERMINAL", 100))
        return t
    if scenario == "failed":
        t.append(ev(at, "SYNTHESIZER", "MODEL_RETRY_SCHEDULED", {"attempts": 1, "reasonCode": "MODEL_SCHEMA_INVALID"}, None, None, 82))
        at += 1.6
        t.append(ev(at, "SYSTEM", "FAILED", {"errorCode": "MODEL_PROVIDER_FAILED"}, "FAILED", "TERMINAL", 100))
        return t
    t.append(ev(at, "SYNTHESIZER", "SYNTHESIS_COMPLETED", {"grounded": True, "citationCount": 3}, None, None, 90))
    at += 0.6
    t.append(ev(at, "SYSTEM", "STAGE_CHANGED", {"stage": "FINALIZING"}, "FINALIZING", "FINALIZING", 96))
    at += 0.6
    t.append(ev(at, "SYSTEM", "SUCCEEDED", {}, "SUCCEEDED", "TERMINAL", 100))
    return t


def agent_timeline():
    plan = lambda version, s1, s2: {"planVersion": version, "reason": "根据观察更新计划", "tasks": [
        {"objective": "确认 SSE 断线恢复机制", "status": s1, "evidenceCount": 2 if s1 == "done" else 0,
         "criteria": [{"text": "找到游标重放的原文", "status": "resolved" if s1 == "done" else "uncovered"}]},
        {"objective": "确认生产断线率", "status": s2, "evidenceCount": 0,
         "criteria": [{"text": "获得可核对的统计数字", "status": "blocked" if s2 == "blocked" else "uncovered"}]}]}
    return [
        ev(0.0, "SYSTEM", "QUEUED", {}, "QUEUED", "QUEUED", 2),
        ev(1.0, "AGENT", "AGENT_PLAN_UPDATED", plan(1, "running", "pending"), "WORKING", "WORKING", 15),
        ev(2.2, "AGENT", "AGENT_ACTION_SELECTED", {"action": "kb_search", "reason": "先检索项目知识库中的恢复设计"}, None, None, 30),
        ev(3.6, "AGENT", "AGENT_OBSERVATION", {"action": "kb_search", "newEvidence": True}, None, None, 45),
        ev(4.6, "AGENT", "AGENT_PLAN_REVISED", plan(2, "done", "running"), None, None, 60),
        ev(5.8, "AGENT", "AGENT_OBSERVATION", {"action": "kb_search", "newEvidence": False}, None, None, 72),
        ev(6.8, "AGENT", "AGENT_STOPPED_WITH_GAPS", {"gaps": ["没有可核对的生产断线率统计"]}, None, None, 86),
        ev(7.8, "SYSTEM", "AGENT_PUBLICATION_VALIDATED", {"reportStatus": "partial", "citationCount": 1}, None, None, 96),
        ev(8.4, "SYSTEM", "INSUFFICIENT_EVIDENCE", {}, "INSUFFICIENT_EVIDENCE", "TERMINAL", 100),
    ]


class Run:
    def __init__(self, scenario: str, body: dict, endpoint: str):
        self.id = "preview-" + uuid.uuid4().hex[:12]
        self.session = body.get("sessionId") or "preview-session-" + uuid.uuid4().hex[:6]
        self.scenario = scenario
        self.agent = endpoint.endswith("/agents")
        self.tools = body.get("requestedTools") or ["kb_search"]
        self.created = time.monotonic()
        self.created_iso = now_iso()
        self.timeline = agent_timeline() if self.agent else workflow_timeline(scenario, self.tools)
        self.cancelled_at = None
        self.stream_count = 0
        self.lock = threading.Lock()

    def events(self):
        elapsed = time.monotonic() - self.created
        visible = []
        for index, item in enumerate(self.timeline):
            if self.cancelled_at is not None and item["at"] > self.cancelled_at:
                break
            if item["at"] <= elapsed:
                visible.append((index + 1, item))
        if self.cancelled_at is not None:
            visible.append((len(self.timeline) + 1, ev(self.cancelled_at, "SYSTEM", "CANCELLED", {}, "CANCELLED", "TERMINAL", None)))
        return visible

    def event_json(self, number, item):
        return {"eventId": number, "id": f"{self.id}:{number}", "type": item["type"], "role": item["role"],
                "taskId": item["taskId"], "payload": item["payload"], "createdAt": self.created_iso}

    def view(self):
        status, stage, progress = "QUEUED", "QUEUED", 0
        visible = self.events()
        for _, item in visible:
            status = item["status"] or status
            stage = item["stage"] or stage
            progress = item["progress"] if item["progress"] is not None else progress
        final, error = None, None
        if status == "SUCCEEDED":
            details = []
            if "kb_search" in self.tools or "web_search" not in self.tools:
                details += [KB_DOC, KB_DOC_2]
            if "web_search" in self.tools:
                details.append(WEB_DOC)
            while len(details) < 3:
                details.append(KB_DOC_2 if KB_DOC_2 not in details else WEB_DOC)
            details = details[:3]
            # 故意逆序提供 metadata，验证页面只按来源 ID 精确匹配。
            final = {"answer": SUCCESS_ANSWER, "citations": [d["sourceId"] for d in details],
                     "citationDetails": list(reversed(details)), "citationContract": "INDEXED_V1",
                     "report_status": "complete"}
            if self.scenario == "langgraph":
                # LangGraph 来源契约（FRONTEND_SOURCE_CONTRACT_2026-10-03）：逐项 metadataStatus；
                # 最后一项模拟旧回执缺快照（MISSING_SNAPSHOT）。
                def contract_item(d, available=True):
                    if not available:
                        return {"sourceId": d["sourceId"], "kind": "UNKNOWN", "title": None, "url": None, "excerpt": None,
                                "metadataStatus": "UNAVAILABLE", "unavailableReason": "MISSING_SNAPSHOT"}
                    kind = "KNOWLEDGE_CHUNK" if d["kind"] == "KNOWLEDGE_CHUNK" else "WEB_SEARCH_SNAPSHOT"
                    return {"sourceId": d["sourceId"], "kind": kind, "title": d["title"], "url": d.get("url") if kind != "KNOWLEDGE_CHUNK" else None,
                            "excerpt": d["excerpt"], "metadataStatus": "AVAILABLE", "unavailableReason": None}
                final = {"answer": SUCCESS_ANSWER, "citations": [d["sourceId"] for d in details],
                         "citationContract": "INDEXED_V1", "insufficientEvidence": False,
                         "citationDetails": [contract_item(d, i < len(details) - 1) for i, d in enumerate(details)]}
        elif status == "INSUFFICIENT_EVIDENCE":
            final = {"answer": PARTIAL_ANSWER, "citations": [KB_DOC_2["sourceId"]], "citationDetails": [KB_DOC_2],
                     "citationContract": "INDEXED_V1", "report_status": "partial",
                     "unfinished_goals": [
                         {"task_id": "task-2", "criterion_id": "c-2", "text": "获得可核对的生产断线率统计", "reason": "知识库与网页均未提供该数字"},
                         {"task_id": "task-3", "text": "确认多次断线后的重连上限", "reason": "相关检查没有完成的评估记录"}]}
        elif status == "FAILED":
            error = "MODEL_PROVIDER_FAILED"
        elif status == "BUDGET_EXCEEDED":
            error = "BUDGET_EXCEEDED"
        terminal = status in TERMINAL_STATUSES
        usage = {"modelCalls": 5, "toolCalls": len(self.tools) + 1, "totalTokens": 18342, "inputTokens": 15120,
                 "outputTokens": 3222, "durationMs": int((self.timeline[-1]["at"]) * 1000),
                 "estimatedCost": None, "costStatus": "unknown"} if terminal else {"modelCalls": 2, "toolCalls": 1}
        return {"runId": self.id, "sessionId": self.session, "status": status, "stage": stage, "progress": progress,
                "requestedTools": self.tools, "trace": [self.event_json(n, i) for n, i in visible],
                "usage": usage, "finalResponse": final, "errorCode": error, "errorMessage": None,
                "createdAt": self.created_iso, "updatedAt": now_iso(), "remoteStopState": None}

    def terminal(self):
        return self.view()["status"] in TERMINAL_STATUSES

    def evidence_view(self):
        """GET …/evidence（evidence-view/1，FRONTEND_EVIDENCE_READ_API_2026-10-03）的合成响应。"""
        limits = {"records": 256, "checks": 128, "sourceReads": 128, "blockedAttempts": 128, "responseBytes": 262144, "completeProjection": True}
        base = {"schemaVersion": "evidence-view/1", "runId": self.id, "runStatus": self.view()["status"], "limits": limits,
                "limitations": ["RECORDED_OBSERVATIONS_ONLY", "EMPTY_DOES_NOT_PROVE_NO_CONFLICT", "MODEL_RELATIONS_ARE_NOT_TRUTH_GUARANTEES",
                                "PUBLICATION_REQUIRES_EXISTING_SEAL_AND_FINALIZATION", "NO_SOURCE_REFRESH", "NO_RAW_SNAPSHOTS_OR_MODEL_RATIONALES"],
                "evidence": [], "claims": [], "decisions": [], "checks": [], "disagreements": [], "blockedAttempts": []}
        if not self.agent:
            return {**base, "availability": "UNSUPPORTED_MODE", "publicationState": "NOT_ASSESSED"}
        ident = lambda t, i: {"recordType": t, "recordId": i, "version": 1, "payloadSha256": "0" * 64, "recordedAt": self.created_iso}
        unknown = {"status": "unknown", "value": None}
        evidence = [
            {"identity": ident("Evidence", "ev-web"), "sourceId": "src-web", "kind": "web", "title": "【演示】SSE 重连说明（合成网页）",
             "url": "https://example.com/deepresearch-preview/sse", "publishedAt": unknown, "observedAt": self.created_iso, "snapshotSha256": "1" * 64,
             "applicability": {"version": unknown, "validAt": unknown, "conditions": ["浏览器侧重连请求"]}},
            {"identity": ident("Evidence", "ev-kb"), "sourceId": "src-kb", "kind": "knowledge", "title": "【演示】sse-durable-replay.md", "url": None,
             "publishedAt": unknown, "observedAt": self.created_iso, "snapshotSha256": "2" * 64, "applicability": None},
        ]
        claim = {"identity": ident("Claim", "claim-resume"), "text": "断线后可以从最后收到的事件之后继续接收。", "kind": "fact", "applicability": None,
                 "decisionStatus": "contested", "checkId": "check-1", "latestRecordedRound": True, "publicationState": "RECORDED_ONLY",
                 "evidenceLinks": [
                     {"evidenceId": "ev-web", "evidenceVersion": 1, "relation": "supports", "disposition": "unresolved",
                      "quote": {"start": 0, "end": 20, "sha256": "3" * 64, "text": "合成引文：重连时携带最后的事件 ID。", "textAvailability": "AVAILABLE"}},
                     {"evidenceId": "ev-kb", "evidenceVersion": 1, "relation": "refutes", "disposition": "unresolved",
                      "quote": {"start": 0, "end": 20, "sha256": "4" * 64, "text": "合成引文：只有保存了游标才能续传。", "textAvailability": "AVAILABLE"}}]}
        decision = {"identity": ident("DecisionRecord", "decision-resume"), "claimId": "claim-resume", "decisionStatus": "contested", "policyVersion": "preview",
                    "adoptedEvidenceIds": [], "unresolvedEvidenceIds": ["ev-web", "ev-kb"], "dismissedEvidence": [], "gapCodes": []}
        return {**base, "availability": "AVAILABLE", "publicationState": "RECORDED_ONLY", "evidence": evidence, "claims": [claim],
                "decisions": [decision], "disagreements": [{"claimId": "claim-resume", "decisionId": "decision-resume", "checkId": "check-1",
                                                             "supportingEvidenceIds": ["ev-web"], "refutingEvidenceIds": ["ev-kb"]}]}


TERMINAL_STATUSES = {"SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED", "CANCELLED", "BUDGET_EXCEEDED"}
RUNS: dict[str, Run] = {}
BY_KEY: dict[str, tuple[str, Run]] = {}
UNKNOWN_FAILED: set[str] = set()
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    server_version = "DeepResearchPreview/1.0"

    def log_message(self, fmt, *args):
        print("[preview] " + fmt % args)

    # ---------- helpers ----------
    def scenario(self) -> str:
        referer = self.headers.get("Referer") or ""
        return (parse_qs(urlparse(referer).query).get("scenario") or ["success"])[0]

    def send_json(self, status, payload, extra=None):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def owned(self, run):
        """只有创建运行的同一 Bearer Token 可以读取、订阅或取消它。"""
        if run is None or getattr(run, "owner", None) != self.headers.get("Authorization", ""):
            return None
        return run

    def authorized(self) -> bool:
        if self.headers.get("Authorization", "").startswith("Bearer "):
            return True
        self.send_json(401, {"error": "未认证", "message": "Token 缺失或已过期"})
        return False

    def body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw or b"{}")
        except json.JSONDecodeError:
            return {}

    # ---------- routes ----------
    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            self.send_response(302)
            self.send_header("Location", "/demo.html")
            self.end_headers()
            return
        if path == "/demo.html":
            html = DEMO_HTML.read_text(encoding="utf-8").replace("</body>", RIBBON + "</body>", 1).encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html;charset=UTF-8")
            self.send_header("Content-Length", str(len(html)))
            self.send_header("Cache-Control", "no-cache, no-store")
            self.end_headers()
            self.wfile.write(html)
            return
        if path == "/api/ping":
            return self.send_json(200, {"status": "ok", "preview": True})
        if path == "/api/research/tools/capabilities":
            if not self.authorized():
                return
            return self.send_json(200, {"webSearch": {"configured": self.scenario() != "noweb"}})
        parts = path.strip("/").split("/")
        if len(parts) >= 4 and parts[:3] == ["api", "research", "workflows"]:
            if not self.authorized():
                return
            run = self.owned(RUNS.get(parts[3]))
            if not run:
                return self.send_json(404, {"error": "任务不存在或无权访问"})
            if len(parts) == 4:
                return self.send_json(200, run.view())
            if len(parts) == 5 and parts[4] == "events":
                return self.stream(run)
            if len(parts) == 5 and parts[4] == "evidence":
                if self.scenario() == "evidence-disabled":
                    return self.send_json(503, {"errorCode": "EVIDENCE_VIEW_DISABLED"})
                return self.send_json(200, run.evidence_view())
        self.send_json(404, {"error": "预览服务器未模拟该路径", "path": path})

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/api/auth/dev-token":
            body = self.body()
            return self.send_json(200, {"token": FAKE_TOKEN, "tenantId": body.get("tenantId"), "userId": body.get("userId"),
                                        "roles": ["USER"], "preview": True})
        if path in ("/api/research/workflows", "/api/research/agents"):
            if not self.authorized():
                return
            key = self.headers.get("Idempotency-Key") or ""
            body = self.body()
            scenario = self.scenario()
            fingerprint = json.dumps(body, sort_keys=True)
            owner = self.headers.get("Authorization", "")
            key = owner + "\n" + key  # 幂等键按身份隔离
            with LOCK:
                if scenario == "unknown" and key not in UNKNOWN_FAILED:
                    # 第一次创建“在响应前中断”：服务端其实已经保存任务，安全重试会命中同一幂等键。
                    UNKNOWN_FAILED.add(key)
                    run = Run("success", body, path)
                    run.owner = owner
                    RUNS[run.id] = run
                    BY_KEY[key] = (fingerprint, run)
                    return self.send_json(502, {"error": "预览：模拟网关在响应前中断"})
                if key in BY_KEY:
                    saved, run = BY_KEY[key]
                    if saved != fingerprint:
                        return self.send_json(409, {"error": "幂等键与请求体不一致"})
                    replayed = True
                else:
                    run = Run(scenario, body, path)
                    run.owner = owner
                    RUNS[run.id] = run
                    BY_KEY[key] = (fingerprint, run)
                    replayed = False
            accepted = {"runId": run.id, "sessionId": run.session, "status": "QUEUED", "stage": "QUEUED",
                        "statusUrl": f"/api/research/workflows/{run.id}", "eventsUrl": f"/api/research/workflows/{run.id}/events",
                        "replayed": replayed}
            return self.send_json(202, accepted, {"Idempotency-Replayed": str(replayed).lower()})
        if path.startswith("/api/research/workflows/") and path.endswith("/cancel"):
            if not self.authorized():
                return
            run = self.owned(RUNS.get(path.split("/")[4]))
            if not run:
                return self.send_json(404, {"error": "任务不存在或无权访问"})
            already = run.terminal()
            if not already:
                run.cancelled_at = time.monotonic() - run.created
            return self.send_json(200, {"runId": run.id, "status": run.view()["status"], "alreadyTerminal": already})
        if path == "/api/research/agent":
            if not self.authorized():
                return
            body = self.body()
            time.sleep(1.6)
            return self.send_json(200, {
                "runId": "preview-native-" + uuid.uuid4().hex[:8], "sessionId": body.get("sessionId") or "preview-native-session",
                "finished": True, "status": "DONE", "rounds": 3,
                "answer": "> 本地演示数据：Single Agent 基线的合成结果。\n\n持久事件可通过游标重放，刷新页面不会重新创建任务 [来源1]。",
                "citations": [KB_DOC_2["sourceId"]], "citationDetails": [KB_DOC_2], "citationContract": "INDEXED_V1",
                "events": [{"seq": 1, "action": "AGENT", "type": "STARTED", "round": 1, "message": "已创建执行上下文"},
                           {"seq": 2, "action": "SEARCHKNOWLEDGE", "type": "TOOL_SELECTED", "round": 1, "message": "选择知识库检索"},
                           {"seq": 3, "action": "SEARCHKNOWLEDGE", "type": "TOOL_OBSERVED", "round": 2, "message": "返回 2 条片段"},
                           {"seq": 4, "action": "FINAL", "type": "FINAL_ANSWER", "round": 3, "message": "根据工具结果完成回答"}],
                "steps": [{"round": 1, "action": "searchKnowledge", "decisionSummary": "检索恢复机制", "outcomeCode": "OK"}],
                "usage": {"modelCalls": 3, "toolCalls": 1, "totalTokens": 5120, "durationMs": 1600}})
        self.send_json(404, {"error": "预览服务器未模拟该路径", "path": path})

    def stream(self, run: Run):
        cursor = self.headers.get("Last-Event-ID") or ""
        sent = 0
        if cursor.startswith(run.id + ":"):
            try:
                sent = int(cursor.split(":")[-1])
            except ValueError:
                return self.send_json(409, {"error": "事件游标不属于当前任务"})
        elif cursor:
            return self.send_json(409, {"error": "事件游标不属于当前任务"})
        with run.lock:
            run.stream_count += 1
            connection = run.stream_count
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream;charset=UTF-8")
        self.send_header("Cache-Control", "no-cache, no-store, must-revalidate")
        self.end_headers()
        delivered = 0
        try:
            while True:
                for number, item in run.events():
                    if number <= sent:
                        continue
                    data = json.dumps(run.event_json(number, item), ensure_ascii=False)
                    self.wfile.write(f"id: {run.id}:{number}\nevent: {item['type']}\ndata: {data}\n\n".encode())
                    self.wfile.flush()
                    sent = number
                    delivered += 1
                    # disconnect 场景：第一条连接在 4 条事件后断开，验证游标续传。
                    if run.scenario == "disconnect" and connection == 1 and delivered >= 4:
                        return
                if run.terminal():
                    return
                self.wfile.write(b": keep-alive\n\n")
                self.wfile.flush()
                time.sleep(0.25)
        except (BrokenPipeError, ConnectionResetError):
            return


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    print(f"DeepResearch 前端预览：http://{args.host}:{args.port}/demo.html （仅合成数据）")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
