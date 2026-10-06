// Memory M1 frontend: continuation request body and refusal handling. No server, no network.
import { describe, expect, it } from "vitest";
import { createRun, type ApiContext } from "../api/endpoints";
import { ApiError, friendlyError } from "../api/http";
import { newPending } from "../api/pending";
import { explainFailure } from "./failures";
import { isMemoryCode, memoryErrorInfo } from "./researchMemory";

const scope = { origin: "http://127.0.0.1:5173", tenantId: "t", userId: "u", credential: "c" };

describe("continue research request", () => {
  it("sends only identifiers: the loaded session paired with the project, verbatim on retry", async () => {
    const body = { question: "接着做，先推进尚未完成的部分。", requestedTools: ["kb_search"], sessionId: "sess-loaded", researchProjectId: "proj-1" };
    const pending = newPending("agent", body, scope as never, "ui-1-key");
    expect(JSON.parse(pending.bodyJson)).toEqual(body);
    const calls: Array<{ url: string; init: RequestInit }> = [];
    const ctx: ApiContext = { origin: "http://127.0.0.1:5173", token: "tok", fetch: (async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ runId: "wf-new", sessionId: "sess-loaded", status: "QUEUED", statusUrl: "/api/research/workflows/wf-new", eventsUrl: "/api/research/workflows/wf-new/events" }), { status: 202 });
    }) as typeof fetch } as ApiContext;
    await createRun(ctx, "agent", pending.bodyJson, pending.key);
    await createRun(ctx, "agent", pending.bodyJson, pending.key);   // explicit retry of an unknown outcome
    expect(calls.map((c) => c.url)).toEqual(["http://127.0.0.1:5173/api/research/agents", "http://127.0.0.1:5173/api/research/agents"]);
    expect(calls[0].init.body).toBe(calls[1].init.body);
    expect((calls[0].init.headers as Record<string, string>)["Idempotency-Key"]).toBe((calls[1].init.headers as Record<string, string>)["Idempotency-Key"]);
    expect(Object.keys(JSON.parse(String(calls[0].init.body))).sort()).toEqual(["question", "requestedTools", "researchProjectId", "sessionId"]);
  });

  it("reads the stable memory error body and explains it without free text", async () => {
    const ctx = { origin: "http://127.0.0.1:5173", token: "tok", fetch: (async () => new Response(JSON.stringify({ errorCode: "RESEARCH_MEMORY_UNAVAILABLE", requiresReselection: true }), { status: 409 })) as unknown as typeof fetch } as ApiContext;
    const error = await createRun(ctx, "agent", "{}", "ui-2-key").catch((e: unknown) => e) as ApiError;
    expect(error.status).toBe(409);
    expect(error.code).toBe("RESEARCH_MEMORY_UNAVAILABLE");
    expect(error.requiresReselection).toBe(true);
    expect(friendlyError(error, "workflow")).toBe(memoryErrorInfo("RESEARCH_MEMORY_UNAVAILABLE").text);
  });

  it("tolerates non-JSON errors and unknown memory codes", async () => {
    const ctx = { origin: "http://127.0.0.1:5173", token: "tok", fetch: (async () => new Response("<html>bad gateway</html>", { status: 502 })) as unknown as typeof fetch } as ApiContext;
    const error = await createRun(ctx, "agent", "{}", "ui-3-key").catch((e: unknown) => e) as ApiError;
    expect(error.status).toBe(502);
    expect(error.requiresReselection).toBeNull();
    expect(isMemoryCode(error.code)).toBe(false);
    expect(memoryErrorInfo("RESEARCH_MEMORY_SOMETHING_NEW").reselect).toBe(true);
  });

  it("explains a run that stopped using revoked history, with reselection rather than a silent retry", () => {
    const info = explainFailure("FAILED", "RESEARCH_MEMORY_REVOKED");
    expect(info.code).toBe("RESEARCH_MEMORY_REVOKED");
    expect(info.recovery).toContain("重新载入");
  });
});
