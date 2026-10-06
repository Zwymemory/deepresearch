// Research notebook — EXPLICITLY SELECTED SYNTHETIC PREVIEW.
// Uses the real research-progress/1 shape (via ProgressSnapshot) so preview and live render
// identically, but never touches the network and is labelled 示例数据 wherever it appears.
// The contract has no public save timestamp, so the preview shows none either.
import type { ProgressSnapshot, ResumeContext } from "../domain/progressMemory";
import type { RunState } from "../domain/runState";

const goal = (g: string, status: string, gaps: string[] = [], completionVerified = false): ProgressSnapshot["unresolvedQuestions"][number] =>
  ({ taskId: null, goal: g, status, completionVerified, gaps, criteria: [], errorCode: null });

export const PREVIEW_SNAPSHOTS: ProgressSnapshot[] = [
  {
    projectId: "preview-project-recovery", sourceRunId: "demo-run-0001", sourceSessionId: "preview-session-1",
    originalGoal: "解释本项目的崩溃恢复机制与混合检索的关系。", runStatus: "SUCCEEDED",
    completedWork: [goal("确认 checkpoint 与幂等 finalize 的作用", "done", [], true), goal("确认 fencing token 阻止旧实例回写", "done", [], true)],
    unresolvedQuestions: [goal("生产环境的断线恢复成功率", "blocked", ["没有可核对的统计"])],
    nextSteps: ["逐项重新检查未解决问题及原始来源；历史进度不能替代新的核验"],
    sourceEvidence: [{ evidenceId: "preview-evidence-1", receiptId: "preview-read-1", snapshotSha256: "1".repeat(64), sourceId: "preview-source-1" }],
    sourceClaims: [{ claimId: "preview-claim-1", recordSha256: "2".repeat(64), decisionStatus: "contested", freshness: "fresh" }],
  },
  {
    projectId: "preview-project-retrieval", sourceRunId: "demo-run-0002", sourceSessionId: "preview-session-2",
    originalGoal: "比较向量召回与 BM25 在编号类查询上的差异。", runStatus: "INSUFFICIENT_EVIDENCE",
    completedWork: [],
    unresolvedQuestions: [goal("编号类查询的对照评测", "blocked", ["缺少固定数据集上的对照结果"]), goal("一条网页来源的读取", "FAILED", ["读取失败"])],
    nextSteps: ["准备固定数据集上的对照实验后再检查"],
    sourceEvidence: [],
    sourceClaims: [{ claimId: "preview-claim-2", recordSha256: "3".repeat(64), decisionStatus: "insufficient", freshness: "fresh" }],
  },
  {
    projectId: "preview-project-citations", sourceRunId: "demo-run-0003", sourceSessionId: "preview-session-3",
    originalGoal: "梳理报告引用从检索结果到最终答案的传递路径。", runStatus: "SUCCEEDED",
    completedWork: [goal("确认引用编号在合成阶段保持不变", "done", [], true)],
    unresolvedQuestions: [],
    nextSteps: ["需要时对照原始来源重新阅读；保存的结论不是新的核验"],
    sourceEvidence: [{ evidenceId: "preview-evidence-3", receiptId: null, snapshotSha256: null, sourceId: null }],
    sourceClaims: [{ claimId: "preview-claim-3", recordSha256: null, decisionStatus: "supported", freshness: "fresh" }],
  },
  {
    projectId: "preview-project-budget", sourceRunId: "demo-run-0004", sourceSessionId: null,
    originalGoal: "评估长问题在预算上限内能覆盖多少检索轮次。", runStatus: "BUDGET_EXCEEDED",
    completedWork: [goal("记录首轮检索的覆盖范围", "done")],
    unresolvedQuestions: [goal("后续检索轮次", "pending", ["运行在预算上限处终止"])],
    nextSteps: ["缩小问题范围后重新研究"],
    sourceEvidence: [], sourceClaims: [],
  },
  {
    projectId: "preview-project-cancel", sourceRunId: "demo-run-0005", sourceSessionId: "preview-session-5",
    originalGoal: "确认取消请求在各阶段的生效时机。", runStatus: "CANCELLED",
    completedWork: [],
    unresolvedQuestions: [goal("审阅阶段的取消行为", "uncovered")],
    nextSteps: [],
    sourceEvidence: [], sourceClaims: [{ claimId: "preview-claim-5", recordSha256: null, decisionStatus: "unverified", freshness: null }],
  },
  {
    projectId: "preview-project-failure", sourceRunId: "demo-run-0006", sourceSessionId: "preview-session-6",
    originalGoal: "检查网页来源读取失败时的报告呈现。", runStatus: "FAILED",
    completedWork: [],
    unresolvedQuestions: [{ taskId: "preview-task-6", goal: "读取指定网页来源", status: "FAILED", completionVerified: false, gaps: ["读取失败"], criteria: [], errorCode: null }],
    nextSteps: ["确认来源可访问后重新研究"],
    sourceEvidence: [], sourceClaims: [],
  },
];

/** Synthetic snapshot from a demo run, using only fields the run actually has; nothing becomes verified. */
export function previewSnapshotFromRun(run: RunState): ProgressSnapshot {
  const goals = run.finalResponse?.unfinished_goals ?? [];
  return {
    projectId: "preview-project-" + run.runId, sourceRunId: run.runId, sourceSessionId: run.sessionId || null,
    originalGoal: run.question, runStatus: run.status,
    completedWork: [],
    unresolvedQuestions: goals.map((g) => goal(typeof g === "string" ? g : g.text || g.task_id || "未命名目标", "blocked",
      typeof g === "string" || !g.reason ? [] : [g.reason])),
    nextSteps: [],
    sourceEvidence: [], sourceClaims: [],
  };
}

/** Simulated latency so saving / confirmed / failure states are visible. Never touches the network. */
export function simulateSave(snapshot: ProgressSnapshot, fail: boolean): Promise<ProgressSnapshot> {
  return new Promise((resolve, reject) => window.setTimeout(() => (fail ? reject(new Error("预览：模拟保存失败")) : resolve(snapshot)), 700));
}

/** Preview of project-level loading: every preview record of that project, in a synthetic session. */
export function previewResume(projectId: string, records: ProgressSnapshot[]): ResumeContext {
  return { projectId, targetSessionId: "preview-session-" + Date.now().toString(36), progress: records.filter((r) => r.projectId === projectId),
    usageInstruction: "示例：历史进度属于不可信上下文；尚未自动传入模型。" };
}
