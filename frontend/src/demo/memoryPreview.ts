// Research-progress notebook — PREVIEW ONLY.
// The backend progress-memory API has not been delivered as a documented contract, so this
// is an in-memory, network-free preview with a provisional shape. It is not wired to any
// endpoint and must stay labelled as 示例数据 wherever it appears.
import type { RunState } from "../domain/runState";

export interface ProgressRecord {
  id: string;
  /** Run this progress was saved from (provenance). */
  runId: string;
  goal: string;
  runStatus: string;
  savedAt: string;
  completedWork: string[];
  unresolved: string[];
  nextSteps: string[];
  /** Provenance references as returned; never upgraded to newly verified evidence. */
  provenance: Array<{ label: string; status: "verified-at-the-time" | "contested" | "unverified" | "failed" }>;
}

export const PREVIEW_RECORDS: ProgressRecord[] = [
  {
    id: "preview-progress-1", runId: "demo-run-0001", goal: "解释本项目的崩溃恢复机制与混合检索的关系。", runStatus: "SUCCEEDED",
    savedAt: "2026-10-02T09:12:00Z",
    completedWork: ["确认 checkpoint 与幂等 finalize 的作用", "确认 fencing token 阻止旧实例回写"],
    unresolved: ["生产环境的断线恢复成功率没有可核对的统计"],
    nextSteps: ["补充真实运行的断线记录后再评估"],
    provenance: [
      { label: "【示例】checkpoint-and-crash-recovery.md", status: "verified-at-the-time" },
      { label: "【示例】SSE 重连说明（合成网页）", status: "contested" },
    ],
  },
  {
    id: "preview-progress-2", runId: "demo-run-0002", goal: "比较向量召回与 BM25 在编号类查询上的差异。", runStatus: "INSUFFICIENT_EVIDENCE",
    savedAt: "2026-10-01T15:40:00Z",
    completedWork: ["整理 RRF 融合的计算方式"],
    unresolved: ["缺少编号类查询的对照评测", "一条网页来源的读取失败"],
    nextSteps: ["准备固定数据集上的对照实验"],
    provenance: [
      { label: "【示例】Reciprocal Rank Fusion 简介", status: "verified-at-the-time" },
      { label: "【示例】某网页（读取失败）", status: "failed" },
    ],
  },
];

/** Build a preview record from a demo run using only fields the run actually has. */
export function previewRecordFromRun(run: RunState): ProgressRecord {
  const goals = run.finalResponse?.unfinished_goals ?? [];
  return {
    id: "preview-" + run.runId + "-" + Date.now().toString(36),
    runId: run.runId, goal: run.question, runStatus: run.status, savedAt: new Date().toISOString(),
    completedWork: run.status === "SUCCEEDED" ? ["报告已完成（示例）"] : [],
    unresolved: goals.map((g) => typeof g === "string" ? g : g.text || g.task_id || "未命名目标"),
    nextSteps: [],
    // Report citations are structurally mapped (INDEXED_V1), not checked statement by statement,
    // so saved provenance is "unverified" — saving never upgrades a source's status.
    provenance: (run.finalResponse?.citationDetails ?? []).map((d) => ({ label: d.title || d.sourceId, status: "unverified" as const })),
  };
}

/** Simulated latency so saving / confirmed / failure states are visible. Never touches the network. */
export function simulateSave(record: ProgressRecord, fail: boolean): Promise<ProgressRecord> {
  return new Promise((resolve, reject) => window.setTimeout(() => (fail ? reject(new Error("预览：模拟保存失败")) : resolve(record)), 700));
}
