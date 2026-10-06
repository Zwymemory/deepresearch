// Research progress memory routes (RESEARCH_PROGRESS_FRONTEND_HANDOFF_2026-10-05).
// All requests have no JSON body. Errors branch on HTTP status only; bodies may not be JSON.
// The new-session POST is not idempotent and is never retried automatically.
import type { ApiContext } from "./endpoints";
import { parseRecall, type RecallView } from "../domain/memoryRecall";
import { ApiError, authHeaders, responseData } from "./http";
import { parseList, parseProgressSave, parseProject, parseResume, parseSnapshot, type ProgressSaveView, type ProgressSnapshot, type ResumeContext } from "../domain/progressMemory";

const f = (ctx: ApiContext) => ctx.fetch ?? globalThis.fetch.bind(globalThis);
const enc = encodeURIComponent;

async function call(ctx: ApiContext, method: string, path: string, signal?: AbortSignal, body?: unknown): Promise<unknown> {
  const response = await f(ctx)(ctx.origin + path, {
    method, cache: "no-store", signal,
    headers: authHeaders(ctx.token, body === undefined ? { Accept: "application/json" } : { Accept: "application/json", "Content-Type": "application/json" }),
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return responseData<unknown>(response);
}

/** Owned autonomous run → its project. 404 for unsupported, unknown or inaccessible runs. */
export async function discoverProject(ctx: ApiContext, runId: string, signal?: AbortSignal) {
  return parseProject(await call(ctx, "GET", `/api/research/agents/${enc(runId)}/progress-project`, signal));
}

/** Save or refresh the single snapshot for (project, run) from server records. */
export async function saveProgress(ctx: ApiContext, projectId: string, runId: string): Promise<ProgressSnapshot> {
  return parseSnapshot(await call(ctx, "PUT", `/api/research/projects/${enc(projectId)}/progress/runs/${enc(runId)}`));
}

/** Automatic-save status of an owned agent run (read-only; never triggers a save). */
export async function getProgressSave(ctx: ApiContext, runId: string, signal?: AbortSignal): Promise<ProgressSaveView> {
  return parseProgressSave(await call(ctx, "GET", `/api/research/agents/${enc(runId)}/progress-save`, signal));
}

/** User correction note (≤2000 chars; "" clears). Returns the snapshot; never edits verified facts. */
export async function correctProgress(ctx: ApiContext, projectId: string, runId: string, note: string): Promise<ProgressSnapshot> {
  return parseSnapshot(await call(ctx, "PATCH", `/api/research/projects/${enc(projectId)}/progress/runs/${enc(runId)}`, undefined, { note }));
}

/** Cross-question recall of an owned agent run (read-only; never selects or saves anything). */
export async function getMemoryRecall(ctx: ApiContext, runId: string, signal?: AbortSignal): Promise<RecallView> {
  return parseRecall(await call(ctx, "GET", `/api/research/agents/${enc(runId)}/memory-recall`, signal));
}

/** Read a saved snapshot; never saves implicitly. */
export async function readProgress(ctx: ApiContext, projectId: string, runId: string, signal?: AbortSignal): Promise<ProgressSnapshot> {
  return parseSnapshot(await call(ctx, "GET", `/api/research/projects/${enc(projectId)}/progress/runs/${enc(runId)}`, signal));
}

/** Latest ≤20 accessible candidates across the owner's projects; no total, no pagination. */
export async function listProgress(ctx: ApiContext, signal?: AbortSignal) {
  return parseList(await call(ctx, "GET", "/api/research/progress", signal));
}

/** Creates a new, empty owned session and returns project-level context. Not idempotent. */
export async function createResumeContext(ctx: ApiContext, projectId: string): Promise<ResumeContext> {
  return parseResume(await call(ctx, "POST", `/api/research/projects/${enc(projectId)}/resume-context`));
}

/** Re-read context for an existing eligible session (recovery); creates nothing. */
export async function getResumeContext(ctx: ApiContext, projectId: string, sessionId: string, signal?: AbortSignal): Promise<ResumeContext> {
  return parseResume(await call(ctx, "GET", `/api/research/projects/${enc(projectId)}/resume-context?sessionId=${enc(sessionId)}`, signal));
}

/** Deletes only the saved snapshot. `deleted:false` (already removed) also allows removing it from the notebook. */
export async function deleteProgress(ctx: ApiContext, projectId: string, runId: string): Promise<{ deleted: boolean }> {
  const data = (await call(ctx, "DELETE", `/api/research/projects/${enc(projectId)}/progress/runs/${enc(runId)}`)) as { deleted?: unknown };
  return { deleted: data?.deleted === true };
}

/** User-facing explanation by HTTP status; Chinese reason strings from the server are never parsed. */
export function progressErrorText(error: unknown, action: "discover" | "save" | "read" | "list" | "load" | "delete" | "correct"): string {
  const status = (error as ApiError)?.status ?? null;
  if ((error as Error)?.name === "ContractError") return "服务端返回了无法识别的研究进度格式：" + (error as Error).message;
  if (status === 401) return "身份缺失或已过期，请重新连接身份。";
  if (status === 404) return action === "discover" ? "这个运行不支持保存研究进度（只支持你有权访问的自主研究运行）。"
    : action === "load" ? "该项目或会话不存在、无权访问，或其中的研究进度已失效。"
    : "记录不存在、无权访问，或其出处已不再可用。";
  if (status === 400 && action === "correct") return "纠正说明最多 2000 个字符，未保存。";
  if (status === 413 && action === "correct") return "加入纠正说明后超过快照容量，未保存。";
  if (status === 413) return "这条研究进度超过初始版本的 60,000 字节上限，无法保存；页面不会截断内容。";
  if (status === 400) return "请求格式不正确，未自动重试。";
  if (status == null) return action === "load" ? "网络中断：服务端可能已经创建了一个空会话。页面不会自动重试。" : "网络中断，操作结果未知。";
  return action === "load" ? `服务端错误（HTTP ${status}）：可能已创建空会话，页面不会自动重试。` : `服务端错误（HTTP ${status}），可以手动重试。`;
}

export function isApiStatus(error: unknown, status: number) {
  return (error as ApiError)?.status === status;
}
