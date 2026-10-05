import { useState } from "react";
import { STAGE_LABELS } from "../../domain/eventText";
import { CLAIM_STATUS, recordKey, type ProgressGoal, type ProgressSnapshot } from "../../domain/progressMemory";
import type { LoadState } from "../../live/useNotebook";
import { Icon } from "../../ui/Icon";
import { Sheet } from "../shell/Dialogs";

const GOAL_STATUS: Record<string, string> = { done: "已完成", blocked: "待解决", pending: "待执行", running: "执行中", uncovered: "尚未核查", resolved: "已核查", FAILED: "失败" };
const short = (id: string) => (id.length > 18 ? id.slice(0, 8) + "…" + id.slice(-6) : id);

function Goals({ title, goals, empty }: { title: string; goals: ProgressGoal[]; empty: string }) {
  return (
    <section>
      <h4 className="insp-label">{title}</h4>
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

function RecordDetail({ record, onBack, onLoad, loadBusy, loadedHere, onDelete, deleting, deleteError }: {
  record: ProgressSnapshot; onBack: () => void; onLoad: () => void; loadBusy: boolean;
  /** This project's context is already loaded into a new session; another load creates another session. */
  loadedHere: boolean;
  onDelete: () => void; deleting: boolean; deleteError: string | null;
}) {
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="grid gap-3">
      <button type="button" className="link-btn" style={{ justifySelf: "start" }} onClick={onBack}>← 返回列表</button>
      <h3 style={{ font: "600 18px/1.45 var(--font-serif)", overflowWrap: "anywhere" }}>{record.originalGoal}</h3>
      <div className="meta-row">
        <span className="chip" title="保存时的运行状态，不是实时状态">保存时：{STAGE_LABELS[record.runStatus] ?? record.runStatus}</span>
        <span className="source-id">项目 {short(record.projectId)} · 运行 {short(record.sourceRunId)}</span>
      </div>
      <Goals title="已完成的工作（保存时的记录，不是重新核验）" goals={record.completedWork} empty="没有记录到已完成的工作。" />
      <Goals title="尚未解决的问题" goals={record.unresolvedQuestions} empty="没有记录到未解决的问题。" />
      <section>
        <h4 className="insp-label">下一步</h4>
        {record.nextSteps.length ? <ul className="nb-gaps">{record.nextSteps.map((s, i) => <li key={i}>{s}</li>)}</ul> : <p className="note">未记录</p>}
      </section>
      <section>
        <h4 className="insp-label">出处（标识与当时状态，不是标题或链接）</h4>
        {record.sourceClaims.length ? <ul className="grid gap-1">{record.sourceClaims.map((c) => {
          const s = CLAIM_STATUS[c.decisionStatus] ?? { label: c.decisionStatus, chip: "chip" };
          return <li key={c.claimId} className="flex flex-wrap items-center gap-2"><span className={"chip " + s.chip}>{s.label}</span><span className="source-id">论断 {short(c.claimId)}{c.freshness ? ` · ${c.freshness === "fresh" ? "引用记录仍有效" : c.freshness}` : ""}</span></li>;
        })}</ul> : null}
        {record.sourceEvidence.length ? <p className="source-id" style={{ marginTop: 6 }}>证据 {record.sourceEvidence.map((e) => short(e.evidenceId)).join("、")}</p> : null}
        {!record.sourceClaims.length && !record.sourceEvidence.length ? <p className="note">未记录</p> : null}
      </section>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn btn-primary btn-sm" onClick={onLoad} disabled={loadBusy || loadedHere}>
          {loadBusy ? "正在载入…" : loadedHere ? "已载入到新会话" : "载入此项目的研究进度到新会话"}
        </button>
        {loadedHere && !loadBusy ? <button type="button" className="btn btn-quiet btn-sm" onClick={onLoad}>另建一个新会话再次载入</button> : null}
        {confirming ? <>
          <button type="button" className="btn btn-danger btn-sm" onClick={onDelete} disabled={deleting}>{deleting ? "正在删除…" : "确认删除"}</button>
          <button type="button" className="btn btn-quiet btn-sm" onClick={() => setConfirming(false)} disabled={deleting}>取消</button>
        </> : <button type="button" className="btn btn-quiet btn-sm" onClick={() => setConfirming(true)}>删除保存的进度…</button>}
      </div>
      <p className="note">载入会新建一个空会话，并返回该项目最近的研究进度（可能不止这一条）；不会开始研究。删除只移除保存的进度，不删除原始运行、证据或会话。</p>
      {deleteError ? <p className="missing" role="alert">{deleteError}</p> : null}
    </div>
  );
}

function LoadedPanel({ load, onClear, onRetry }: { load: LoadState; onClear: () => void; onRetry: (projectId: string) => void }) {
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
    <div className="limits" role="status" style={{ marginTop: 0 }}>
      <p><strong>已载入历史研究进度（尚未传入模型；未开始研究）</strong>{load.recovered ? <span className="note">　已用会话 ID 重新读取</span> : null}</p>
      <p className="note" style={{ marginTop: 4 }}>项目级载入：返回 {context.progress.length} 条研究进度。新会话 <span className="source-id">{short(context.targetSessionId)}</span></p>
      {context.progress.length ? <ul className="nb-gaps" style={{ marginTop: 6 }}>{context.progress.map((p) => (
        <li key={recordKey(p.projectId, p.sourceRunId)}>{p.originalGoal}<span className="note">（保存时：{STAGE_LABELS[p.runStatus] ?? p.runStatus}，未解决 {p.unresolvedQuestions.length} 项）</span></li>
      ))}</ul> : <p className="note">该项目当前没有可用的研究进度。</p>}
      {context.usageInstruction ? <p className="note" style={{ marginTop: 6 }}>服务端说明：{context.usageInstruction}</p> : null}
      <button type="button" className="link-btn" style={{ marginTop: 6 }} onClick={onClear}>清除载入记录</button>
    </div>
  );
}

export function NotebookDialog({ open, onOpenChange, mode, needsIdentity, onOpenIdentity, items, loading, error, candidateLimit, onRefresh,
  load, onLoad, onClearLoaded, onDelete, deletingKey, deleteError }: {
  open: boolean; onOpenChange: (open: boolean) => void;
  /** "live" shows only server data; "preview" is the explicitly selected synthetic demo. */
  mode: "live" | "preview";
  needsIdentity: boolean; onOpenIdentity: () => void;
  items: ProgressSnapshot[]; loading: boolean; error: string | null; candidateLimit: number | null; onRefresh: () => void;
  load: LoadState; onLoad: (projectId: string) => void; onClearLoaded: () => void;
  onDelete: (record: ProgressSnapshot) => Promise<boolean>; deletingKey: string | null; deleteError: { key: string; message: string } | null;
}) {
  const [selected, setSelected] = useState<string | null>(null);
  const record = items.find((r) => recordKey(r.projectId, r.sourceRunId) === selected) ?? null;
  return (
    <Sheet open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) setSelected(null); }} title="研究笔记"
      description="保存的研究进度是不可信的历史上下文，不是新核验的证据；载入不会自动开始研究，也尚未传入模型。">
      <div className="grid gap-3">
        <div className="flex flex-wrap items-center gap-2">
          {mode === "preview" ? <span className="chip chip-warn">预览 · 示例数据，不联网</span> : <span className="chip chip-accent">服务端保存的研究进度</span>}
          <span className="note">与“最近的研究”（本机浏览记录）分开。</span>
        </div>
        <LoadedPanel load={load} onClear={onClearLoaded} onRetry={onLoad} />
        {needsIdentity ? (
          <p className="note">需要先连接身份才能读取研究笔记。<button type="button" className="link-btn" onClick={onOpenIdentity}>连接身份</button></p>
        ) : record ? (
          <RecordDetail record={record} onBack={() => setSelected(null)} loadBusy={load.state === "loading"}
            loadedHere={load.state === "loaded" && load.context.projectId === record.projectId}
            onLoad={() => onLoad(record.projectId)}
            onDelete={async () => { if (await onDelete(record)) setSelected(null); }}
            deleting={deletingKey === recordKey(record.projectId, record.sourceRunId)}
            deleteError={deleteError?.key === recordKey(record.projectId, record.sourceRunId) ? deleteError.message : null} />
        ) : loading ? <p className="note">正在读取…</p>
        : error ? <div className="grid gap-2"><p className="missing" role="alert">{error}</p><button type="button" className="btn btn-quiet btn-sm" style={{ justifySelf: "start" }} onClick={onRefresh}>重试</button></div>
        : items.length ? (
          <div className="grid gap-2">
            {items.map((r) => (
              <button key={recordKey(r.projectId, r.sourceRunId)} type="button" className="example notebook-card" onClick={() => setSelected(recordKey(r.projectId, r.sourceRunId))}>
                <strong style={{ overflowWrap: "anywhere" }}>{r.originalGoal}</strong>
                <span>保存时：{STAGE_LABELS[r.runStatus] ?? r.runStatus} · 未解决 {r.unresolvedQuestions.length} 项 · 已完成 {r.completedWork.length} 项</span>
              </button>
            ))}
            {mode === "live" && candidateLimit ? <p className="note">只显示最近 {candidateLimit} 条候选中仍可访问的记录。</p> : null}
          </div>
        ) : <p className="note">{mode === "live" ? `最近 ${candidateLimit ?? 20} 条候选中没有可访问的研究进度；这不代表没有更早的记录。` : "预览中没有保存的进度。"}</p>}
      </div>
    </Sheet>
  );
}
