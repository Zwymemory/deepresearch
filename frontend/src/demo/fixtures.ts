// Deterministic, synthetic demonstration data. Nothing here came from a model,
// a retrieval provider or a real run. Shapes follow the existing public contracts
// (WorkflowDtos.View / Event, finalResponse), except where marked FUTURE CONTRACT.
import type { EvidenceView } from "../domain/evidenceView";
import type { CitationDetail, FinalResponse, RunEvent, ToolName, Usage } from "../domain/types";

export const DEMO_QUESTION = "本项目如何通过 checkpoint、claim fencing 与 SSE 重放实现崩溃恢复？混合检索在其中起什么作用？";
export const DEMO_TOOLS: ToolName[] = ["kb_search", "web_search", "calculator"];

export const SOURCES: CitationDetail[] = [
  {
    sourceId: "kb:demo-checkpoint:chunk-03",
    kind: "KNOWLEDGE_CHUNK",
    title: "【示例】checkpoint-and-crash-recovery.md",
    excerpt: "[UNTRUSTED_DATA_BEGIN source=knowledge-base]\n## 示例片段\n\n合成摘录：工作流在每个节点完成后写入 checkpoint；进程崩溃后，新的 Worker 通过 claim 领取任务并从最近的 checkpoint 继续。已完成的 checkpoint 走幂等 finalize，不会再次执行模型合成。此段文字仅用于界面演示。\n\n[UNTRUSTED_DATA_END source=knowledge-base]",
  },
  {
    sourceId: "kb:demo-fencing:chunk-01",
    kind: "KNOWLEDGE_CHUNK",
    title: "【示例】claim-lease-and-fencing.md",
    excerpt: "合成摘录：每次 claim 都会递增 fencing token。旧实例在租约过期后尝试回写时，因 token 落后而被拒绝，从而避免两个 Worker 同时写入同一运行。该描述只说明机制，不包含生产环境的故障率数据。",
  },
  {
    sourceId: "web:demo:" + "b".repeat(64),
    kind: "WEB_SEARCH_SNAPSHOT",
    title: "【示例】Server-Sent Events 规范中的 Last-Event-ID 重连行为说明——一个用于测试超长标题换行、截断与可读性的合成网页标题",
    url: "https://example.com/deepresearch-demo/server-sent-events-last-event-id-reconnection",
    excerpt: "导航\n\n合成搜索摘要：浏览器重新连接事件流时，会在请求头中携带最后收到的事件 ID；服务端可据此从下一条事件继续发送。此摘要是示例数据，不是对真实规范原文的逐字引用，也未经过全文核验。",
  },
  {
    sourceId: "web:demo:" + "c".repeat(64),
    kind: "WEB_ORIGINAL",
    title: "【示例】Reciprocal Rank Fusion：融合多个排序列表",
    url: "https://example.org/deepresearch-demo/rrf",
    excerpt: "合成原文片段：RRF 把每个检索器给出的名次转换为 1/(k+rank) 并求和，常见取值 k=60。它不依赖各检索器分数的量纲，因此适合融合向量召回与 BM25 关键词召回。此段为示例数据。",
  },
];

const SUCCESS_ANSWER = `## 结论

崩溃恢复依靠三层记录协同：**checkpoint** 保存每个节点的完成状态 [来源1]，**claim fencing** 阻止过期实例回写 [来源2]，**SSE 游标** 让页面断线后从下一条事件继续 [来源3]。三者都只解决“继续执行与继续观看”，不改变答案的事实正确性。

## 执行如何恢复

1. 节点完成后写入 checkpoint；新的 Worker 从最近的 checkpoint 继续，已完成的部分走幂等 finalize [来源1]。
2. 每次领取任务都会递增 fencing token，租约过期的旧实例回写会被拒绝 [来源2]。

## 页面如何恢复

浏览器重新连接事件流时携带最后的事件 ID，服务端从下一条继续发送；页面按事件 ID 去重，因此刷新不会重新创建任务 [来源3]。

## 检索的作用

混合检索与恢复机制相互独立：向量召回与 BM25 的结果按名次用 RRF 融合，\`k=60\` 时第 1 名与第 3 名的贡献约为 0.0164 与 0.0159 [来源4]。

> 以上为示例报告，用于演示排版与引用检查，不代表真实研究结论。`;

const PARTIAL_ANSWER = `## 已有证据支持的部分

checkpoint 与 claim fencing 共同保证崩溃后可以继续执行，且旧实例不会覆盖新结果 [来源1] [来源2]。

## 尚未核实的部分

关于“生产环境中真实的断线恢复成功率”，现有知识库与网页摘要都没有给出可核对的统计，系统保留为待查事项，没有补全数字。`;

export const SUCCESS_RESPONSE: FinalResponse = {
  answer: SUCCESS_ANSWER,
  citations: SOURCES.map((s) => s.sourceId),
  // Deliberately reversed: the UI must match by sourceId, not by position.
  citationDetails: [...SOURCES].reverse(),
  citationContract: "INDEXED_V1",
  report_status: "complete",
};

export const PARTIAL_RESPONSE: FinalResponse = {
  answer: PARTIAL_ANSWER,
  citations: [SOURCES[0].sourceId, SOURCES[1].sourceId],
  citationDetails: [SOURCES[1], SOURCES[0]],
  citationContract: "INDEXED_V1",
  report_status: "partial",
  unfinished_goals: [
    { task_id: "task-3", criterion_id: "c-3", text: "获得可核对的生产环境断线恢复成功率", reason: "知识库与网页摘要均未提供该数字" },
    { task_id: "task-4", text: "确认多次断线后的重连上限", reason: "相关检查没有完成的评估记录" },
  ],
};

export const DEMO_USAGE: Usage = {
  modelCalls: 5, toolCalls: 4, totalTokens: null, inputTokensStatus: "unknown", outputTokensStatus: "unknown",
  durationMs: 9400, estimatedCost: null, costStatus: "unknown",
};

/** Event script replayed by the demo player: [delay ms after start, event]. */
export const RUN_SCRIPT: Array<[number, RunEvent]> = [
  [0, { type: "QUEUED", role: "SYSTEM", payload: {} }],
  [700, { type: "STAGE_CHANGED", role: "SYSTEM", payload: { stage: "PLANNING" } }],
  [1700, { type: "PLAN_COMPLETED", role: "PLANNER", payload: { taskCount: 3, tools: DEMO_TOOLS } }],
  [2300, { type: "STAGE_CHANGED", role: "SYSTEM", payload: { stage: "WORKING" } }],
  [2700, { type: "TASK_STARTED", role: "WORKER", taskId: "task-kb", payload: { tool: "kb_search" } }],
  [2900, { type: "TASK_STARTED", role: "WORKER", taskId: "task-web", payload: { tool: "web_search" } }],
  [4300, { type: "TASK_COMPLETED", role: "WORKER", taskId: "task-kb", payload: { tool: "kb_search", evidenceCount: 4 } }],
  [5000, { type: "TASK_COMPLETED", role: "WORKER", taskId: "task-web", payload: { tool: "web_search", evidenceCount: 3 } }],
  [5600, { type: "STAGE_CHANGED", role: "SYSTEM", payload: { stage: "REVIEWING" } }],
  [6800, { type: "REVIEW_COMPLETED", role: "REVIEWER", payload: { sufficient: true, revisionTaskCount: 0 } }],
  [7300, { type: "STAGE_CHANGED", role: "SYSTEM", payload: { stage: "SYNTHESIZING" } }],
  [8700, { type: "SYNTHESIS_COMPLETED", role: "SYNTHESIZER", payload: { grounded: true, citationCount: 4 } }],
  [9100, { type: "STAGE_CHANGED", role: "SYSTEM", payload: { stage: "FINALIZING" } }],
  [9500, { type: "SUCCEEDED", role: "SYSTEM", payload: {} }],
];

/**
 * Synthetic evidence-view/1 response (shape of GET /api/research/workflows/{runId}/evidence)
 * with one recorded disagreement. Demo only; labelled wherever it is shown.
 */
const id = (recordType: string, recordId: string) => ({ recordType, recordId, version: 1, payloadSha256: "0".repeat(64), recordedAt: "2026-10-02T09:00:06Z" });
const unknownDate = { status: "unknown", value: null } as const;
export const DEMO_EVIDENCE_VIEW: EvidenceView = {
  schemaVersion: "evidence-view/1", runId: "demo-run-0001", runStatus: "SUCCEEDED", availability: "AVAILABLE", publicationState: "RECORDED_ONLY",
  limits: { records: 256, checks: 128, sourceReads: 128, blockedAttempts: 128, responseBytes: 262144, completeProjection: true },
  limitations: ["RECORDED_OBSERVATIONS_ONLY", "EMPTY_DOES_NOT_PROVE_NO_CONFLICT", "MODEL_RELATIONS_ARE_NOT_TRUTH_GUARANTEES",
    "PUBLICATION_REQUIRES_EXISTING_SEAL_AND_FINALIZATION", "NO_SOURCE_REFRESH", "NO_RAW_SNAPSHOTS_OR_MODEL_RATIONALES"],
  evidence: [
    { identity: id("Evidence", "ev-sse"), sourceId: "src-sse", kind: "web", title: "【示例】SSE 重连说明（合成网页）", url: "https://example.com/deepresearch-demo/sse",
      publishedAt: unknownDate, observedAt: "2026-10-02T09:00:04Z", snapshotSha256: "1".repeat(64),
      applicability: { version: unknownDate, validAt: unknownDate, conditions: ["浏览器侧重连请求"] } },
    { identity: id("Evidence", "ev-fencing"), sourceId: "src-fencing", kind: "knowledge", title: "【示例】claim-lease-and-fencing.md", url: null,
      publishedAt: unknownDate, observedAt: "2026-10-02T09:00:05Z", snapshotSha256: "2".repeat(64),
      applicability: { version: unknownDate, validAt: unknownDate, conditions: ["服务端拒绝旧实例写入"] } },
  ],
  claims: [
    { identity: id("Claim", "claim-resume"), text: "断线后，浏览器会从最后收到的事件之后继续接收。", kind: "fact", applicability: null,
      decisionStatus: "contested", checkId: "check-1", latestRecordedRound: true, publicationState: "RECORDED_ONLY", evidenceLinks: [
        { evidenceId: "ev-sse", evidenceVersion: 1, relation: "supports", disposition: "unresolved",
          quote: { start: 0, end: 32, sha256: "3".repeat(64), text: "合成摘录：重连时携带最后收到的事件 ID，服务端从下一条继续发送。", textAvailability: "AVAILABLE" } },
        { evidenceId: "ev-fencing", evidenceVersion: 1, relation: "refutes", disposition: "unresolved",
          quote: { start: 0, end: 30, sha256: "4".repeat(64), text: "合成摘录：只有服务端保存了对应游标时，才能从下一条继续；否则需要快照重建。", textAvailability: "AVAILABLE" } }] },
    { identity: id("Claim", "claim-fencing"), text: "fencing token 阻止过期实例覆盖新结果。", kind: "fact", applicability: null,
      decisionStatus: "supported", checkId: "check-1", latestRecordedRound: true, publicationState: "RECORDED_ONLY", evidenceLinks: [
        { evidenceId: "ev-fencing", evidenceVersion: 1, relation: "supports", disposition: "adopted", quote: null }] },
  ],
  decisions: [
    { identity: id("DecisionRecord", "decision-resume"), claimId: "claim-resume", decisionStatus: "contested", policyVersion: "demo",
      adoptedEvidenceIds: [], unresolvedEvidenceIds: ["ev-sse", "ev-fencing"], dismissedEvidence: [], gapCodes: [] },
  ],
  checks: [],
  disagreements: [{ claimId: "claim-resume", decisionId: "decision-resume", checkId: "check-1", supportingEvidenceIds: ["ev-sse"], refutingEvidenceIds: ["ev-fencing"] }],
  blockedAttempts: [],
};
