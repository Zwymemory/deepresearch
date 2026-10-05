import { useState } from "react";
import type { ProgressRecord } from "../../demo/memoryPreview";
import { STAGE_LABELS } from "../../domain/eventText";
import { Icon } from "../../ui/Icon";
import { Sheet } from "../shell/Dialogs";

const PROVENANCE: Record<ProgressRecord["provenance"][number]["status"], { label: string; chip: string }> = {
  "verified-at-the-time": { label: "当时已核查", chip: "chip-ok" },
  contested: { label: "有争议", chip: "chip-warn" },
  unverified: { label: "未逐条核查", chip: "chip" },
  failed: { label: "读取失败", chip: "chip-error" },
};

/** Why live progress memory is unavailable — stated precisely, never as a silent fallback. */
export const MEMORY_UNAVAILABLE = "服务端“研究进度”接口尚未作为公开契约交付（缺少最终 API 示例、项目标识来源与列表接口），因此真实模式下的保存、浏览、载入与删除都不可用。";

function RecordDetail({ record, onBack, onLoad, onDelete }: {
  record: ProgressRecord; onBack: () => void; onLoad: () => void; onDelete: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  return (
    <div className="grid gap-3">
      <button type="button" className="link-btn" style={{ justifySelf: "start" }} onClick={onBack}>← 返回列表</button>
      <h3 style={{ font: "600 18px/1.45 var(--font-serif)" }}>{record.goal}</h3>
      <div className="meta-row"><span className="chip">{STAGE_LABELS[record.runStatus] ?? record.runStatus}</span><span>保存于 {new Date(record.savedAt).toLocaleString()}</span><span className="source-id">来自运行 {record.runId}</span></div>
      {[["已完成的工作", record.completedWork], ["尚未解决", record.unresolved], ["下一步", record.nextSteps]].map(([title, items]) => (
        <section key={title as string}>
          <h4 className="insp-label">{title as string}</h4>
          {(items as string[]).length ? <ul className="grid gap-1 pl-5" style={{ listStyle: "disc" }}>{(items as string[]).map((t, i) => <li key={i}>{t}</li>)}</ul> : <p className="note">未记录</p>}
        </section>
      ))}
      <section>
        <h4 className="insp-label">出处（保留原状态，不是新的核查）</h4>
        <ul className="grid gap-1">{record.provenance.map((p, i) => <li key={i} className="flex flex-wrap items-center gap-2"><span className={"chip " + PROVENANCE[p.status].chip}>{PROVENANCE[p.status].label}</span>{p.label}</li>)}</ul>
      </section>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn btn-primary btn-sm" onClick={onLoad}>在新会话中载入为上下文</button>
        {confirming ? <>
          <button type="button" className="btn btn-danger btn-sm" onClick={onDelete}>确认删除</button>
          <button type="button" className="btn btn-quiet btn-sm" onClick={() => setConfirming(false)}>取消</button>
        </> : <button type="button" className="btn btn-quiet btn-sm" onClick={() => setConfirming(true)}>删除…</button>}
      </div>
    </div>
  );
}

export function NotebookDialog({ open, onOpenChange, live, records, onDelete, loaded, onLoad }: {
  open: boolean; onOpenChange: (open: boolean) => void;
  /** Live mode shows the dependency first; the preview must be opened explicitly. */
  live: boolean;
  records: ProgressRecord[];
  onDelete: (id: string) => void;
  loaded: ProgressRecord | null;
  onLoad: (record: ProgressRecord) => void;
}) {
  const [showPreview, setShowPreview] = useState(!live);
  const [selected, setSelected] = useState<string | null>(null);
  const record = records.find((r) => r.id === selected) ?? null;
  return (
    <Sheet open={open} onOpenChange={(o) => { onOpenChange(o); if (!o) setSelected(null); }} title="研究笔记"
      description="保存的研究进度是上下文，不是新验证的证据；载入后不会自动开始研究。">
      {live ? (
        <div className="unknown" role="note" style={{ marginTop: 0 }}>
          <strong><Icon name="alert" size={16} />真实模式暂不可用</strong>
          <p>{MEMORY_UNAVAILABLE}</p>
          {!showPreview ? <button type="button" className="btn btn-quiet btn-sm" style={{ justifySelf: "start" }} onClick={() => setShowPreview(true)}>查看版式预览（示例数据，不联网）</button> : null}
        </div>
      ) : null}
      {showPreview ? (
        <div className="grid gap-3" style={{ marginTop: live ? 14 : 0 }}>
          <div className="flex flex-wrap items-center gap-2"><span className="chip chip-warn">预览 · 示例数据</span><span className="note">与“最近的研究”（本机浏览记录）分开保存。</span></div>
          {loaded ? <p className="limits" role="status" style={{ marginTop: 0 }}><strong>已载入（预览）：</strong>“{loaded.goal}”的进度作为新会话的上下文。未开始任何研究；其中的争议与未核查内容保持原状态。</p> : null}
          {record ? (
            <RecordDetail record={record} onBack={() => setSelected(null)} onLoad={() => onLoad(record)}
              onDelete={() => { onDelete(record.id); setSelected(null); }} />
          ) : records.length ? (
            <div className="grid gap-2">
              {records.map((r) => (
                <button key={r.id} type="button" className="example notebook-card" onClick={() => setSelected(r.id)}>
                  <strong style={{ overflowWrap: "anywhere" }}>{r.goal}</strong>
                  <span>{STAGE_LABELS[r.runStatus] ?? r.runStatus} · 未解决 {r.unresolved.length} 项 · {new Date(r.savedAt).toLocaleDateString()}</span>
                </button>
              ))}
            </div>
          ) : <p className="note">预览中没有保存的进度。</p>}
        </div>
      ) : null}
    </Sheet>
  );
}
