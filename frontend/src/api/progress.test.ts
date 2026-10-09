// Adapter tests against the backend's captured research-progress fixtures. No network.
import { describe, expect, it } from "vitest";
import project from "./__fixtures__/research-progress/project.json";
import saved from "./__fixtures__/research-progress/saved.json";
import list from "./__fixtures__/research-progress/list.json";
import resume from "./__fixtures__/research-progress/resume.json";
import deleted from "./__fixtures__/research-progress/deleted.json";
import { createResumeContext, deleteProgress, discoverProject, getResumeContext, listProgress, progressErrorText, readProgress, saveProgress } from "./progress";
import { parseSnapshot, recordKey } from "../domain/progressMemory";

type Call = { url: string; init: RequestInit };
function fake(responses: Array<() => Response>) {
  const calls: Call[] = [];
  const fn = (async (url: string, init: RequestInit = {}) => { calls.push({ url: String(url), init }); return responses[calls.length - 1](); }) as unknown as typeof fetch;
  return { fn, calls };
}
const json = (body: unknown, status = 200) => () => new Response(JSON.stringify(body), { status });
const ctx = (fn: typeof fetch) => ({ origin: "http://x", token: "t", fetch: fn });

describe("research-progress contract", () => {
  it("maps the saved fixture defensively, keeping statuses unresolved and criteria camelCase", () => {
    const s = parseSnapshot(saved);
    expect([s.projectId, s.sourceRunId, s.runStatus, s.completedWork.length]).toEqual(["project-http", "run-http", "FAILED", 0]);
    expect(s.unresolvedQuestions).toHaveLength(3);
    expect(s.unresolvedQuestions[0].criteria[0].criterionId).toMatch(/^criterion-/);
    const runLevel = s.unresolvedQuestions[2];
    expect([runLevel.taskId, runLevel.criteria.length, runLevel.errorCode, runLevel.completionVerified]).toEqual([null, 0, "LOCAL_FIXTURE_FAILURE", false]);
    expect(s.sourceClaims[0]).toMatchObject({ decisionStatus: "contested", freshness: "fresh" });
    expect(s.sourceEvidence[0].snapshotSha256).toHaveLength(64);
  });
  it("rejects unknown schemas and any upgrade of the trust boundary", () => {
    expect(() => parseSnapshot({ ...saved, schema_version: "research-progress/2" })).toThrow(/不支持/);
    expect(() => parseSnapshot({ ...saved, trusted_as_evidence: true })).toThrow(/prior_progress/);
  });
  it("uses (project_id, source_run_id) as record identity", () => {
    expect(recordKey("p", "r")).not.toBe(recordKey("p", "r2"));
  });
});

describe("research-progress routes", () => {
  it("discovers, saves, reads, lists and deletes with bearer auth, no body and encoded ids", async () => {
    const { fn, calls } = fake([json(project), json(saved), json(saved), json(list), json(deleted), json({ ...deleted, deleted: false })]);
    expect(await discoverProject(ctx(fn), "run/1")).toEqual({ projectId: "project-http", runId: "run-http" });
    await saveProgress(ctx(fn), "p 1", "run/1");
    await readProgress(ctx(fn), "p 1", "run/1");
    const listed = await listProgress(ctx(fn));
    expect([listed.items.length, listed.candidateLimit]).toEqual([1, 20]);
    expect(await deleteProgress(ctx(fn), "p 1", "run/1")).toEqual({ deleted: true });
    expect(await deleteProgress(ctx(fn), "p 1", "run/1")).toEqual({ deleted: false });
    expect(calls.map((c) => [c.init.method, c.url.replace("http://x", "")])).toEqual([
      ["GET", "/api/research/agents/run%2F1/progress-project"],
      ["PUT", "/api/research/projects/p%201/progress/runs/run%2F1"],
      ["GET", "/api/research/projects/p%201/progress/runs/run%2F1"],
      ["GET", "/api/research/progress"],
      ["DELETE", "/api/research/projects/p%201/progress/runs/run%2F1"],
      ["DELETE", "/api/research/projects/p%201/progress/runs/run%2F1"],
    ]);
    for (const c of calls) {
      expect(c.init.body).toBeUndefined();
      expect((c.init.headers as Record<string, string>).Authorization).toBe("Bearer t");
    }
  });
  it("loads project context with POST once, and recovers with GET by session id", async () => {
    const { fn, calls } = fake([json(resume), json(resume)]);
    const created = await createResumeContext(ctx(fn), "project-http");
    expect([created.targetSessionId, created.progress.length]).toEqual(["fd075717-2934-484f-9736-7a3e87a01464", 1]);
    expect(created.usageInstruction).toMatch(/尚未自动传入模型/);
    await getResumeContext(ctx(fn), "project-http", created.targetSessionId);
    expect(calls.map((c) => c.init.method)).toEqual(["POST", "GET"]);
    expect(calls[1].url).toContain("resume-context?sessionId=fd075717-2934-484f-9736-7a3e87a01464");
  });
  it("does not retry a failed new-session POST", async () => {
    const { fn, calls } = fake([() => new Response("upstream", { status: 502 })]);
    await expect(createResumeContext(ctx(fn), "p")).rejects.toMatchObject({ status: 502 });
    expect(calls).toHaveLength(1);
  });
  it("explains errors by status and tolerates non-JSON bodies", async () => {
    const { fn } = fake([() => new Response("<html>too large</html>", { status: 413 })]);
    const error = await saveProgress(ctx(fn), "p", "r").catch((e) => e);
    expect(error.status).toBe(413);
    expect(progressErrorText(error, "save")).toMatch(/60,000/);
    expect(progressErrorText({ status: 404 }, "discover")).toMatch(/不支持保存/);
    expect(progressErrorText(new TypeError("network"), "load")).toMatch(/不会自动重试/);
    expect(progressErrorText({ status: 401 }, "list")).toMatch(/重新连接身份/);
  });
});
