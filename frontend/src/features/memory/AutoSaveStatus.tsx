import type { ProgressSaveView } from "../../domain/progressMemory";
import { autoSaveText } from "./autoSaveText";

export type AutoSaveRead = { state: "loading" } | { state: "absent" } | { state: "error"; message: string } | { state: "ok"; view: ProgressSaveView; exhausted: boolean };

/**
 * Automatic progress-save status of an autonomous run (read-only). The page never starts an
 * automatic save; it only reports the server's state. A failure never hides the report, and the
 * existing manual save button remains the explicit retry. Saving is not research success or verification.
 */
export function AutoSaveStatus({ read }: { read: AutoSaveRead }) {
  if (read.state === "absent") return null;
  if (read.state === "loading") return <p className="note auto-save" role="status">正在读取研究进度的自动保存状态…</p>;
  if (read.state === "error") return <p className="note auto-save" role="status">无法读取自动保存状态（{read.message}）。报告不受影响。</p>;
  const v = read.view;
  const text = autoSaveText(v, read.exhausted);
  return (
    <div className="note auto-save" role="status" data-status={v.status}>
      <p>{text}</p>
      {v.errorCode ? <details><summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary><p className="source-id">{v.errorCode}</p></details> : null}
    </div>
  );
}
