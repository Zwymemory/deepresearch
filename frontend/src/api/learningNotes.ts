// Learning-notes routes (memory phase 5). GETs are read-only and never start research or call a
// model. Paths are always built from known project/run/topic ids — a source_url in a payload is
// only compared, never fetched — so the bearer token goes nowhere but the page's own API.
import type { ApiContext } from "./endpoints";
import { ApiError } from "./http";
import { call } from "./progress";
import { learningPaths, parseLearningNotes, parseLearningSelection, parseLearningSource, type LearningNotesView, type LearningSelection, type LearningSource } from "../domain/learningNotes";

export async function getLearningNotes(ctx: ApiContext, projectId: string, signal?: AbortSignal): Promise<LearningNotesView> {
  return parseLearningNotes(await call(ctx, "GET", learningPaths.list(projectId), signal), projectId);
}

export async function getLearningSource(ctx: ApiContext, projectId: string, runId: string, signal?: AbortSignal): Promise<LearningSource> {
  return parseLearningSource(await call(ctx, "GET", learningPaths.source(projectId, runId), signal), runId);
}

/** Saves the user's note on a topic ("" clears). Returns the refreshed project list. */
export async function patchLearningNote(ctx: ApiContext, projectId: string, topicId: string, note: string): Promise<LearningNotesView> {
  return parseLearningNotes(await call(ctx, "PATCH", learningPaths.topic(projectId, topicId), undefined, { note }), projectId);
}

/** Removes only the learning topic; original reports remain. */
export async function deleteLearningTopic(ctx: ApiContext, projectId: string, topicId: string): Promise<boolean> {
  const data = (await call(ctx, "DELETE", learningPaths.topic(projectId, topicId))) as { deleted?: unknown; topic_id?: unknown };
  return data?.deleted === true && data.topic_id === topicId;
}

/** Notes frozen into an agent run's request (read-only). */
export async function getLearningSelection(ctx: ApiContext, runId: string, signal?: AbortSignal): Promise<LearningSelection> {
  return parseLearningSelection(await call(ctx, "GET", learningPaths.selection(runId), signal));
}

export function learningErrorText(error: unknown, action: "list" | "source" | "save" | "delete"): string {
  if ((error as Error)?.name === "ContractError") return "服务端返回了无法识别的学习笔记格式：" + (error as Error).message;
  const e = error as ApiError;
  const status = e?.status ?? null;
  const native = e?.message && !/^HTTP \d+$/.test(e.message) ? `服务端说明：${e.message}` : "";
  const base = status === 401 ? "身份缺失或已过期，请重新连接身份。"
    : status === 403 ? "当前身份没有访问这些学习笔记的权限。"
    : status === 404 ? (action === "source" ? "原问答不可用：可能已删除，或不在你可访问的学习笔记中。"
      : action === "list" ? "该项目不存在或无权访问。" : "学习主题不存在或已被删除。")
    : status === 400 && action === "save" ? "笔记最多 2000 个字符，未保存。"
    : status == null ? (action === "save" || action === "delete" ? "网络中断，操作结果未知；请刷新后确认。" : "网络中断，未能读取。")
    : `服务端错误（HTTP ${status}）。`;
  return native && status != null && status !== 401 ? `${base} ${native}` : base;
}
