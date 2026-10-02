import { useRef, useState, type KeyboardEvent } from "react";
import type { ExecutionMode, ToolName } from "../../domain/types";
import { Icon } from "../../ui/Icon";
import type { DemoOutcome } from "../../demo/useDemoRun";
import { DEMO_QUESTION } from "../../demo/fixtures";

type Scope = "kb" | "web" | "mixed";

const SCOPES: Array<{ id: Scope; label: string; icon: "book" | "globe" | "mix"; color: string }> = [
  { id: "kb", label: "知识库", icon: "book", color: "var(--kb)" },
  { id: "web", label: "网页", icon: "globe", color: "var(--web)" },
  { id: "mixed", label: "混合", icon: "mix", color: "var(--accent)" },
];

const MODE_NOTES: Record<ExecutionMode, string> = {
  workflow: "可恢复工作流：规划、检索、审阅、合成，状态与事件可续传。",
  agent: "候选功能，仍在真实场景验收中：根据观察决定下一步，缺少证据时保留问题。",
  legacy: "单 Agent 基线：Java 直接选择工具；不具备工作流恢复，不使用检索范围设置。",
};

export interface StartRequest { question: string; tools: ToolName[]; mode: ExecutionMode; outcome: DemoOutcome }

export function EntryView({ onStart }: { onStart: (request: StartRequest) => void }) {
  const [question, setQuestion] = useState("");
  const [scope, setScope] = useState<Scope>("mixed");
  const [calculator, setCalculator] = useState(true);
  const [mode, setMode] = useState<ExecutionMode>("workflow");
  const [error, setError] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);

  const tools = (): ToolName[] => [
    ...(scope !== "web" ? ["kb_search" as const] : []),
    ...(scope !== "kb" ? ["web_search" as const] : []),
    ...(calculator ? ["calculator" as const] : []),
  ];

  const submit = (text = question, outcome: DemoOutcome = "success") => {
    if (!text.trim()) { setError("请先写下研究问题。"); field.current?.focus(); return; }
    onStart({ question: text.trim(), tools: mode === "legacy" ? [] : tools(), mode, outcome });
  };

  const onKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") submit();
  };

  // Roving radio group: arrow keys move the selection, as in native radios.
  const onScopeKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const keys = ["ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"];
    if (!keys.includes(event.key)) return;
    event.preventDefault();
    const index = SCOPES.findIndex((s) => s.id === scope);
    const next = SCOPES[(index + (event.key === "ArrowLeft" || event.key === "ArrowUp" ? -1 : 1) + SCOPES.length) % SCOPES.length];
    setScope(next.id);
    (event.currentTarget.querySelector(`[data-scope="${next.id}"]`) as HTMLElement | null)?.focus();
  };

  const scopeDisabled = mode === "legacy";

  return (
    <section className="entry" aria-labelledby="entry-title">
      <div className="entry-inner">
        <h1 id="entry-title" className="entry-title">你想弄清楚什么？</h1>
        <p className="entry-intro">提出一个问题。研究助手会检索、核验并整理成可以逐条追溯来源的报告；证据不足时会说明缺口，而不是补全答案。</p>

        <form className="composer" onSubmit={(e) => { e.preventDefault(); submit(); }}>
          <label htmlFor="question" className="sr-only">研究问题</label>
          <textarea id="question" ref={field} className="question-field" rows={3} maxLength={4000} value={question}
            placeholder="例如：本项目如何在崩溃和断线后继续研究，而不重复创建任务？"
            aria-describedby={error ? "question-error" : "question-hint"} aria-invalid={!!error}
            onChange={(e) => { setQuestion(e.target.value); if (error) setError(""); }} onKeyDown={onKey} />
          <div className="composer-bar">
            <div role="radiogroup" aria-label="检索范围" className="segmented" onKeyDown={onScopeKey} aria-disabled={scopeDisabled}>
              {SCOPES.map((s) => (
                <button key={s.id} type="button" role="radio" data-scope={s.id} className="seg-btn" disabled={scopeDisabled}
                  aria-checked={scope === s.id} tabIndex={scope === s.id ? 0 : -1} onClick={() => setScope(s.id)}>
                  <span className="swatch" style={{ background: s.color }} aria-hidden="true" />{s.label}
                </button>
              ))}
            </div>
            <button type="button" className="toggle-chip" aria-pressed={calculator} disabled={scopeDisabled}
              onClick={() => setCalculator((v) => !v)} title="确定性数值计算">
              <Icon name="sigma" size={15} />计算器
            </button>
            <span className="spacer" />
            <button type="submit" className="btn btn-primary btn-lg"><Icon name="spark" />开始研究</button>
          </div>
        </form>

        <div className="composer-foot">
          <span id="question-hint">{error ? <span id="question-error" role="alert" style={{ color: "var(--error-ink)" }}>{error}</span>
            : <>{question.length} / 4000 · ⌘/Ctrl + Enter 提交 · 网页搜索为外部调用</>}</span>
          <label className="mode-picker">
            <span>执行方式</span>
            <select value={mode} onChange={(e) => setMode(e.target.value as ExecutionMode)} aria-describedby="mode-note">
              <option value="workflow">Durable Workflow</option>
              <option value="agent">自主研究（候选）</option>
              <option value="legacy">Single Agent 基线</option>
            </select>
          </label>
        </div>
        <p id="mode-note" className="note" style={{ padding: "6px 6px 0" }}>
          {mode === "agent" ? <span className="chip chip-future" style={{ marginRight: 6 }}>候选功能</span> : null}{MODE_NOTES[mode]}
        </p>

        <div className="examples" aria-label="示例体验（示例数据）">
          <button type="button" className="example" onClick={() => submit(DEMO_QUESTION, "success")}>
            <span className="chip chip-warn" style={{ justifySelf: "start" }}>示例数据</span>
            <strong>完整报告：知识库 + 网页来源</strong>
            <span>观看一次完整运行，再逐条检查引用。</span>
          </button>
          <button type="button" className="example" onClick={() => submit(DEMO_QUESTION, "partial")}>
            <span className="chip chip-warn" style={{ justifySelf: "start" }}>示例数据</span>
            <strong>证据不足：保留待核查事项</strong>
            <span>部分结论有来源，其余明确列为缺口。</span>
          </button>
        </div>
      </div>
    </section>
  );
}
