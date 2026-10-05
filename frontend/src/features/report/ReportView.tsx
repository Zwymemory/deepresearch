import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import type { NormalizedCitation } from "../../domain/citations";
import { STAGE_LABELS, toolLabel } from "../../domain/eventText";
import { explainFailure } from "../../domain/failures";
import type { Block } from "../../domain/markdown";
import { lastReachedStage, recordedGaps, type RunState } from "../../domain/runState";
import type { UnfinishedGoal, Usage } from "../../domain/types";
import { Icon } from "../../ui/Icon";
import { Markdown, type CiteHandlers } from "./Markdown";
import { EvidenceRecord } from "../evidence/EvidenceRecord";
import type { EvidenceViewResult } from "../../domain/evidenceView";

function outcomeOf(run: RunState): { tone: "ok" | "warn" | "neutral" | "error"; icon: "check" | "flag" | "pause" | "alert"; label: string } {
  if (run.status === "SUCCEEDED") return { tone: "ok", icon: "check", label: "研究完成" };
  if (run.status === "INSUFFICIENT_EVIDENCE") return { tone: "warn", icon: "flag", label: run.finalResponse?.report_status === "partial" ? "部分成果 · 仍有待核查事项" : "证据不足" };
  if (run.status === "CANCELLED") return { tone: "neutral", icon: "pause", label: "已取消" };
  if (run.status === "BUDGET_EXCEEDED" || run.errorCode === "BUDGET_EXCEEDED") return { tone: "warn", icon: "pause", label: "因预算上限终止" };
  return { tone: "error", icon: "alert", label: explainFailure(run.status, run.errorCode).label };
}

const goalText = (goal: UnfinishedGoal | string) => typeof goal === "string" ? goal
  : String(goal.text || goal.criterion_id || goal.task_id || goal.investigation_id || goal.call_id || "未命名目标");

function usageValue(value: number | null | undefined, status?: string, unit = "") {
  if (value == null) return status === "unknown" ? "未知" : "未记录";
  return value.toLocaleString() + unit;
}

function RunInfo({ run }: { run: RunState }) {
  const u: Usage = run.usage ?? {};
  const memory = run.memoryContext;
  return (
    <details className="note" style={{ marginTop: 26 }}>
      <summary style={{ cursor: "pointer", width: "fit-content" }}>运行信息（用量）</summary>
      <dl className="facts" style={{ marginTop: 10 }}>
        <dt>模型调用</dt><dd>{usageValue(u.modelCalls)}</dd>
        <dt>工具调用</dt><dd>{usageValue(u.toolCalls)}</dd>
        <dt>Token</dt><dd>{usageValue(u.totalTokens, u.inputTokensStatus === "unknown" || u.outputTokensStatus === "unknown" ? "unknown" : undefined)}</dd>
        <dt>耗时</dt><dd>{u.durationMs == null ? "未记录" : (u.durationMs / 1000).toFixed(1) + " 秒"}</dd>
        <dt>估算费用</dt><dd>{usageValue(u.estimatedCost, u.costStatus)}{u.estimatedCost != null && (u.currency || u.costCurrency) ? " " + (u.currency || u.costCurrency) : ""}</dd>
        {memory ? <>
          <dt>会话上下文</dt>
          <dd>已选取：摘要 {memory.summarySelected ? "是" : "否"} · 近期消息 {memory.recentMessageCount ?? "未记录"} 条 · 记忆 {memory.selectedMemoryCount ?? "未记录"} 条。模型是否实际使用这些上下文：{memory.modelUseVerification === "unknown" || !memory.modelUseVerification ? "未知（服务端未验证）" : memory.modelUseVerification}</dd>
        </> : null}
      </dl>
      <p style={{ marginTop: 8 }}>{u.estimated ? "以上用量为服务端估算值。" : ""}“未知”表示服务端没有可靠记录；页面不估算补齐，明确的 0 才显示为 0。</p>
    </details>
  );
}

export function ReportView({ run, blocks, citations, modeLabel, active, onCite, onOpenSources, onNew, onFollowUp, onReady, demo, onRetryQuestion, lastEventId, evidence, onCompareDisagreement, save }: {
  run: RunState; blocks: Block[]; citations: NormalizedCitation[]; modeLabel: string; active: number | null;
  onCite: CiteHandlers["onCite"]; onOpenSources: () => void; onNew: () => void; onFollowUp: (question: string) => void;
  demo: boolean;
  /** Recovery for failed runs: returns to the composer prefilled; a new run starts only on explicit submit. */
  onRetryQuestion: () => void;
  lastEventId?: string;
  /** Called once the report is in the DOM, so scroll/focus restoration has a target. */
  onReady: (ready: boolean) => void;
  /** Autonomous runs (or demo): the public evidence record. null for modes without a projection. */
  evidence?: { result: EvidenceViewResult | null; loading: boolean } | null;
  onCompareDisagreement?: (index: number) => void;
  /** Save-progress affordance. Eligibility comes from project discovery (live) or the preview (demo). */
  save?: {
    eligibility: "eligible" | "checking" | "unsupported"; reason: string;
    state: "idle" | "saving" | "saved" | "failed"; message?: string; preview: boolean;
    onSave: () => void; onOpenNotebook: () => void;
  };
}) {
  useLayoutEffect(() => { onReady(true); return () => onReady(false); }, [onReady]);
  const outcome = outcomeOf(run);
  const headings = useMemo(() => blocks.filter((b): b is Extract<Block, { type: "heading" }> => b.type === "heading"), [blocks]);
  const [currentSection, setCurrentSection] = useState<string | null>(headings[0]?.id ?? null);
  const [followUp, setFollowUp] = useState("");
  const goals = run.finalResponse?.unfinished_goals ?? [];
  const stopGaps = goals.length ? [] : recordedGaps(run);
  const budgetStop = run.status === "BUDGET_EXCEEDED" || run.errorCode === "BUDGET_EXCEEDED";
  const stoppedAt = lastReachedStage(run);
  const kb = citations.filter((c) => c.kind === "knowledge").length;
  const web = citations.filter((c) => c.kind === "web-snapshot" || c.kind === "web-original").length;
  const unknownSources = citations.filter((c) => c.kind === "unknown").length;
  const failed = !["SUCCEEDED", "INSUFFICIENT_EVIDENCE", "CANCELLED"].includes(run.status);
  const failure = explainFailure(run.status, run.errorCode);
  const [copied, setCopied] = useState(false);

  // Section guide follows the document without moving it.
  useEffect(() => {
    const nodes = headings.map((h) => document.getElementById(h.id)).filter((n): n is HTMLElement => !!n);
    if (!nodes.length) return;
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      if (visible[0]) setCurrentSection(visible[0].target.id);
    }, { rootMargin: "-80px 0px -60% 0px" });
    nodes.forEach((n) => observer.observe(n));
    return () => observer.disconnect();
  }, [headings]);

  const ctx: CiteHandlers = { citations, active, onCite };

  return (
    <div className="report-layout">
      {headings.length > 1 ? (
        <nav className="section-nav" aria-label="报告章节">
          <span className="eyebrow">本文章节</span>
          <ol>{headings.map((h) => <li key={h.id}><a href={`#${h.id}`} aria-current={currentSection === h.id ? "true" : undefined}>{h.text}</a></li>)}</ol>
        </nav>
      ) : null}

      <article className="report" id="report" aria-labelledby="report-question">
        <div className="report-toolbar">
          <span className="eyebrow">研究报告{demo ? " · 示例数据" : ""}</span>
          <div className="flex flex-wrap gap-2">
            {citations.length ? <button type="button" className="btn btn-quiet btn-sm" onClick={onOpenSources}><Icon name="book" size={16} />全部来源（{citations.length}）</button> : null}
            {save ? (
              <button type="button" className="btn btn-quiet btn-sm" onClick={save.onSave} aria-describedby="save-status"
                disabled={save.eligibility !== "eligible" || save.state === "saving"}>
                <Icon name="flag" size={16} />{save.state === "saving" ? "正在保存…" : save.state === "saved" ? "再次保存（刷新快照）" : "保存研究进度"}
              </button>
            ) : null}
            <button type="button" className="btn btn-quiet btn-sm" onClick={onNew}><Icon name="spark" size={16} />新研究</button>
          </div>
        </div>
        {save ? (
          <p id="save-status" className="note" role="status" style={{ marginTop: -14, marginBottom: 18 }}>
            {save.eligibility !== "eligible" ? <>保存研究进度：{save.reason}</>
              : save.state === "saved" ? <>{save.preview ? "已保存到研究笔记（示例数据，服务端未参与）。" : "服务端已确认保存。保存内容来自服务端记录；保存不会让研究变为成功，也不是重新核验。"}<button type="button" className="link-btn" onClick={save.onOpenNotebook}>查看研究笔记</button></>
              : save.state === "failed" ? <span style={{ color: "var(--error-ink)" }}>保存失败{save.preview ? "（示例）" : ""}：{save.message ?? "没有任何内容被保存。"} 可以重试。</span>
              : save.state === "saving" ? "正在保存，等待服务端确认…" : null}
          </p>
        ) : null}

        <h1 id="report-question" className="report-q" tabIndex={-1}>{run.question}</h1>
        <div className="outcome">
          <span className="status-tag" data-tone={outcome.tone}><Icon name={outcome.icon} size={15} />{outcome.label}</span>
          <span>{modeLabel}</span>
          {run.tools.map((t) => <span key={t} className="chip">{toolLabel(t)}</span>)}
          {citations.length ? <span title="按引用编号排列，不是可信度排名">引用 {citations.length} 个来源（知识库 {kb} · 网页 {web}{unknownSources ? ` · 详情未记录 ${unknownSources}` : ""}）</span> : null}
        </div>

        {run.status === "INSUFFICIENT_EVIDENCE" ? (
          <p className="limits"><strong>限制：</strong>只发布有证据支持的部分；{goals.length} 项目标仍未完成，见文末“尚未解决的问题”。</p>
        ) : run.status === "CANCELLED" ? (
          <p className="limits"><strong>已取消：</strong>研究在“{STAGE_LABELS[stoppedAt ?? ""] ?? "早期"}”阶段被取消，没有可发布的答案。已记录的过程仍可查看。</p>
        ) : budgetStop ? (
          <div className="limits" role="status">
            <p><strong>因预算上限终止</strong>（结果码 <code>BUDGET_EXCEEDED</code>）：运行触达了模型、工具、Token 或成本上限{stoppedAt ? `，停在“${STAGE_LABELS[stoppedAt]}”阶段` : ""}。这是执行限制，不是证据结论。</p>
            <p className="note" style={{ marginTop: 6 }}>可以缩小问题范围后重新研究。</p>
            <button type="button" className="btn btn-quiet btn-sm" style={{ marginTop: 10 }} onClick={onRetryQuestion}>用同一问题重新研究</button>
          </div>
        ) : failed ? (
          <div className="limits" role="status">
            <p><strong>{failure.label}</strong>（结果码 <code>{failure.code}</code>）：{failure.description}{stoppedAt ? ` 运行停在“${STAGE_LABELS[stoppedAt]}”阶段。` : ""}</p>
            {run.errorMessage && run.errorMessage !== failure.description ? <p className="note" style={{ marginTop: 4 }}>服务端说明：{run.errorMessage}</p> : null}
            <p className="note" style={{ marginTop: 6 }}>{failure.recovery}</p>
            <button type="button" className="btn btn-quiet btn-sm" style={{ marginTop: 10 }} onClick={onRetryQuestion}>用同一问题重新研究</button>
          </div>
        ) : null}
        {unknownSources && !demo ? (
          <p className="note" style={{ marginTop: 10 }}>{unknownSources} 个引用缺少来源详情（当前执行引擎未公开标题、地址与摘录），只能显示来源 ID；页面不会推测链接。</p>
        ) : null}

        {headings.length > 1 ? (
          <nav className="mobile-sections" aria-label="报告章节（紧凑）" style={{ marginTop: 22 }}>
            {headings.map((h) => <a key={h.id} href={`#${h.id}`}>{h.text}</a>)}
          </nav>
        ) : null}

        {blocks.length ? <><hr className="report-rule" /><Markdown blocks={blocks} ctx={ctx} /></>
          : run.status === "INSUFFICIENT_EVIDENCE" && !goals.length ? <p className="note" style={{ marginTop: 22 }}>没有找到足够可信的证据，系统不会自行补全答案。</p>
          : run.status !== "SUCCEEDED" && run.status !== "INSUFFICIENT_EVIDENCE" ? <p className="note" style={{ marginTop: 22 }}>该运行没有公开任何部分结果；页面不会根据过程事件重建中途内容。已记录的过程可在“技术详情”与运行信息中查看。</p> : null}

        {stopGaps.length ? (
          <section className="unresolved" aria-labelledby="gaps-title">
            <h2 id="gaps-title"><Icon name="flag" />停止时记录的待查事项</h2>
            <ol>{stopGaps.map((gap, i) => <li key={i}>{gap}</li>)}</ol>
          </section>
        ) : null}

        {goals.length ? (
          <section className="unresolved" aria-labelledby="unresolved-title">
            <h2 id="unresolved-title"><Icon name="flag" />尚未解决的问题</h2>
            <ol>
              {goals.map((goal, i) => (
                <li key={i}>{goalText(goal)}{typeof goal !== "string" && goal.reason ? <span className="reason">：{goal.reason}</span> : null}</li>
              ))}
            </ol>
          </section>
        ) : null}

        {evidence ? <EvidenceRecord result={evidence.result} loading={evidence.loading} demo={demo} onCompare={(i) => onCompareDisagreement?.(i)} /> : null}

        <RunInfo run={run} />
        {!demo && run.runId ? (
          <details className="note" style={{ marginTop: 10 }}>
            <summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary>
            <dl className="facts" style={{ marginTop: 10 }}>
              <dt>Run ID</dt><dd className="source-id">{run.runId} <button type="button" className="link-btn" onClick={() => { void navigator.clipboard?.writeText(run.runId).then(() => setCopied(true)); }}>{copied ? "已复制" : "复制"}</button></dd>
              <dt>Session</dt><dd className="source-id">{run.sessionId || "—"}</dd>
              {run.mode !== "legacy" ? <><dt>Last-Event-ID</dt><dd className="source-id">{lastEventId || "—"}</dd></> : null}
              <dt>状态码</dt><dd className="source-id">{run.status}{run.errorCode ? ` · ${run.errorCode}` : ""}</dd>
            </dl>
          </details>
        ) : null}

        {/* Follow-up only makes sense after a report exists; failed or cancelled runs offer recovery instead. */}
        {run.status === "SUCCEEDED" || run.status === "INSUFFICIENT_EVIDENCE" ? <form className="followup" onSubmit={(e) => { e.preventDefault(); if (followUp.trim()) onFollowUp(followUp.trim()); }}>
          <label htmlFor="followup" style={{ fontWeight: 650 }}>{!demo && run.sessionId ? "在同一 Session 中继续追问" : "继续追问"}</label>
          <div className="followup-row">
            <input id="followup" value={followUp} onChange={(e) => setFollowUp(e.target.value)} placeholder="基于这份报告，再问一个问题" />
            <button type="submit" className="btn btn-primary" disabled={!followUp.trim()}>发起新研究</button>
          </div>
          <p className="note">{demo ? "会以新的示例运行开始；示例模式下不会发送请求。"
            : run.sessionId ? "会使用同一 Session ID 创建新的研究运行。Single Agent 会按 Session 选取上下文；工作流模式是否使用 Session 上下文尚未经验证。"
            : "会创建新的研究运行，不沿用本次上下文。"}</p>
        </form> : null}
      </article>
    </div>
  );
}
