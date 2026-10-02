import { useEffect, useLayoutEffect, useMemo, useState } from "react";
import type { NormalizedCitation } from "../../domain/citations";
import { STAGE_LABELS, toolLabel } from "../../domain/eventText";
import type { Block } from "../../domain/markdown";
import { lastReachedStage, type RunState } from "../../domain/runState";
import type { UnfinishedGoal, Usage } from "../../domain/types";
import { Icon } from "../../ui/Icon";
import { Markdown, type CiteHandlers } from "./Markdown";

function outcomeOf(run: RunState): { tone: "ok" | "warn" | "neutral"; icon: "check" | "flag" | "pause" | "alert"; label: string } {
  if (run.status === "SUCCEEDED") return { tone: "ok", icon: "check", label: "研究完成" };
  if (run.status === "INSUFFICIENT_EVIDENCE") return { tone: "warn", icon: "flag", label: run.finalResponse?.report_status === "partial" ? "部分成果 · 仍有待核查事项" : "证据不足" };
  if (run.status === "CANCELLED") return { tone: "neutral", icon: "pause", label: "已取消" };
  return { tone: "warn", icon: "alert", label: STAGE_LABELS[run.status] ?? run.status };
}

const goalText = (goal: UnfinishedGoal | string) => typeof goal === "string" ? goal
  : String(goal.text || goal.criterion_id || goal.task_id || goal.investigation_id || goal.call_id || "未命名目标");

function usageValue(value: number | null | undefined, status?: string, unit = "") {
  if (value == null) return status === "unknown" ? "未知" : "未记录";
  return value.toLocaleString() + unit;
}

function RunInfo({ usage }: { usage: Usage | null }) {
  const u = usage ?? {};
  return (
    <details className="note" style={{ marginTop: 26 }}>
      <summary style={{ cursor: "pointer", width: "fit-content" }}>运行信息（用量）</summary>
      <dl className="facts" style={{ marginTop: 10 }}>
        <dt>模型调用</dt><dd>{usageValue(u.modelCalls)}</dd>
        <dt>工具调用</dt><dd>{usageValue(u.toolCalls)}</dd>
        <dt>Token</dt><dd>{usageValue(u.totalTokens, u.inputTokensStatus === "unknown" || u.outputTokensStatus === "unknown" ? "unknown" : undefined)}</dd>
        <dt>耗时</dt><dd>{u.durationMs == null ? "未记录" : (u.durationMs / 1000).toFixed(1) + " 秒"}</dd>
        <dt>估算费用</dt><dd>{usageValue(u.estimatedCost, u.costStatus)}</dd>
      </dl>
      <p style={{ marginTop: 8 }}>“未知”表示服务端没有可靠记录；页面不估算补齐，明确的 0 才显示为 0。</p>
    </details>
  );
}

export function ReportView({ run, blocks, citations, modeLabel, active, onCite, onOpenSources, onNew, onFollowUp, onReady }: {
  run: RunState; blocks: Block[]; citations: NormalizedCitation[]; modeLabel: string; active: number | null;
  onCite: CiteHandlers["onCite"]; onOpenSources: () => void; onNew: () => void; onFollowUp: (question: string) => void;
  /** Called once the report is in the DOM, so scroll/focus restoration has a target. */
  onReady: (ready: boolean) => void;
}) {
  useLayoutEffect(() => { onReady(true); return () => onReady(false); }, [onReady]);
  const outcome = outcomeOf(run);
  const headings = useMemo(() => blocks.filter((b): b is Extract<Block, { type: "heading" }> => b.type === "heading"), [blocks]);
  const [currentSection, setCurrentSection] = useState<string | null>(headings[0]?.id ?? null);
  const [followUp, setFollowUp] = useState("");
  const goals = run.finalResponse?.unfinished_goals ?? [];
  const stoppedAt = lastReachedStage(run);
  const kb = citations.filter((c) => c.kind === "knowledge").length;
  const web = citations.filter((c) => c.kind === "web-snapshot" || c.kind === "web-original").length;

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
          <span className="eyebrow">研究报告 · 示例数据</span>
          <div className="flex flex-wrap gap-2">
            {citations.length ? <button type="button" className="btn btn-quiet btn-sm" onClick={onOpenSources}><Icon name="book" size={16} />全部来源（{citations.length}）</button> : null}
            <button type="button" className="btn btn-quiet btn-sm" onClick={onNew}><Icon name="spark" size={16} />新研究</button>
          </div>
        </div>

        <h1 id="report-question" className="report-q" tabIndex={-1}>{run.question}</h1>
        <div className="outcome">
          <span className="status-tag" data-tone={outcome.tone}><Icon name={outcome.icon} size={15} />{outcome.label}</span>
          <span>{modeLabel}</span>
          {run.tools.map((t) => <span key={t} className="chip">{toolLabel(t)}</span>)}
          {citations.length ? <span title="按引用编号排列，不是可信度排名">引用 {citations.length} 个来源（知识库 {kb} · 网页 {web}）</span> : null}
        </div>

        {run.status === "INSUFFICIENT_EVIDENCE" ? (
          <p className="limits"><strong>限制：</strong>只发布有证据支持的部分；{goals.length} 项目标仍未完成，见文末“尚未解决的问题”。</p>
        ) : run.status === "CANCELLED" ? (
          <p className="limits"><strong>已取消：</strong>研究在“{STAGE_LABELS[stoppedAt ?? ""] ?? "早期"}”阶段被取消，没有可发布的答案。已记录的过程仍可查看。</p>
        ) : null}

        {headings.length > 1 ? (
          <nav className="mobile-sections" aria-label="报告章节（紧凑）" style={{ marginTop: 22 }}>
            {headings.map((h) => <a key={h.id} href={`#${h.id}`}>{h.text}</a>)}
          </nav>
        ) : null}

        {blocks.length ? <><hr className="report-rule" /><Markdown blocks={blocks} ctx={ctx} /></> : null}

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

        <RunInfo usage={run.usage} />

        <form className="followup" onSubmit={(e) => { e.preventDefault(); if (followUp.trim()) onFollowUp(followUp.trim()); }}>
          <label htmlFor="followup" style={{ fontWeight: 650 }}>继续追问</label>
          <div className="followup-row">
            <input id="followup" value={followUp} onChange={(e) => setFollowUp(e.target.value)} placeholder="基于这份报告，再问一个问题" />
            <button type="submit" className="btn btn-primary" disabled={!followUp.trim()}>发起新研究</button>
          </div>
          <p className="note">会以新的研究运行开始；是否沿用本次上下文取决于执行方式与 Session 设置，示例模式下不会发送请求。</p>
        </form>
      </article>
    </div>
  );
}
