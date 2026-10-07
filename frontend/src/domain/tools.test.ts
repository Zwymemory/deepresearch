// Follow-up creation boundary: the run view reports the server-expanded capability list
// (real autonomous run: calculator, check_claims, read_source, web_search); only public tools
// may be sent back, nothing may be added, and mode / session are kept.
import { describe, expect, it } from "vitest";
import { loadPending, newPending, savePending } from "../api/pending";
import { applyView, emptyRun } from "./runState";
import { creationTools, followUpRequest, publicTools } from "./tools";

const EXPANDED = ["calculator", "check_claims", "read_source", "web_search"];
const scope = { origin: "http://127.0.0.1:5173", tenantId: "t", userId: "u", credential: "c" } as never;

describe("public tool boundary for create requests", () => {
  it("drops internal capabilities and keeps the user's public selection", () => {
    expect(publicTools(EXPANDED)).toEqual(["calculator", "web_search"]);
    expect(publicTools(["web_search", "read_source", "check_claims"])).toEqual(["web_search"]);
    expect(publicTools(["kb_search", " KB_SEARCH ", "kb_search", "publish_evidence", 7, null])).toEqual(["kb_search"]);
  });

  it("the run state from a real expanded view holds only public tools", () => {
    const run = applyView(emptyRun("q", [], "agent"), { runId: "wf-1", sessionId: "s-1", status: "SUCCEEDED", requestedTools: EXPANDED });
    expect(run.tools).toEqual(["calculator", "web_search"]);
  });

  it("a follow-up keeps mode and session, and a web-only report stays web-only", () => {
    const agent = applyView(emptyRun("q", [], "agent"), { runId: "wf-1", sessionId: "sess-original", status: "SUCCEEDED", requestedTools: EXPANDED });
    expect(followUpRequest(agent)).toEqual({ ok: true, tools: ["calculator", "web_search"], mode: "agent", sessionId: "sess-original" });
    const webOnly = applyView(emptyRun("q", [], "agent"), { runId: "wf-2", sessionId: "s-2", status: "SUCCEEDED", requestedTools: ["check_claims", "read_source", "web_search"] });
    const request = followUpRequest(webOnly);
    expect(request).toMatchObject({ ok: true, tools: ["web_search"], mode: "agent", sessionId: "s-2" });
    const workflow = followUpRequest({ mode: "workflow", sessionId: "w-s", tools: ["kb_search"] });
    expect(workflow).toEqual({ ok: true, tools: ["kb_search"], mode: "workflow", sessionId: "w-s" });
  });

  it("refuses instead of defaulting when no public tool is known", () => {
    expect(followUpRequest({ mode: "agent", sessionId: "s", tools: ["check_claims", "read_source"] })).toMatchObject({ ok: false, mode: "agent", sessionId: "s" });
    expect(creationTools("workflow", [])).toMatchObject({ ok: false });
    expect(creationTools("legacy", EXPANDED)).toEqual({ ok: true, tools: [] });
  });

  it("the serialized new request never contains internal tools", () => {
    const selection = creationTools("agent", EXPANDED);
    if (!selection.ok) throw new Error("expected a selection");
    const pending = newPending("agent", { question: "这道题的答案是？", requestedTools: selection.tools, sessionId: "sess-original" }, scope);
    expect(JSON.parse(pending.bodyJson)).toEqual({ question: "这道题的答案是？", requestedTools: ["calculator", "web_search"], sessionId: "sess-original" });
    expect(pending.bodyJson).not.toMatch(/check_claims|read_source/);
  });

  it("an already-persisted request is replayed with its exact bytes (not re-normalized)", () => {
    const map = new Map<string, string>();
    const session = { getItem: (k: string) => map.get(k) ?? null, setItem: (k: string, v: string) => void map.set(k, v), removeItem: (k: string) => void map.delete(k) };
    const stored = newPending("agent", { question: "q", requestedTools: EXPANDED, sessionId: "s" }, scope, "ui-key");
    savePending(stored, { session } as never);
    const loaded = loadPending({ session } as never);
    expect(loaded?.key).toBe("ui-key");
    expect(loaded?.bodyJson).toBe(stored.bodyJson);
  });
});
