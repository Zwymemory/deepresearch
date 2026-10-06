// Memory M4: recall view parsing and wording. Shapes follow the task contract and the backend draft
// (ResearchRecallService.view); recorded native responses will be checked at native capture.
import { describe, expect, it } from "vitest";
import { newPending } from "../api/pending";
import { recallHeadline } from "../features/memory/recallText";
import { parseRecall } from "./memoryRecall";

const snapshot = {
  schema_version: "research-progress/1", context_kind: "prior_progress", trusted_as_evidence: false,
  project_id: "p-old", source_run_id: "wf-old", original_goal: "Kafka v1.0 的检索延迟", run_status: "INSUFFICIENT_EVIDENCE",
  completed_work: [{ goal: "整理 v1.0 检索机制", completion_verified: true, criteria: [], gaps: [] }],
  unresolved_questions: [{ goal: "同硬件测量延迟", completion_verified: false, criteria: ["同数据集"], gaps: ["未测量"] }],
  next_steps: [], source_evidence: [{ evidence_id: "ev-1" }], source_claims: [{ claim_id: "c-1", decision_status: "contested", freshness: "fresh" }],
  user_correction: "延迟须在机器 C 上重测",
};
const record = {
  source_project_id: "p-old", source_run_id: "wf-old", snapshot_sha256: "a".repeat(64), snapshot,
  reason: { method: "keyword-baseline/1", matched_terms: ["kafka", "延迟"], score: 2 },
  applicability: { status: "RECHECK_REQUIRED", cautions: ["版本可能已变化"], mentioned_versions: ["v1.0"],
    time: { status: "unknown", value: null, reason: "有效时间未知" }, conditions: { status: "unknown", value: null, reason: "条件未知" } },
  source_refs: [
    { source_key: "k1", source: { title: "Kafka 文档 v1.0" }, snapshot_sha256: "b".repeat(64), source_run_id: "wf-old", evidence_id: "ev-1", independent_evidence: false },
    { source_key: "k1", source: { title: "Kafka 文档 v1.0" }, snapshot_sha256: "b".repeat(64), source_run_id: "wf-older", evidence_id: "ev-0", independent_evidence: false },
  ],
};
const view = (over: Record<string, unknown>) => ({ schema_version: "research-recall-view/1", run_id: "wf-new", project_id: "p-new", status: "USED",
  planner_input_recorded: true, trusted_as_evidence: false, records: [record],
  selection: { candidate_limit: 20, max_records: 3, limit_bytes: 8192, method: "keyword-baseline/1", unrelated: 2, inaccessible: 0, duplicates: 1, omitted: 0 },
  message: "旧研究仅作调查线索；时间、版本和条件均需重新核查。", ...over });

describe("recall view", () => {
  it("USED with the planner record: reason, versions, dispute, correction and deduplicated sources", () => {
    const v = parseRecall(view({}));
    expect(v.status).toBe("USED");
    expect(v.plannerInputRecorded).toBe(true);
    const r = v.records[0];
    expect(r.reason.matchedTerms).toEqual(["kafka", "延迟"]);
    expect(r.applicability.mentionedVersions).toEqual(["v1.0"]);
    expect(r.applicability.time).toEqual({ status: "unknown", value: null, reason: "有效时间未知" });
    expect(r.snapshot?.sourceClaims[0].decisionStatus).toBe("contested");
    expect(r.snapshot?.userCorrection).toBe("延迟须在机器 C 上重测");
    expect(r.sourceRefs).toHaveLength(1);                    // same source key counts once
    expect(r.sourceRefs[0].label).toBe("Kafka 文档 v1.0");
    expect(recallHeadline(v)).toContain("进入本次规划请求");
    expect(recallHeadline(v)).toContain("不是已核验的证据");
  });

  it("SELECTED, or USED without the planner record, never claims use", () => {
    expect(recallHeadline(parseRecall(view({ status: "SELECTED", planner_input_recorded: false })))).toMatch(/尚未确认.*规划请求/);
    const usedNoRecord = parseRecall(view({ status: "USED", planner_input_recorded: false }));
    expect(usedNoRecord.plannerInputRecorded).toBe(false);
    expect(recallHeadline(usedNoRecord)).not.toContain("已有");
    expect(parseRecall(view({ status: "SELECTED", planner_input_recorded: true })).plannerInputRecorded).toBe(false);
  });

  it("EMPTY and DISABLED say so plainly", () => {
    expect(recallHeadline(parseRecall(view({ status: "EMPTY", records: [], selection: { ...view({}).selection, unrelated: 5 } })))).toContain("没有找到与本问题相关的历史研究");
    expect(recallHeadline(parseRecall(view({ status: "DISABLED", records: [], selection: null })))).toBe("本次运行关闭了历史研究参考。");
  });

  it("UNAVAILABLE exposes no old payload even if the server sent some; unknown status shows nothing", () => {
    const v = parseRecall(view({ status: "UNAVAILABLE", planner_input_recorded: true }));
    expect(v.records).toEqual([]);
    expect(v.selection).toBeNull();
    expect(v.plannerInputRecorded).toBe(false);
    expect(recallHeadline(v)).toContain("请重新开始研究");
    const u = parseRecall(view({ status: "MAYBE" }));
    expect(u.status).toBe("UNKNOWN");
    expect(u.records).toEqual([]);
  });

  it("rejects unknown schema and trusted_as_evidence; a malformed snapshot is not shown as content", () => {
    expect(() => parseRecall(view({ schema_version: "research-recall-view/2" }))).toThrow();
    expect(() => parseRecall(view({ trusted_as_evidence: true }))).toThrow();
    const bad = parseRecall(view({ records: [{ ...record, snapshot: { ...snapshot, trusted_as_evidence: true } }] }));
    expect(bad.records[0].snapshot).toBeNull();
    expect(bad.records[0].snapshotError).toBeTruthy();
  });

  it("memoryRecall is part of the stored idempotent body (retry replays the same choice)", () => {
    const p = newPending("agent", { question: "Kafka v2.0 延迟", requestedTools: ["kb_search"], memoryRecall: false },
      { origin: "o", tenantId: "t", userId: "u", credential: "c" } as never, "ui-1-key");
    expect(JSON.parse(p.bodyJson)).toEqual({ question: "Kafka v2.0 延迟", requestedTools: ["kb_search"], memoryRecall: false });
  });
});
