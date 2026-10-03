// Deterministic adapter tests: no server, no network. A fake fetch records every call.
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { normalizeCitations } from "../domain/citations";
import { applyEvent, applyView, emptyRun, isTerminal, mapLegacy, type RunState } from "../domain/runState";
import { explainFailure } from "../domain/failures";
import { snapshotRun } from "../demo/useDemoRun";
import { backoffDelay, startEventStream } from "./eventStream";
import { cancelRun, createRun, getRun, ping, type ApiContext } from "./endpoints";
import { ApiError, isUnknownCreateOutcome, validatedBase } from "./http";
import { loadIdentity, requestScope, saveIdentity, scopeKey, STORE, type Identity } from "./identity";
import { canSafelyRetry, newPending } from "./pending";
import { createSseParser } from "./sse";

const LOCATION = { origin: "http://127.0.0.1:5173", href: "http://127.0.0.1:5173/app/" };

function memoryStorage() {
  const map = new Map<string, string>();
  return { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v), removeItem: (k: string) => void map.delete(k), map };
}

type Call = { url: string; init: RequestInit };
function fakeFetch(handler: (call: Call, index: number) => Response | Promise<Response>) {
  const calls: Call[] = [];
  const fn = (async (url: string, init: RequestInit = {}) => {
    const call = { url: String(url), init };
    calls.push(call);
    return handler(call, calls.length - 1);
  }) as unknown as typeof fetch;
  return { fn, calls };
}

const sseBody = (blocks: string[]) => new Response(new ReadableStream<Uint8Array>({
  start(controller) { const enc = new TextEncoder(); blocks.forEach((b) => controller.enqueue(enc.encode(b))); controller.close(); },
}), { headers: { "Content-Type": "text/event-stream" } });

const sseEvent = (runId: string, n: number, type: string, payload: object = {}) =>
  `id: ${runId}:${n}\nevent: ${type}\ndata: ${JSON.stringify({ eventId: n, id: `${runId}:${n}`, type, payload })}\n\n`;

const header = (init: RequestInit, name: string) => (init.headers as Record<string, string> | undefined)?.[name];

describe("SSE parsing", () => {
  it("handles CRLF split across chunks, multi-line data and comments", () => {
    const parser = createSseParser();
    expect(parser.push(": keep-alive\r")).toEqual([]);
    expect(parser.push("\n\r\nid: r:1\r\nevent: X\r\ndata: {\"a\":\r")).toEqual([]);
    const out = parser.push("\ndata: 1}\r\n\r\n");
    expect(out).toEqual([{ id: "r:1", type: "X", data: "{\"a\":\n1}" }]);
  });
  it("flushes a final block without a trailing blank line", () => {
    const parser = createSseParser();
    expect(parser.push("id: r:2\ndata: {}", true)).toEqual([{ id: "r:2", type: "message", data: "{}" }]);
  });
});

describe("event stream recovery", () => {
  it("reconnects with the last applied cursor and ignores replayed duplicates", async () => {
    const runId = "run-1";
    let run: RunState = { ...emptyRun("q"), runId, status: "QUEUED" };
    const { fn, calls } = fakeFetch((_call, i) => i === 0
      ? sseBody([sseEvent(runId, 1, "STAGE_CHANGED", { stage: "PLANNING" }), sseEvent(runId, 2, "STAGE_CHANGED", { stage: "WORKING" })])
      // The server replays event 2 after reconnect; it must not be applied twice.
      : sseBody([sseEvent(runId, 2, "STAGE_CHANGED", { stage: "WORKING" }), sseEvent(runId, 3, "SUCCEEDED")]));
    const ctx: ApiContext = { origin: "http://x", token: "t", fetch: fn };
    const delays: number[] = [];
    const handle = startEventStream(ctx, runId, {
      getCursor: () => run.lastEventId,
      onEvent: (event, id) => { run = applyEvent(run, event, id); },
      onConnected: () => {}, onReconnecting: (_a, d) => delays.push(d), onFatal: () => {},
      refresh: async () => isTerminal(run.status),
    }, { sleep: async () => {} });
    await handle.done;
    expect(calls).toHaveLength(2);
    expect(header(calls[0].init, "Last-Event-ID")).toBeUndefined();
    expect(header(calls[1].init, "Last-Event-ID")).toBe("run-1:2");
    expect(header(calls[1].init, "Authorization")).toBe("Bearer t");
    expect(run.events.map((e) => e.id)).toEqual(["run-1:1", "run-1:2", "run-1:3"]);
    expect(run.status).toBe("SUCCEEDED");
    expect(delays).toEqual([1000]);
    expect(calls.every((c) => !c.url.endsWith("/cancel") && (c.init.method ?? "GET") === "GET")).toBe(true);
  });
  it("stops automatic recovery on 401/403/404/409", async () => {
    for (const status of [401, 403, 404, 409]) {
      const { fn, calls } = fakeFetch(() => new Response(JSON.stringify({ error: "x" }), { status }));
      let fatal: ApiError | null = null;
      const handle = startEventStream({ origin: "http://x", token: "t", fetch: fn }, "r", {
        getCursor: () => "", onEvent: () => {}, onConnected: () => {}, onReconnecting: () => {},
        onFatal: (e) => { fatal = e; }, refresh: async () => false,
      }, { sleep: async () => {} });
      await handle.done;
      expect(calls).toHaveLength(1);
      expect((fatal as ApiError | null)?.status).toBe(status);
    }
  });
  it("backs off 1s, 2s, 4s and caps at 8s", () => {
    expect([1, 2, 3, 4, 5].map(backoffDelay)).toEqual([1000, 2000, 4000, 8000, 8000]);
  });
  it("stopping the stream issues no cancel request", async () => {
    // Like real fetch, a pending request rejects with AbortError when its signal aborts.
    const { fn, calls } = fakeFetch((call) => new Promise<Response>((_resolve, reject) => {
      call.init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    }));
    const handle = startEventStream({ origin: "http://x", token: "t", fetch: fn }, "r", {
      getCursor: () => "", onEvent: () => {}, onConnected: () => {}, onReconnecting: () => {}, onFatal: () => {}, refresh: async () => false,
    });
    handle.stop();
    await handle.done;
    expect(calls.filter((c) => c.url.includes("/cancel"))).toHaveLength(0);
  });
});

describe("creation identity and safe retry", () => {
  const identity: Identity = { baseUrl: "", tenantId: "t1", userId: "u1", token: "secret-a", remember: false };
  const scope = requestScope(identity, LOCATION.origin);
  it("sends the exact body and key, and replays them verbatim", async () => {
    const pending = newPending("workflow", { question: "问题", requestedTools: ["kb_search"] }, scope, "ui-1-k");
    const { fn, calls } = fakeFetch(() => new Response(JSON.stringify({ runId: "r", replayed: false }), { status: 202 }));
    await createRun({ origin: "http://x", token: "secret-a", fetch: fn }, "workflow", pending.bodyJson, pending.key);
    await createRun({ origin: "http://x", token: "secret-a", fetch: fn }, "workflow", pending.bodyJson, pending.key);
    expect(calls.map((c) => [c.url, c.init.body, header(c.init, "Idempotency-Key")])).toEqual([
      ["http://x/api/research/workflows", pending.bodyJson, "ui-1-k"], ["http://x/api/research/workflows", pending.bodyJson, "ui-1-k"]]);
  });
  it("routes autonomous runs to /agents", async () => {
    const { fn, calls } = fakeFetch(() => new Response("{}", { status: 202 }));
    await createRun({ origin: "http://x", token: "t", fetch: fn }, "agent", "{}", "k");
    expect(calls[0].url).toBe("http://x/api/research/agents");
  });
  it("refuses to replay under a different identity, credential or origin", () => {
    const pending = newPending("workflow", { question: "q" }, scope);
    expect(canSafelyRetry(pending, scope).ok).toBe(true);
    expect(canSafelyRetry(pending, requestScope({ ...identity, token: "secret-b" }, LOCATION.origin)).ok).toBe(false);
    expect(canSafelyRetry(pending, requestScope({ ...identity, userId: "u2" }, LOCATION.origin)).ok).toBe(false);
    expect(canSafelyRetry(pending, requestScope(identity, "http://other")).ok).toBe(false);
  });
  it("classifies unknown outcomes like V1", () => {
    expect(isUnknownCreateOutcome(new TypeError("network"), "workflow")).toBe(true);
    expect(isUnknownCreateOutcome(new ApiError("x", 502), "workflow")).toBe(true);
    expect(isUnknownCreateOutcome(new ApiError("x", 503), "workflow")).toBe(false);
    expect(isUnknownCreateOutcome(new ApiError("x", 409), "workflow")).toBe(false);
    expect(isUnknownCreateOutcome(new ApiError("x", 503), "legacy")).toBe(true);
  });
});

describe("cancellation", () => {
  it("is a POST to the server cancel endpoint with the bearer token", async () => {
    const { fn, calls } = fakeFetch(() => new Response(JSON.stringify({ runId: "r 1", status: "CANCELLED", alreadyTerminal: false })));
    const result = await cancelRun({ origin: "http://x", token: "t", fetch: fn }, "r 1");
    expect(calls[0].url).toBe("http://x/api/research/workflows/r%201/cancel");
    expect(calls[0].init.method).toBe("POST");
    expect(header(calls[0].init, "Authorization")).toBe("Bearer t");
    expect(result.status).toBe("CANCELLED");
  });
});

describe("identity isolation", () => {
  const base: Identity = { baseUrl: "", tenantId: "t1", userId: "u1", token: "a", remember: false };
  it("partitions the run cache by origin, tenant, user and credential", () => {
    const key = scopeKey(base, LOCATION.origin);
    expect(new Set([key, scopeKey({ ...base, token: "b" }, LOCATION.origin), scopeKey({ ...base, userId: "u2" }, LOCATION.origin),
      scopeKey({ ...base, tenantId: "t2" }, LOCATION.origin), scopeKey(base, "http://other")]).size).toBe(5);
    expect(key).not.toContain("a|");
  });
  it("never writes the token to localStorage and only to sessionStorage on request", () => {
    const local = memoryStorage(), session = memoryStorage();
    saveIdentity(base, { local, session });
    expect([...local.map.values()].join()).not.toContain('"token"');
    expect(session.map.has(STORE.token)).toBe(false);
    saveIdentity({ ...base, remember: true }, { local, session });
    expect(session.map.get(STORE.token)).toBe("a");
  });
  it("discards cross-origin settings together with session credentials and runs", () => {
    const local = memoryStorage(), session = memoryStorage();
    local.setItem(STORE.auth, JSON.stringify({ baseUrl: "http://evil.test" }));
    session.setItem(STORE.token, "x"); session.setItem(STORE.run, "{}"); session.setItem(STORE.pending, "{}");
    const { identity, rejectedCrossOrigin } = loadIdentity({ local, session }, LOCATION);
    expect(rejectedCrossOrigin).toBe(true);
    expect(identity.token).toBe("");
    expect(session.map.size).toBe(0);
    expect(() => validatedBase("http://evil.test", LOCATION)).toThrow();
  });
  it("sends GET status with the current identity's bearer token only", async () => {
    const { fn, calls } = fakeFetch(() => new Response(JSON.stringify({ runId: "r", status: "QUEUED" })));
    await getRun({ origin: "http://x", token: "tok", fetch: fn }, "r");
    expect(header(calls[0].init, "Authorization")).toBe("Bearer tok");
  });
});

describe("mode-specific response shapes", () => {
  const kb = { sourceId: "kb:1", kind: "KNOWLEDGE_CHUNK", title: "a.md", excerpt: "x".repeat(60) };
  it("LangGraph workflow results have no citationDetails: sources stay 'insufficient', never guessed", () => {
    const run = applyView({ ...emptyRun("q"), runId: "r" }, { runId: "r", status: "SUCCEEDED",
      finalResponse: { answer: "A [来源1]", citations: ["kb:1"], citationContract: "INDEXED_V1", insufficientEvidence: false } });
    const [c] = normalizeCitations(run.finalResponse?.citations, run.finalResponse?.citationContract, run.finalResponse?.citationDetails);
    expect(c.kind).toBe("unknown");
    expect(c.url).toBeNull();
    expect(c.missingReason).toMatch(/唯一/);
  });
  it("Dify and autonomous results carry details and report fields", () => {
    const run = applyView({ ...emptyRun("q"), runId: "r" }, { runId: "r", status: "INSUFFICIENT_EVIDENCE",
      finalResponse: { answer: "A", citations: ["kb:1"], citationContract: "INDEXED_V1", citationDetails: [kb], report_status: "partial",
        unfinished_goals: [{ task_id: "t", reason: "r" }], claims: [{ any: "shape" }], semantic_truth_guaranteed: false } });
    expect(normalizeCitations(run.finalResponse?.citations, "INDEXED_V1", run.finalResponse?.citationDetails)[0].kind).toBe("knowledge");
    expect(run.finalResponse?.unfinished_goals).toHaveLength(1);
    expect(run.finalResponse?.semantic_truth_guaranteed).toBe(false);
  });
  it("maps Single Agent responses, keeping NONE contracts unlinked and estimated usage marked", () => {
    const ok = mapLegacy("q", { runId: "n1", finished: true, answer: "A [来源1]", citations: ["kb:1"], citationContract: "NONE",
      events: [{ seq: 1, type: "STARTED", message: "m" }, { seq: 1, type: "STARTED", message: "m" }],
      usage: { totalTokens: 0, estimated: true, modelCalls: 2 },
      memoryContext: { diagnostics: { summarySelected: true, selectedMemoryCount: 1, modelUseVerification: "unknown" } } });
    expect(ok.status).toBe("SUCCEEDED");
    expect(ok.mode).toBe("legacy");
    expect(ok.events).toHaveLength(1);
    expect(ok.usage?.totalTokens).toBe(0);
    expect(ok.usage?.estimated).toBe(true);
    expect(ok.memoryContext?.modelUseVerification).toBe("unknown");
    expect(normalizeCitations(ok.finalResponse?.citations, ok.finalResponse?.citationContract, [])[0].indexed).toBe(false);
    const failed = mapLegacy("q", { finished: false, status: "max rounds reached" });
    expect([failed.status, failed.errorCode]).toEqual(["MAX_ROUNDS_REACHED", "MAX_ROUNDS_REACHED"]);
    expect(mapLegacy("q", { finished: false, status: "WEIRD" }).status).toBe("FAILED");
  });
  it("explains failures by their specific code", () => {
    expect(explainFailure("FAILED", "DIFY_MODEL_OUTPUT_EMPTY").label).toBe("模型最终输出为空");
    expect(explainFailure("FAILED", null).code).toBe("FAILED");
    expect(explainFailure("ODD", "NEW_CODE").label).toBe("研究任务未完成");
  });
  it("tells the Java service apart from the preview mock", async () => {
    const java = fakeFetch(() => new Response("DeepResearch is up. model=x"));
    const mock = fakeFetch(() => new Response(JSON.stringify({ status: "ok", preview: true })));
    expect((await ping({ origin: "http://x", token: "", fetch: java.fn })).kind).toBe("java");
    expect((await ping({ origin: "http://x", token: "", fetch: mock.fn })).kind).toBe("preview");
  });
});

describe("demo isolation", () => {
  it("demo playback performs no network requests", () => {
    const original = globalThis.fetch;
    let called = 0;
    globalThis.fetch = (() => { called += 1; throw new Error("network in demo"); }) as typeof fetch;
    try {
      for (const outcome of ["success", "partial"] as const) snapshotRun(outcome, Infinity);
    } finally {
      globalThis.fetch = original;
    }
    expect(called).toBe(0);
  });
  it("demo modules never import the API layer", () => {
    const dir = join(fileURLToPath(new URL(".", import.meta.url)), "../demo");
    for (const file of readdirSync(dir)) {
      expect(readFileSync(join(dir, file), "utf8")).not.toMatch(/from "\.\.\/api\//);
    }
  });
});
