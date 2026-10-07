import { safeCitationUrl } from "../../domain/citations";
import { CLAIM_STATUS } from "../../domain/progressMemory";
import { draftDownload, type DraftRead, type DraftScopeValue, type DraftSourceKind } from "../../domain/researchDraft";
import { Icon } from "../../ui/Icon";

const KIND: Record<DraftSourceKind, { label: string; chip: string }> = {
  WEB_ORIGINAL: { label: "已读取原文（网页）", chip: "chip-web" },
  WEB_SEARCH_SNAPSHOT: { label: "仅搜索摘要", chip: "chip-warn" },
  // The same kind can be a retrieval chunk, so it is not claimed as a fully read original.
  KNOWLEDGE_CHUNK: { label: "知识库片段", chip: "chip-kb" },
};
// The run has stopped: running / pending describe each task at the time of the budget stop.
const TASK_STATUS: Record<string, string> = {
  pending: "停止时尚未开始", running: "停止时仍在进行", blocked: "待解决", unresolved: "未解决", publication_pending: "等待发布",
  uncovered: "尚未核查", failed: "失败", FAILED: "失败",
};
const scopeText = (v: DraftScopeValue, unknown: string, known: string) => v.status === "known" && v.value ? `${known}：${v.value}` : unknown;

/**
 * Budget-exhausted run: material the server kept from stored records, shown as an unpublished draft.
 * Nothing here is a report citation or a published conclusion; excerpts are untrusted text.
 */
export function ResearchDraft({ read, runId }: { read: DraftRead; runId: string }) {
  if (read.state === "absent") return null;
  if (read.state === "invalid") return <p className="note research-draft" role="status">阶段性资料草稿的格式无法确认（{read.reason}），页面没有显示其内容。</p>;
  const d = read.draft;
  const order = new Map(d.sources.map((s, i) => [s.sourceId, i + 1]));
  const download = () => {
    const { blob, filename } = draftDownload(d.markdown, runId);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url; a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  };
  return (
    <section className="research-draft" aria-labelledby="draft-title">
      <div className="draft-head">
        <h2 id="draft-title">阶段性资料草稿</h2>
        <span className="chip chip-warn">未发布 · 因预算终止</span>
      </div>
      <p className="note">由已存储的记录整理，没有额外调用模型。资料尚未核查，不是研究结论，也不计入报告引用。</p>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="btn btn-quiet btn-sm" onClick={download} disabled={!d.markdown}><Icon name="list" size={16} />下载草稿（.md）</button>
        <span className="note">内容与服务端提供的 Markdown 完全一致。</span>
      </div>

      <h3 className="insp-label">已收集的资料（{d.sources.length}）</h3>
      {d.sources.length ? (
        <ol className="draft-sources">
          {d.sources.map((s) => {
            const url = safeCitationUrl(s.url);
            return (
              <li key={s.sourceId}>
                <div className="flex flex-wrap items-center gap-2">
                  <span className={"chip " + KIND[s.kind].chip}>{KIND[s.kind].label}</span>
                  <span className="chip">尚未核查</span>
                </div>
                <div className="ev-title">{url ? <a href={url.href} target="_blank" rel="noopener noreferrer">{s.title || url.host}</a> : s.title || "标题不可用"}</div>
                {s.excerpt ? (
                  <details>
                    <summary className="note" style={{ cursor: "pointer" }}>摘录{s.excerptTruncated ? `（已截断，最多 ${d.limits.excerptChars} 字）` : ""}</summary>
                    <blockquote className="ev-quote">{s.excerpt}</blockquote>
                  </details>
                ) : <p className="note">没有可显示的摘录。</p>}
                <p className="note">读取时间：{s.observedAt ? new Date(s.observedAt).toLocaleString() : "未记录"}</p>
              </li>
            );
          })}
        </ol>
      ) : <p className="note">没有可列出的资料。</p>}
      {d.limits.omittedSources ? <p className="note">另有 {d.limits.omittedSources} 条资料未列出（最多列出 {d.limits.sourceLimit} 条）。</p> : null}

      {d.checkedClaims.length || d.limits.omittedClaims ? (
        <>
          <h3 className="insp-label">已记录的核查结论（尚未最终发布）</h3>
          <ul className="draft-claims">
            {d.checkedClaims.map((c, i) => {
              const s = CLAIM_STATUS[c.decisionStatus] ?? { label: c.decisionStatus, chip: "chip" };
              return (
                <li key={i}>
                  <span className={"chip " + s.chip}>{s.label}</span>
                  <p>{c.text}</p>
                  <p className="note">
                    {scopeText(c.applicability.version, "版本未确定", "版本")} · {scopeText(c.applicability.validAt, "有效时间未确定", "有效时间")}
                    {c.applicability.conditions.length ? ` · 条件：${c.applicability.conditions.join("；")}` : ""}
                    {c.sourceIds.length ? ` · 依据资料 ${c.sourceIds.map((id) => order.has(id) ? `#${order.get(id)}` : id).join("、")}` : ""}
                  </p>
                </li>
              );
            })}
          </ul>
          {d.limits.omittedClaims ? <p className="note">另有 {d.limits.omittedClaims} 条记录的结论未列出。</p> : null}
        </>
      ) : null}

      {d.pendingTasks.length || d.limits.omittedTasks ? (
        <>
          <h3 className="insp-label">尚未完成的任务</h3>
          <ul className="nb-gaps">{d.pendingTasks.map((t, i) => <li key={i}>{t.text}<span className="note">（{TASK_STATUS[t.status] ?? t.status}）</span></li>)}</ul>
          {d.limits.omittedTasks ? <p className="note">另有 {d.limits.omittedTasks} 项任务未列出。</p> : null}
        </>
      ) : null}
    </section>
  );
}
