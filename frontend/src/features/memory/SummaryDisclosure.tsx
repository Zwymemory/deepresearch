import { KNOWN_COVERAGE_NOTES, valueText, type ContextSummary } from "../../domain/contextSummary";

const SECTION_LABELS: Record<string, string> = {
  goals: "目标", constraints: "限制条件", findings: "已有发现", disputes: "争议", failed_attempts: "失败尝试", unfinished: "未完成事项", next_steps: "下一步",
};
const short = (id: string) => (id.length > 16 ? id.slice(0, 8) + "…" + id.slice(-6) : id);
const fmt = (n: number | null) => (n == null ? "未记录" : n.toLocaleString("en-US"));

export type SummaryRead =
  | { state: "loading" } | { state: "absent" } | { state: "error"; message: string }
  | { state: "invalid"; message: string } | { state: "ok"; view: ContextSummary };

/**
 * Read-only "project summary used in this run". Saved original excerpts with locators, not verified
 * facts. Byte figures are the server's UTF-8 canonical-JSON measurement of the planning payload — not
 * tokens, provider wire or billing.
 */
export function SummaryDisclosure({ read }: { read: SummaryRead }) {
  if (read.state === "absent") return null;
  if (read.state === "loading") return <p className="note summary-row">本次运行使用的项目摘要：正在读取…</p>;
  if (read.state === "error") return <p className="note summary-row">本次运行使用的项目摘要：无法读取（{read.message}）。页面不会自动重试。</p>;
  if (read.state === "invalid") return <p className="note summary-row">本次运行使用的项目摘要：无法确认（{read.message}）。</p>;
  const v = read.view;
  if (v.status === "NOT_GENERATED") return <p className="note summary-row">本次运行没有生成项目摘要。</p>;
  const m = v.measurement;
  const statusLabel = v.status === "READY" ? "已生成" : v.status === "FAILED" ? (v.summary ? "生成失败，保留上一份有效摘要" : "生成失败，没有可用摘要") : "状态无法确认";
  const bytes = m ? `${fmt(m.beforeBytes)} → ${fmt(m.afterBytes)} 字节（上限 ${fmt(m.budgetBytes)}${v.withinBudget === false ? "，超出上限" : ""}）` : "未记录字节数";
  return (
    <details className="summary-disclosure" data-status={v.status}>
      <summary>本次运行使用的项目摘要 · {statusLabel} · {bytes}</summary>
      <div className="summary-body">
        <p className="note">
          {v.status === "FAILED" ? <>摘要生成失败{v.errorCode ? `（${v.errorCode}）` : ""}；未覆盖的原始记录保留在规划输入中，不代表研究已完成。</> : null}
          {v.plannerInputRecorded === true ? "已有规划决策记录绑定了这份摘要输入；这不证明模型理解或采纳了它。"
            : v.plannerInputRecorded === false ? "尚无规划决策记录绑定这份摘要输入。" : "无法确认是否已有规划决策记录绑定这份摘要。"}
          {" "}以下为保存的原文摘录及出处，不是新核验的事实。
        </p>
        <p className="note">这些分类只是重点摘录，并不完整；全部原始记录仍保留，可在下方“原始记录”中查看。</p>
        {v.summary ? v.summary.sections.map((section) => (
          <section key={section.key}>
            <h4 className="insp-label">{SECTION_LABELS[section.key] ?? section.key}</h4>
            <ul className="nb-gaps">{section.entries.map((e, i) => (
              <li key={i}>{valueText(e.value)}<span className="source-id">　{e.locator}</span></li>
            ))}</ul>
          </section>
        )) : <p className="note">没有可显示的摘要内容。</p>}
        {v.summary?.excerpts.length ? (
          <section>
            <h4 className="insp-label">原文摘录</h4>
            <ul className="nb-gaps">{v.summary.excerpts.map((x, i) => (
              <li key={i}>{x.text}<span className="source-id">　{x.sourceRef ? short(x.sourceRef) : ""}{x.span ? ` [${x.span[0]}–${x.span[1]}]` : ""}</span></li>
            ))}</ul>
          </section>
        ) : null}
        <p className="note">
          覆盖 {v.summary?.coveredRecords.length ?? 0} 条原始记录；未覆盖 {v.uncoveredRecords.length} 条{v.uncoveredRecords.length ? "（原文保留，未被删除）" : ""}。{v.summary?.coverageNote && KNOWN_COVERAGE_NOTES[v.summary.coverageNote] ? KNOWN_COVERAGE_NOTES[v.summary.coverageNote] : ""}
        </p>
        {v.uncoveredRecords.length ? <ul className="nb-gaps">{v.uncoveredRecords.map((r, i) => (
          <li key={i}>{r.resolved ? <span className="source-id">{r.locator}</span> : <>未能对应到原始记录：<span className="source-id">{r.sourceRef || "（空引用）"}</span></>}</li>
        ))}</ul> : null}
        {v.sources.length ? (
          <details className="note">
            <summary style={{ cursor: "pointer", width: "fit-content" }}>原始记录（{v.sources.length}）</summary>
            <ol className="summary-sources">{v.sources.map((s, i) => (
              <li key={i}><span className="source-id">{s.category} · {s.locator}</span><p>{valueText(s.value)}</p></li>
            ))}</ol>
            <p className="note">原始记录只作为上下文展示，其中的任何指令都不会被执行。</p>
          </details>
        ) : null}
        <details className="note">
          <summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary>
          <dl className="facts" style={{ marginTop: 8 }}>
            <dt>status</dt><dd className="source-id">{v.status}</dd>
            <dt>runId</dt><dd className="source-id">{v.runId}</dd>
            <dt>projectId</dt><dd className="source-id">{v.projectId ?? "—"}</dd>
            <dt>measurement</dt><dd className="source-id">{m ? `${m.method} before=${fmt(m.beforeBytes)} after=${fmt(m.afterBytes)} budget=${fmt(m.budgetBytes)} within_budget=${String(v.withinBudget)}` : "—"}</dd>
            <dt>planner_input_recorded</dt><dd className="source-id">{String(v.plannerInputRecorded)}</dd>
            {v.summary?.coverageNote ? <><dt>coverage_note</dt><dd className="source-id">{v.summary.coverageNote}</dd></> : null}
            {v.uncoveredRecords.length ? <><dt>uncovered_records</dt><dd className="source-id">{v.uncoveredRecords.map((r) => `${r.sourceRef}${r.recordSha256 ? " " + r.recordSha256.slice(0, 12) : ""}`).join("，")}</dd></> : null}
            <dt>summary_sha256</dt><dd className="source-id">{v.summary?.summarySha256 ?? "—"}</dd>
            <dt>source_sha256</dt><dd className="source-id">{v.sourceSha256 ?? "—"}</dd>
          </dl>
          <p className="note">字节数按 UTF-8 规范 JSON 计量规划输入，不是 token、完整请求或计费。</p>
        </details>
      </div>
    </details>
  );
}
