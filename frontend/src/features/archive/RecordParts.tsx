// Pieces of a saved research-progress record, shared by the archive's selection panel and
// reading layer. Everything shown comes from the snapshot as saved; nothing is re-verified.
import { useState } from "react";
import { CLAIM_STATUS, recordKey, type ProgressGoal, type ProgressSnapshot } from "../../domain/progressMemory";
import type { LoadState } from "../../live/useNotebook";
import { Icon } from "../../ui/Icon";
import { shortId, statusLabel } from "./recordText";

const GOAL_STATUS: Record<string, string> = { done: "已完成", blocked: "待解决", pending: "待执行", running: "执行中", uncovered: "尚未核查", resolved: "已核查", FAILED: "失败" };
function Goals({ index, title, goals, empty }: { index: string; title: string; goals: ProgressGoal[]; empty: string }) {
  return (
    <section className="rec-section">
      <h2 className="rec-h"><span className="rec-n" aria-hidden="true">{index}</span>{title}</h2>
      {goals.length ? (
        <ul className="nb-goals">
          {goals.map((g, i) => (
            <li key={i}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="chip">{GOAL_STATUS[g.status] ?? g.status}</span>
                <strong style={{ overflowWrap: "anywhere" }}>{g.goal}</strong>
                {g.completionVerified ? <span className="chip chip-ok">保存时已有完成证明</span> : null}
                {g.errorCode ? <code>{g.errorCode}</code> : null}
              </div>
              {g.gaps.length ? <ul className="nb-gaps">{g.gaps.map((gap, j) => <li key={j}>{gap}</li>)}</ul> : null}
              {g.criteria.length ? <ul className="nb-gaps">{g.criteria.map((c, j) => (
                <li key={j}><span className="note">{GOAL_STATUS[c.status] ?? c.status}</span>　{c.text}{c.gaps.length ? <span className="note">（{c.gaps.join("；")}）</span> : null}</li>
              ))}</ul> : null}
            </li>
          ))}
        </ul>
      ) : <p className="note">{empty}</p>}
    </section>
  );
}

/** The snapshot as a readable document: numbered sections, report-sized text. */
export function RecordSections({ record }: { record: ProgressSnapshot }) {
  return (
    <div className="rec-body">
      <Goals index="01" title="已完成的工作（保存时的记录，不是重新核验）" goals={record.completedWork} empty="没有记录到已完成的工作。" />
      <Goals index="02" title="尚未解决的问题" goals={record.unresolvedQuestions} empty="没有记录到未解决的问题。" />
      <section className="rec-section">
        <h2 className="rec-h"><span className="rec-n" aria-hidden="true">03</span>下一步</h2>
        {record.nextSteps.length ? <ul className="nb-gaps rec-list">{record.nextSteps.map((s, i) => <li key={i}>{s}</li>)}</ul> : <p className="note">未记录</p>}
      </section>
      <section className="rec-section">
        <h2 className="rec-h"><span className="rec-n" aria-hidden="true">04</span>出处（标识与当时状态，不是标题或链接）</h2>
        {record.sourceClaims.length ? <ul className="grid gap-2">{record.sourceClaims.map((c) => {
          const s = CLAIM_STATUS[c.decisionStatus] ?? { label: c.decisionStatus, chip: "chip" };
          return <li key={c.claimId} className="flex flex-wrap items-center gap-2"><span className={"chip " + s.chip}>{s.label}</span><span className="source-id">论断 {shortId(c.claimId)}{c.freshness ? ` · ${c.freshness === "fresh" ? "引用记录仍有效" : c.freshness}` : ""}</span></li>;
        })}</ul> : null}
        {record.sourceEvidence.length ? <p className="source-id" style={{ marginTop: 8 }}>证据 {record.sourceEvidence.map((e) => shortId(e.evidenceId)).join("、")}</p> : null}
        {!record.sourceClaims.length && !record.sourceEvidence.length ? <p className="note">未记录</p> : null}
      </section>
    </div>
  );
}

/** Load and two-step delete. Loading creates a new, empty session; it never starts research. */
export function RecordActions({ onLoad, loadBusy, loadedHere, onDelete, deleting, deleteError }: {
  onLoad: () => void; loadBusy: boolean;
  /** This project's context is already loaded into a new session; another load creates another session. */
  loadedHere: boolean;
  onDelete: () => void; deleting: boolean; deleteError: string | null;
}) {
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="grid gap-3">
      <div className="rec-actions">
        <button type="button" className="btn btn-primary btn-sm" onClick={onLoad} disabled={loadBusy || loadedHere}>
          {loadBusy ? "正在载入…" : loadedHere ? "已载入到新会话" : "载入此项目的研究进度到新会话"}
        </button>
        {loadedHere && !loadBusy ? <button type="button" className="btn btn-quiet btn-sm" onClick={onLoad}>另建一个新会话再次载入</button> : null}
        {confirming ? <div className="flex flex-wrap gap-2">
          <button type="button" className="btn btn-danger btn-sm" onClick={onDelete} disabled={deleting}>{deleting ? "正在删除…" : "确认删除"}</button>
          <button type="button" className="btn btn-quiet btn-sm" onClick={() => setConfirming(false)} disabled={deleting}>取消</button>
        </div> : <button type="button" className="btn btn-quiet btn-sm" onClick={() => setConfirming(true)}>删除保存的进度…</button>}
      </div>
      <p className="note">载入会新建一个空会话，并返回该项目最近的研究进度（可能不止这一条）；不会开始研究。删除只移除保存的进度，不删除原始运行、证据或会话。</p>
      {deleteError ? <p className="missing" role="alert">{deleteError}</p> : null}
    </div>
  );
}

export function LoadedPanel({ load, onClear, onRetry }: { load: LoadState; onClear: () => void; onRetry: (projectId: string) => void }) {
  if (load.state === "loading") return <p className="note" role="status">正在新建会话并读取该项目的研究进度…</p>;
  if (load.state === "failed") return (
    <div className="unknown" role="alert" style={{ marginTop: 0 }}>
      <strong><Icon name="alert" size={16} />载入未完成</strong>
      <p>{load.message}</p>
      {load.maybeCreated ? <button type="button" className="btn btn-quiet btn-sm" style={{ justifySelf: "start" }} onClick={() => onRetry(load.projectId)}>再次载入（会再新建一个会话）</button> : null}
    </div>
  );
  if (load.state !== "loaded") return null;
  const { context } = load;
  return (
    <div className="limits loaded-panel" role="status" style={{ marginTop: 0 }}>
      <p><strong>已载入历史研究进度（尚未传入模型；未开始研究）</strong>{load.recovered ? <span className="note">　已用会话 ID 重新读取</span> : null}</p>
      <p className="note" style={{ marginTop: 4 }}>项目级载入：返回 {context.progress.length} 条研究进度。新会话 <span className="source-id">{shortId(context.targetSessionId)}</span></p>
      {context.progress.length ? <ul className="nb-gaps" style={{ marginTop: 6 }}>{context.progress.map((p) => (
        <li key={recordKey(p.projectId, p.sourceRunId)}>{p.originalGoal}<span className="note">（保存时：{statusLabel(p.runStatus)}，未解决 {p.unresolvedQuestions.length} 项）</span></li>
      ))}</ul> : <p className="note">该项目当前没有可用的研究进度。</p>}
      {context.usageInstruction ? <p className="note" style={{ marginTop: 6 }}>服务端说明：{context.usageInstruction}</p> : null}
      <button type="button" className="link-btn" style={{ marginTop: 6 }} onClick={onClear}>清除载入记录</button>
    </div>
  );
}
