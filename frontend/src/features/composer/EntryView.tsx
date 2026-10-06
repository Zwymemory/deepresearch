import { useRef, useState, type KeyboardEvent } from "react";
import type { ExecutionMode, ToolName } from "../../domain/types";
import { Icon } from "../../ui/Icon";
import type { DemoOutcome } from "../../demo/useDemoRun";
import { DEMO_QUESTION } from "../../demo/fixtures";
import type { AppMode } from "../shell/TopBar";
import type { Continuation } from "../../domain/researchMemory";
import type { MemoryRejection } from "../../live/useLiveResearch";

/** History shown as history: the saved record, not part of this round's question. */
function SourceProject({ continuation, onClear }: { continuation: Continuation; onClear: () => void }) {
  return (
    <section className="source-project" aria-labelledby="source-project-title">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p id="source-project-title" className="eyebrow">来源项目 · 历史记录{continuation.preview ? " · 示例数据" : ""}</p>
        <button type="button" className="link-btn" onClick={onClear}>不使用此项目</button>
      </div>
      <ul className="source-goals">{continuation.goals.map((g, i) => <li key={i}>{g}</li>)}</ul>
      {continuation.unresolved.length ? (
        <div>
          <p className="insp-label">尚未解决的问题</p>
          <ul className="nb-gaps">{continuation.unresolved.map((u, i) => (
            <li key={i}>{u.goal}{u.gaps.length ? <span className="note">（{u.gaps.join("；")}）</span> : null}
              {u.criteria.length ? <span className="note">　标准：{u.criteria.join("；")}</span> : null}</li>
          ))}</ul>
        </div>
      ) : null}
      {continuation.nextSteps.length ? (
        <div>
          <p className="insp-label">保存时记录的下一步</p>
          <ul className="nb-gaps">{continuation.nextSteps.map((n, i) => <li key={i}>{n}</li>)}</ul>
        </div>
      ) : null}
      <p className="note">这是保存的历史记录，尚未传入模型。提交后，服务端会重新核对并选择该项目的历史进度作为规划参考（不可信的历史上下文，不是新证据）；页面无法确认模型是否收到或使用了它。</p>
      <details className="note">
        <summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary>
        <dl className="facts" style={{ marginTop: 8 }}>
          <dt>researchProjectId</dt><dd className="source-id">{continuation.projectId}</dd>
          <dt>sessionId（载入返回）</dt><dd className="source-id">{continuation.sessionId}</dd>
        </dl>
      </details>
    </section>
  );
}

type Scope = "kb" | "web" | "mixed";

const SCOPES: Array<{ id: Scope; label: string; color: string }> = [
  { id: "kb", label: "知识库", color: "var(--kb)" },
  { id: "web", label: "网页", color: "var(--web)" },
  { id: "mixed", label: "混合", color: "var(--accent)" },
];

const MODE_NOTES: Record<ExecutionMode, string> = {
  workflow: "可恢复工作流：规划、检索、审阅、合成；刷新或断线后从持久游标续传，可在服务端取消。",
  agent: "候选功能，仍在真实场景验收中：根据观察决定下一步，缺少证据时保留问题；状态与事件可续传。",
  legacy: "单 Agent 基线：Java 直接选择工具，同步返回；不具备工作流恢复与服务端取消，不使用检索范围设置。",
};

export interface StartRequest { question: string; tools: ToolName[]; mode: ExecutionMode; outcome: DemoOutcome; sessionId: string; researchProjectId?: string }

export interface UnknownCreate { question: string; idempotencyKey: string; onRetry: () => void; onDiscard: () => void }

/** Decorative folio stack — a quiet reference to the archive; carries no data. */
function FolioStack() {
  return (
    <div className="folio-stack" aria-hidden="true">
      <span className="ring r1" /><span className="ring r2" /><span className="ring r3" />
      <div className="stack-plane">
        {[0, 1, 2, 3, 4].map((i) => <span key={i} className="stack-folio" style={{ ["--d" as string]: i }}><i />{i === 4 ? <b>No.01</b> : null}</span>)}
      </div>
    </div>
  );
}

export function EntryView({ appMode, onStart, onExample, onOpenArchive, onOpenRecent, recentCount, busy = false, webConfigured = null, initialQuestion = "", unknown = null, blocking = null,
  continuation = null, onClearContinuation, rejection = null }: {
  appMode: AppMode;
  /** Loaded project/session pair chosen with "Continue research"; absent for ordinary research. */
  continuation?: Continuation | null;
  onClearContinuation?: () => void;
  /** The server refused the last project continuation; nothing was started. */
  rejection?: MemoryRejection | null;
  onOpenArchive: () => void;
  onOpenRecent: () => void;
  /** Local runs remembered by this browser (never server data). */
  recentCount: number;
  /** Resolves to an error message, or null when accepted for submission. */
  onStart: (request: StartRequest) => Promise<string | null> | string | null;
  onExample: (outcome: DemoOutcome) => void;
  busy?: boolean;
  webConfigured?: boolean | null;
  initialQuestion?: string;
  unknown?: UnknownCreate | null;
  blocking?: string | null;
}) {
  const [question, setQuestion] = useState(initialQuestion);
  const [scope, setScope] = useState<Scope>("mixed");
  const [calculator, setCalculator] = useState(true);
  const [chosenMode, setMode] = useState<ExecutionMode>("workflow");
  // Continuing a project is an autonomous-research feature; the pair decides the mode.
  const mode: ExecutionMode = continuation ? "agent" : chosenMode;
  const [sessionId, setSessionId] = useState("");
  const [error, setError] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);
  const locked = busy || !!unknown;

  const tools = (): ToolName[] => [
    ...(scope !== "web" ? ["kb_search" as const] : []),
    ...(scope !== "kb" ? ["web_search" as const] : []),
    ...(calculator ? ["calculator" as const] : []),
  ];

  // Synchronous guard: a double click cannot reach onStart twice before the busy state renders.
  const submitting = useRef(false);
  const submit = async (text = question, outcome: DemoOutcome = "success") => {
    if (locked || submitting.current) return;
    if (!text.trim()) { setError("请先写下研究问题。"); field.current?.focus(); return; }
    setError("");
    submitting.current = true;
    try {
      const result = await onStart(continuation
        ? { question: text.trim(), tools: tools(), mode: "agent", outcome, sessionId: continuation.sessionId, researchProjectId: continuation.projectId }
        : { question: text.trim(), tools: mode === "legacy" ? [] : tools(), mode, outcome, sessionId: sessionId.trim() });
      if (result) setError(result);
    } finally {
      submitting.current = false;
    }
  };

  const onKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") void submit();
  };

  const onScopeKey = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(event.key)) return;
    event.preventDefault();
    const index = SCOPES.findIndex((s) => s.id === scope);
    const next = SCOPES[(index + (event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 1) + SCOPES.length) % SCOPES.length];
    setScope(next.id);
    (event.currentTarget.querySelector(`[data-scope="${next.id}"]`) as HTMLElement | null)?.focus();
  };

  const scopeDisabled = mode === "legacy" || locked;
  const webNote = appMode === "live" && webConfigured === false ? "（未配置）" : "";

  return (
    <section className="entry" aria-labelledby="entry-title">
      <div className="entry-inner">
        <p className="eyebrow entry-eyebrow">RESEARCH CONSOLE · 研究工作台</p>
        <h1 id="entry-title" className="entry-title">你想弄清楚什么？</h1>
        <p className="entry-intro">提出一个问题。研究助手会检索、核验并整理成可以逐条追溯来源的报告；证据不足时会说明缺口，而不是补全答案。</p>

        {blocking ? <p className="limits" role="alert" style={{ marginTop: 20 }}><strong>暂不可用：</strong>{blocking}</p> : null}
        {continuation && onClearContinuation ? <SourceProject continuation={continuation} onClear={onClearContinuation} /> : null}
        {rejection ? (
          <div className="unknown memory-rejected" role="alert">
            <strong><Icon name="alert" size={16} />{rejection.label}：历史进度没有被使用，研究没有开始</strong>
            <p>{rejection.text}</p>
            {rejection.reselect ? <button type="button" className="btn btn-quiet btn-sm" style={{ justifySelf: "start" }} onClick={onOpenArchive}>回到研究档案重新选择</button> : null}
            <details className="note"><summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary>
              <p className="source-id" style={{ marginTop: 6 }}>{rejection.code} · researchProjectId {rejection.projectId}</p></details>
          </div>
        ) : null}

        <form className="composer" onSubmit={(e) => { e.preventDefault(); void submit(); }} aria-busy={busy}>
          <label htmlFor="question" className="sr-only">{continuation ? "本轮研究问题" : "研究问题"}</label>
          <textarea id="question" ref={field} className="question-field" rows={3} maxLength={4000} value={question} disabled={locked}
            placeholder={continuation ? "本轮研究问题，例如：接着做，先推进尚未完成的部分。" : "例如：本项目如何在崩溃和断线后继续研究，而不重复创建任务？"}
            aria-describedby={error ? "question-error" : "question-hint"} aria-invalid={!!error}
            onChange={(e) => { setQuestion(e.target.value); if (error) setError(""); }} onKeyDown={onKey} />
          <div className="composer-bar">
            <div role="radiogroup" aria-label="检索范围" className="segmented" onKeyDown={onScopeKey} aria-disabled={scopeDisabled}>
              {SCOPES.map((s) => (
                <button key={s.id} type="button" role="radio" data-scope={s.id} className="seg-btn" disabled={scopeDisabled}
                  aria-checked={scope === s.id} tabIndex={scope === s.id ? 0 : -1} onClick={() => setScope(s.id)}>
                  <span className="swatch" style={{ background: s.color }} aria-hidden="true" />{s.label}{s.id !== "kb" ? webNote : ""}
                </button>
              ))}
            </div>
            <button type="button" className="toggle-chip" aria-pressed={calculator} disabled={scopeDisabled}
              onClick={() => setCalculator((v) => !v)} title="确定性数值计算">
              <Icon name="sigma" size={15} />计算器
            </button>
            <span className="spacer" />
            <button type="submit" className="btn btn-primary btn-lg" disabled={locked}>
              <Icon name="spark" />{busy ? "正在提交…" : continuation ? "在此项目继续研究" : mode === "legacy" ? "运行基线" : "开始研究"}
            </button>
          </div>
        </form>

        {unknown ? (
          <section className="unknown" role="alert" aria-labelledby="unknown-title">
            <strong id="unknown-title"><Icon name="alert" size={16} />创建结果未知</strong>
            <p>连接在收到响应前中断，服务端可能已经创建了任务。不要新建；安全重试会复用完全相同的请求体与幂等键，若任务已存在只会返回原结果。</p>
            <p className="note">问题：{unknown.question}<br />Idempotency-Key：<code>{unknown.idempotencyKey}</code></p>
            <div className="flex flex-wrap gap-2">
              <button type="button" className="btn btn-primary btn-sm" onClick={unknown.onRetry} disabled={busy}>{busy ? "正在重试…" : "使用原请求安全重试"}</button>
              <button type="button" className="btn btn-quiet btn-sm" onClick={unknown.onDiscard} disabled={busy}>放弃并新建</button>
            </div>
          </section>
        ) : null}

        <div className="composer-foot">
          <span id="question-hint">{error ? <span id="question-error" role="alert" style={{ color: "var(--error-ink)" }}>{error}</span>
            : <>{question.length} / 4000 · ⌘/Ctrl + Enter 提交 · 网页搜索为外部调用</>}</span>
          <label className="mode-picker">
            <span>执行方式</span>
            <select value={mode} disabled={locked || !!continuation} onChange={(e) => setMode(e.target.value as ExecutionMode)} aria-describedby="mode-note">
              <option value="workflow">Durable Workflow</option>
              <option value="agent">自主研究（候选）</option>
              <option value="legacy">Single Agent 基线</option>
            </select>
          </label>
        </div>
        <p id="mode-note" className="note" style={{ padding: "6px 6px 0" }}>
          {mode === "agent" ? <span className="chip chip-future" style={{ marginRight: 6 }}>候选功能</span> : null}{MODE_NOTES[mode]}
        </p>
        {appMode === "live" && !continuation ? (
          <details className="note" style={{ padding: "8px 6px 0" }}>
            <summary style={{ cursor: "pointer", width: "fit-content" }}>会话选项</summary>
            <label className="field-row" style={{ marginTop: 8, maxWidth: 360 }}>
              <span>Session ID（可留空自动生成）</span>
              <input className="text-input" value={sessionId} maxLength={64} disabled={locked} onChange={(e) => setSessionId(e.target.value)} placeholder="延续既有会话时填写" autoComplete="off" />
            </label>
          </details>
        ) : null}

        <div className="examples" aria-label="示例体验（示例数据）">
          <p className="eyebrow" style={{ gridColumn: "1 / -1" }}>示例体验 · 不联网</p>
          <button type="button" className="example" onClick={() => appMode === "demo" ? void submit(DEMO_QUESTION, "success") : onExample("success")}>
            <span className="chip chip-warn" style={{ justifySelf: "start" }}>示例数据</span>
            <strong>完整报告：知识库 + 网页来源</strong>
            <span>{appMode === "demo" ? "观看一次完整运行，再逐条检查引用。" : "切换到示例模式观看；不会连接服务或使用你的凭据。"}</span>
          </button>
          <button type="button" className="example" onClick={() => appMode === "demo" ? void submit(DEMO_QUESTION, "partial") : onExample("partial")}>
            <span className="chip chip-warn" style={{ justifySelf: "start" }}>示例数据</span>
            <strong>证据不足：保留待核查事项</strong>
            <span>部分结论有来源，其余明确列为缺口。</span>
          </button>
        </div>
      </div>

      <aside className="entry-side" aria-label="已有的研究">
        <FolioStack />
        <div className="entry-paths">
          <button type="button" className="path-card" onClick={onOpenArchive}>
            <span className="path-n" aria-hidden="true">A</span>
            <span className="path-body">
              <strong>研究档案</strong>
              <span>{appMode === "demo" ? "保存的研究进度 · 示例数据" : "服务端保存的研究进度；打开时才读取"}</span>
            </span>
            <Icon name="arrowUpRight" size={16} />
          </button>
          <button type="button" className="path-card" onClick={onOpenRecent}>
            <span className="path-n" aria-hidden="true">B</span>
            <span className="path-body">
              <strong>最近运行（本机）</strong>
              <span>{recentCount ? `本机浏览器记住了 ${recentCount} 次运行` : "本机还没有运行记录；开始一次研究后会出现在这里"}</span>
            </span>
            <Icon name="arrowUpRight" size={16} />
          </button>
        </div>
      </aside>
    </section>
  );
}
