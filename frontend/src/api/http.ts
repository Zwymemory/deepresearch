// HTTP primitives for the existing public API, ported from the V1 page.
// Bearer tokens are only ever sent to the page's own origin.
import { isMemoryCode, memoryErrorInfo } from "../domain/researchMemory";

export class ApiError extends Error {
  readonly status: number | null;
  readonly code: string;
  /** Present only on the stable memory error body `{errorCode, requiresReselection}`. */
  readonly requiresReselection: boolean | null;
  constructor(message: string, status: number | null, code = "", requiresReselection: boolean | null = null) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.requiresReselection = requiresReselection;
  }
}

/** Returns the validated same-origin base ("" means the page origin). */
export function validatedBase(value: string, location: { origin: string; href: string } = window.location): string {
  const raw = String(value || "").trim();
  const target = new URL(raw || location.origin, location.href);
  if (target.origin !== location.origin) {
    throw new ApiError("为避免 Bearer Token 误发，只允许同源 API。请从目标服务提供的页面打开。", null, "CROSS_ORIGIN");
  }
  return raw ? target.origin : "";
}

export function apiOrigin(baseUrl: string, location: { origin: string; href: string } = window.location): string {
  const raw = (baseUrl || "").trim().replace(/\/$/, "");
  if (!raw) return location.origin;
  const target = new URL(raw, location.href);
  if (target.origin !== location.origin) throw new ApiError("拒绝向跨源 API 发送 Bearer Token。", null, "CROSS_ORIGIN");
  return target.origin;
}

export function authHeaders(token: string, extra: Record<string, string> = {}): Record<string, string> {
  const headers = { ...extra };
  if (token) headers.Authorization = "Bearer " + token;
  return headers;
}

function safeJson(text: string): unknown {
  try { return JSON.parse(text); } catch { return { message: text }; }
}

export async function responseData<T>(response: Response): Promise<T> {
  const text = await response.text();
  const data = (text ? safeJson(text) : {}) as Record<string, unknown>;
  if (!response.ok) {
    const message = String(data.message || data.detail || data.error || "HTTP " + response.status);
    throw new ApiError(message, response.status, String(data.errorCode || data.code || data.error || ""),
      typeof data.requiresReselection === "boolean" ? data.requiresReselection : null);
  }
  return data as T;
}

export type ErrorContext = "workflow" | "dev-token" | "status" | "stream" | "cancel" | "legacy";

/** User-facing Chinese explanation; wording follows the V1 page. */
export function friendlyError(error: unknown, context?: ErrorContext): string {
  if (!error) return "请求失败，请检查网络连接。";
  const e = error as { name?: string; status?: number | null; message?: string };
  if (e.name === "AbortError") return "等待响应超时，服务端是否已创建任务仍未知。";
  const code = (error as { code?: string }).code;
  if (isMemoryCode(code)) return memoryErrorInfo(code).text;
  if (e.status === 401) return "Token 缺失或已过期，请重新连接身份。";
  if (e.status === 403 && context === "dev-token") return "本地 Dev Token 签发默认关闭，请开启 DEEPRESEARCH_DEV_TOKEN_ENABLED 或粘贴可信 JWT。";
  if (e.status === 403) return "当前身份没有执行此操作的权限。";
  if (e.status === 404) return "任务不存在或无权访问。";
  if (e.status === 409) return "请求冲突：幂等键与请求体不一致，或事件游标不属于当前任务。";
  if (e.status === 503 && context === "workflow") return "Durable Workflow 尚未启用。请设置 DEEPRESEARCH_WORKFLOW_ENABLED=true 并启动 Python workflow sidecar。";
  return e.message || "请求失败，请检查网络连接。";
}

/**
 * Whether a failed create may already have been accepted by the server.
 * Network errors, timeouts and 5xx (except 503 "not enabled") are unknown outcomes:
 * the only safe follow-up is replaying the identical request with the same key.
 */
export function isUnknownCreateOutcome(error: unknown, mode: "workflow" | "agent" | "legacy"): boolean {
  const status = (error as { status?: number | null })?.status ?? null;
  if (status == null) return true;
  if (mode === "legacy") return status >= 500;
  return status >= 500 && status !== 503;
}
