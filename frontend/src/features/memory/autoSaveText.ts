import type { ProgressSaveView } from "../../domain/progressMemory";

/** Concise Chinese reasons for known save error codes; the raw code stays in technical details only. */
const SAVE_ERRORS: Record<string, string> = {
  PROGRESS_CAPACITY_EXCEEDED: "研究进度超出快照容量",
  PROGRESS_SAVE_FAILED: "服务端保存时出错",
  PROGRESS_SOURCE_CHANGED: "所引用的来源已变化",
};
export const saveErrorReason = (code: string | null) => (code ? SAVE_ERRORS[code] ?? "服务端未说明原因" : "服务端未说明原因");

/** Chinese wording for the automatic-save status; manual or unrecorded saves are never called automatic. */
export function autoSaveText(v: ProgressSaveView, exhausted: boolean): string {
  return (
    v.status === "WAITING" ? "运行结束后会自动保存研究进度。"
    : v.status === "PENDING" ? (exhausted ? "服务端尚未确认自动保存；可以稍后刷新查看。" : "正在自动保存研究进度，等待服务端确认…")
    : v.status === "SAVED" ? (v.saveOrigin === "automatic" ? "已自动保存已完成事项与待办，可在研究档案查看。"
      : v.saveOrigin === "manual" ? "研究进度已手动保存，可在研究档案查看。"
      : "研究进度已保存，可在研究档案查看（未说明保存方式）。")
    : v.status === "FAILED" ? `自动保存失败：${saveErrorReason(v.errorCode)}。原报告不受影响；可以用上方“保存研究进度”手动重试。`
    : v.status === "DELETED" ? "保存的研究进度已被删除，之后不会再提供给新的会话。"
    : v.status === "NOT_ENABLED" ? "这次运行没有启用自动保存。"
    : v.status === "UNAVAILABLE" ? `保存的研究进度当前不可用：${saveErrorReason(v.errorCode)}，不会提供给新的会话。`
    : "无法确认自动保存状态。"
  );
}
