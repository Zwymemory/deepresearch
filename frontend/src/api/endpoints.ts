// Typed callers for the established public routes. Nothing here calls
// /internal/** or handles delegated execution credentials.
import { ApiError, authHeaders, responseData } from "./http";
import type { EvidenceView, EvidenceViewResult } from "../domain/evidenceView";
import type { RunEvent, WorkflowView } from "../domain/types";

export interface ApiContext {
  origin: string;
  token: string;
  fetch?: typeof fetch;
}

const f = (ctx: ApiContext) => ctx.fetch ?? globalThis.fetch.bind(globalThis);

/** WorkflowDtos.Accepted */
/** statusUrl / eventsUrl are returned by the server; for this API they name the existing workflow routes used below. */
export interface Accepted { runId: string; sessionId?: string; status?: string; stage?: string; replayed?: boolean; statusUrl?: string; eventsUrl?: string }
/** WorkflowDtos.Cancelled */
export interface Cancelled { runId: string; status: string; alreadyTerminal: boolean }

/** AgentResearchResponse (Single Agent baseline, synchronous). */
export interface LegacyResponse {
  runId?: string;
  sessionId?: string;
  answer?: string;
  rounds?: number;
  finished?: boolean;
  status?: string;
  citations?: string[];
  citationContract?: string;
  citationDetails?: unknown;
  steps?: Array<{ round: number; action: string; decisionSummary?: string; outcomeCode?: string }>;
  events?: Array<{ seq: number; type: string; message?: string; round?: number | null; action?: string }>;
  usage?: { inputTokens?: number; outputTokens?: number; totalTokens?: number; estimated?: boolean; estimatedCost?: number;
    costCurrency?: string; durationMs?: number; modelCalls?: number; toolCalls?: number } | null;
  memoryContext?: { sessionSummary?: string | null; recentConversation?: string[]; memories?: string[];
    diagnostics?: { summarySelected?: boolean; recentMessageCount?: number; selectedMemoryCount?: number; totalMemoryCount?: number;
      reason?: string; modelUseVerification?: string } } | null;
}

export type PingResult = { kind: "java" | "preview"; detail: string };

/** `/api/ping` is plain text on the Java service; the preview mock answers JSON with `preview: true`. */
export async function ping(ctx: ApiContext, signal?: AbortSignal): Promise<PingResult> {
  const response = await f(ctx)(ctx.origin + "/api/ping", { cache: "no-store", signal });
  if (!response.ok) throw new ApiError("HTTP " + response.status, response.status);
  const text = await response.text();
  try {
    const json = JSON.parse(text) as { preview?: boolean };
    if (json && json.preview === true) return { kind: "preview", detail: "预览服务器（模拟 API）" };
  } catch { /* plain text from Java */ }
  return { kind: "java", detail: text.slice(0, 120) };
}

/** `configured` is null when unknown; false blocks web search before submission. */
export async function webSearchConfigured(ctx: ApiContext, signal?: AbortSignal): Promise<boolean | null> {
  if (!ctx.token) return null;
  try {
    const response = await f(ctx)(ctx.origin + "/api/research/tools/capabilities", {
      headers: authHeaders(ctx.token, { Accept: "application/json" }), cache: "no-store", signal,
    });
    if (!response.ok) return null;
    const data = (await response.json()) as { webSearch?: { configured?: unknown } };
    return typeof data.webSearch?.configured === "boolean" ? data.webSearch.configured : null;
  } catch {
    return null;
  }
}

/** Local development only; the service rejects it unless explicitly enabled. Requests the USER role. */
export async function issueDevToken(ctx: Omit<ApiContext, "token">, tenantId: string, userId: string): Promise<string> {
  const response = await f({ ...ctx, token: "" })(ctx.origin + "/api/auth/dev-token", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ tenantId, userId, roles: ["USER"], ttlSeconds: 7200 }),
  });
  const data = await responseData<{ token?: string }>(response);
  return data.token || "";
}

export async function createRun(ctx: ApiContext, mode: "workflow" | "agent", bodyJson: string, key: string, signal?: AbortSignal): Promise<Accepted> {
  const path = mode === "agent" ? "/api/research/agents" : "/api/research/workflows";
  const response = await f(ctx)(ctx.origin + path, {
    method: "POST",
    headers: authHeaders(ctx.token, { "Content-Type": "application/json", "Idempotency-Key": key }),
    body: bodyJson, signal,
  });
  return responseData<Accepted>(response);
}

export async function runLegacy(ctx: ApiContext, bodyJson: string, key: string, signal?: AbortSignal): Promise<LegacyResponse> {
  const response = await f(ctx)(ctx.origin + "/api/research/agent", {
    method: "POST",
    headers: authHeaders(ctx.token, { "Content-Type": "application/json", "Idempotency-Key": key }),
    body: bodyJson, signal,
  });
  return responseData<LegacyResponse>(response);
}

export async function getRun(ctx: ApiContext, runId: string, signal?: AbortSignal): Promise<WorkflowView> {
  const response = await f(ctx)(`${ctx.origin}/api/research/workflows/${encodeURIComponent(runId)}`, {
    headers: authHeaders(ctx.token, { Accept: "application/json" }), cache: "no-store", signal,
  });
  return responseData<WorkflowView>(response);
}

/** Server-side cancellation. Closing the event stream alone never cancels a run. */
export async function cancelRun(ctx: ApiContext, runId: string): Promise<Cancelled> {
  const response = await f(ctx)(`${ctx.origin}/api/research/workflows/${encodeURIComponent(runId)}/cancel`, {
    method: "POST", headers: authHeaders(ctx.token, { Accept: "application/json" }),
  });
  return responseData<Cancelled>(response);
}

export async function openEventStream(ctx: ApiContext, runId: string, lastEventId: string, signal: AbortSignal): Promise<ReadableStream<Uint8Array>> {
  const headers = authHeaders(ctx.token, { Accept: "text/event-stream" });
  if (lastEventId) headers["Last-Event-ID"] = lastEventId;
  const response = await f(ctx)(`${ctx.origin}/api/research/workflows/${encodeURIComponent(runId)}/events`, { headers, cache: "no-store", signal });
  if (!response.ok) await responseData(response);
  if (!response.body) throw new ApiError("当前浏览器不支持 fetch streaming", null);
  return response.body;
}

export function parseEventData(data: string): RunEvent | null {
  try {
    const value = JSON.parse(data) as RunEvent;
    return value && typeof value === "object" ? value : null;
  } catch {
    return null;
  }
}

/**
 * Public evidence read API (autonomous runs). 503 EVIDENCE_VIEW_DISABLED and 409
 * EVIDENCE_VIEW_INTEGRITY_INVALID are capability states, not transport errors.
 */
export async function getEvidenceView(ctx: ApiContext, runId: string, signal?: AbortSignal): Promise<EvidenceViewResult> {
  const response = await f(ctx)(`${ctx.origin}/api/research/workflows/${encodeURIComponent(runId)}/evidence`, {
    headers: authHeaders(ctx.token, { Accept: "application/json" }), cache: "no-store", signal,
  });
  if (response.status === 503) return { state: "disabled" };
  if (response.status === 409) return { state: "integrity" };
  const view = await responseData<EvidenceView>(response);
  if (view?.schemaVersion !== "evidence-view/1") return { state: "error", message: "证据记录格式未知：" + String(view?.schemaVersion) };
  return { state: "ok", view };
}
