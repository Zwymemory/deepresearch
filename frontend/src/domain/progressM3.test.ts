// Memory M3: automatic save status, correction notes and the new snapshot fields. No server.
// Shapes follow the backend candidate source (ResearchProgressService / ResearchProgressAutoSaveService);
// native recorded examples will replace these when the verified candidate is delivered.
import { describe, expect, it } from "vitest";
import { correctProgress, getProgressSave, progressErrorText } from "../api/progress";
import type { ApiContext } from "../api/endpoints";
import { autoSaveText } from "../features/memory/autoSaveText";
import { parseProgressSave, parseSnapshot } from "./progressMemory";

const base = {
  schema_version: "research-progress/1", context_kind: "prior_progress", trusted_as_evidence: false,
  project_id: "p-1", source_run_id: "wf-2", source_session_id: "s-2", original_goal: "比较方案 A、B 的检索效果与延迟", run_status: "INSUFFICIENT_EVIDENCE",
  completed_work: [], unresolved_questions: [], next_steps: [], source_evidence: [], source_claims: [],
};
const save = (over: Record<string, unknown>) => ({ schema_version: "research-progress-save/1", run_id: "wf-2", project_id: "p-1", enabled: true, saved: false,
  status: "PENDING", error_code: null, trusted_as_evidence: false, ...over });

describe("M3 snapshot fields", () => {
  it("reads origin, current question, historical work, prior refs and the correction note", () => {
    const s = parseSnapshot({ ...base, current_question: "接着做", save_origin: "automatic", user_correction: "延迟须在同一台机器上重测",
      historical_completed_work: [{ goal: "已整理检索机制说明", completion_verified: true, criteria: [], gaps: [] }],
      unresolved_questions: [{ goal: "测量延迟", status: "uncovered", completion_verified: false, historical: true, source_run_id: "wf-1", criteria: ["同数据集"], gaps: [] }],
      prior_memory_refs: [{ source_run_id: "wf-1", snapshot_sha256: "a".repeat(64) }] });
    expect(s.saveOrigin).toBe("automatic");
    expect(s.currentQuestion).toBe("接着做");
    expect(s.userCorrection).toBe("延迟须在同一台机器上重测");
    expect(s.historicalCompletedWork[0].goal).toBe("已整理检索机制说明");
    expect(s.completedWork).toEqual([]);                          // earlier progress is not current proof
    expect(s.unresolvedQuestions[0]).toMatchObject({ historical: true, fromRunId: "wf-1", completionVerified: false });
    expect(s.priorMemoryRefs).toEqual([{ sourceRunId: "wf-1", snapshotSha256: "a".repeat(64) }]);
  });

  it("older snapshots keep working and are never described as automatic", () => {
    const s = parseSnapshot(base);
    expect(s.saveOrigin).toBeNull();
    expect(s.userCorrection).toBe("");
    expect(s.historicalCompletedWork).toEqual([]);
  });
});

describe("automatic save status", () => {
  it("parses each status; unknown values and trust violations are not accepted", () => {
    for (const st of ["WAITING", "PENDING", "SAVED", "FAILED", "DELETED", "NOT_ENABLED", "UNAVAILABLE"]) expect(parseProgressSave(save({ status: st })).status).toBe(st);
    expect(parseProgressSave(save({ status: "DONE" })).status).toBe("UNKNOWN");
    expect(() => parseProgressSave(save({ trusted_as_evidence: true }))).toThrow();
    expect(() => parseProgressSave({ ...save({}), schema_version: "research-progress-save/2" })).toThrow();
  });

  it("wording: saved without an automatic origin is never called automatic; failure keeps the report", () => {
    expect(autoSaveText(parseProgressSave(save({ status: "SAVED", saved: true, save_origin: "automatic" })), false)).toBe("已自动保存已完成事项与待办，可在研究档案查看。");
    expect(autoSaveText(parseProgressSave(save({ status: "SAVED", saved: true, save_origin: "manual" })), false)).not.toContain("自动保存");
    expect(autoSaveText(parseProgressSave(save({ status: "SAVED", saved: true })), false)).not.toContain("自动保存");
    const failed = autoSaveText(parseProgressSave(save({ status: "FAILED", error_code: "PROGRESS_CAPACITY_EXCEEDED" })), false);
    expect(failed).toMatch(/超出快照容量.*原报告不受影响.*手动重试/);
    expect(failed).not.toContain("PROGRESS_");                      // raw code only in technical details
    expect(autoSaveText(parseProgressSave(save({ status: "FAILED", error_code: "SOMETHING_NEW" })), false)).toContain("服务端未说明原因");
    expect(autoSaveText(parseProgressSave(save({ status: "NOT_ENABLED", enabled: false })), false)).toBe("这次运行没有启用自动保存。");
    expect(autoSaveText(parseProgressSave(save({ status: "PENDING" })), true)).toContain("尚未确认");
    expect(autoSaveText(parseProgressSave(save({ status: "UNAVAILABLE", error_code: "PROGRESS_SOURCE_CHANGED" })), false)).toBe("保存的研究进度当前不可用：所引用的来源已变化，不会提供给新的会话。");
  });
});

describe("adapters", () => {
  const recorder = (status: number, body: unknown) => {
    const calls: Array<{ url: string; init: RequestInit }> = [];
    const ctx = { origin: "http://127.0.0.1:5173", token: "tok", fetch: (async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(typeof body === "string" ? body : JSON.stringify(body), { status });
    }) as unknown as typeof fetch } as ApiContext;
    return { ctx, calls };
  };

  it("GET progress-save is read-only and never a save", async () => {
    const { ctx, calls } = recorder(200, save({ status: "SAVED", saved: true }));
    await getProgressSave(ctx, "wf-2");
    expect(calls[0].url).toBe("http://127.0.0.1:5173/api/research/agents/wf-2/progress-save");
    expect(calls[0].init.method).toBe("GET");
    expect(calls[0].init.body).toBeUndefined();
  });

  it("PATCH sends only {note}; empty clears; returns the annotated snapshot", async () => {
    const { ctx, calls } = recorder(200, { ...base, user_correction: "" });
    const s = await correctProgress(ctx, "p-1", "wf-2", "");
    expect(calls[0].init.method).toBe("PATCH");
    expect(calls[0].url).toBe("http://127.0.0.1:5173/api/research/projects/p-1/progress/runs/wf-2");
    expect(JSON.parse(String(calls[0].init.body))).toEqual({ note: "" });
    expect((calls[0].init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
    expect(s.userCorrection).toBe("");
  });

  it("explains a too-long note and an inaccessible record without parsing free text", async () => {
    const tooLong = await correctProgress(recorder(400, "纠正说明最多2000字符").ctx, "p-1", "wf-2", "x".repeat(2001)).catch((e: unknown) => e);
    expect(progressErrorText(tooLong, "correct")).toBe("纠正说明最多 2000 个字符，未保存。");
    const gone = await correctProgress(recorder(404, { error: "not found" }).ctx, "p-1", "wf-2", "n").catch((e: unknown) => e);
    expect(progressErrorText(gone, "correct")).toContain("不存在、无权访问");
  });
});
