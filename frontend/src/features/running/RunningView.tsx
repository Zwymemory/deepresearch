import { AnimatePresence, motion } from "motion/react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { describeEvent, roleLabel, STEP_LABELS, toolLabel } from "../../domain/eventText";
import { recordedEvidenceCount, STAGES, type RunState } from "../../domain/runState";
import { Icon } from "../../ui/Icon";

function formatElapsed(ms: number | null) {
  if (ms == null || ms < 0) return "—";
  const s = Math.floor(ms / 1000);
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function RunningView({ run, startedAt, modeLabel, completed, onCancel, onViewReport, onInspectingChange }: {
  run: RunState; startedAt: number | null; modeLabel: string; completed: boolean;
  onCancel: () => void; onViewReport: () => void; onInspectingChange: (inspecting: boolean) => void;
}) {
  const [now, setNow] = useState(() => Date.now());
  const [expanded, setExpanded] = useState(false);
  const [showTimeline, setShowTimeline] = useState(false);
  const [atLiveEdge, setAtLiveEdge] = useState(true);
  const list = useRef<HTMLOListElement>(null);

  // While the reader inspects the process record, completion must not move them away.
  useEffect(() => { onInspectingChange(showTimeline); }, [showTimeline, onInspectingChange]);

  useEffect(() => {
    if (startedAt == null || completed) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [startedAt, completed]);

  // Follow new events only when the reader is already at the live edge.
  useLayoutEffect(() => {
    const node = list.current;
    if (node && atLiveEdge) node.scrollTop = node.scrollHeight;
  }, [run.events.length, atLiveEdge, showTimeline]);

  const latest = run.events[run.events.length - 1];
  const current = latest ? describeEvent(latest) : { title: "正在提交", detail: "等待服务端接受请求。" };
  const stageIndex = STAGES.indexOf(run.stage as (typeof STAGES)[number]);
  const firstAt = run.events[0]?.createdAt ? Date.parse(run.events[0].createdAt) : null;
  const lastAt = latest?.createdAt ? Date.parse(latest.createdAt) : null;
  const elapsed = startedAt != null ? now - startedAt : firstAt != null && lastAt != null ? lastAt - firstAt : null;
  const plan = run.events.find((e) => e.type === "PLAN_COMPLETED");
  const evidence = recordedEvidenceCount(run);
  const finishedTasks = run.events.filter((e) => e.type === "TASK_COMPLETED").length;

  return (
    <section className="run-wrap" aria-labelledby="run-question">
      <div className="question-summary">
        <span className="eyebrow">正在研究 · 示例数据</span>
        <p id="run-question" className="q" data-clamped={!expanded}>{run.question}</p>
        <div className="meta-row">
          <span>{modeLabel}</span>
          {run.tools.map((tool) => (
            <span key={tool} className={"chip " + (tool === "kb_search" ? "chip-kb" : tool === "web_search" ? "chip-web" : "chip-tool")}>{toolLabel(tool)}</span>
          ))}
          {run.question.length > 60 ? (
            <button type="button" className="link-btn" aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}>{expanded ? "收起问题" : "展开完整问题"}</button>
          ) : null}
        </div>
      </div>

      <div className="activity">
        {completed ? (
          <div className="flex flex-wrap items-center justify-between gap-3" role="status" style={{ padding: "10px 14px", borderRadius: 14, background: "var(--success-soft)", color: "var(--success-ink)" }}>
            <strong>研究已结束，报告已可阅读。</strong>
            <button type="button" className="btn btn-primary btn-sm" onClick={onViewReport}>查看报告</button>
          </div>
        ) : null}
        <div className="activity-top">
          <div className="now" aria-live="polite">
            {completed ? null : <span className="pulse" aria-hidden="true" />}
            <div style={{ minWidth: 0 }}>
              <div className="now-label">{current.title}</div>
              <div className="now-sub">{current.detail}</div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <span className="elapsed" title={startedAt != null ? "自提交起的本地计时" : "首条与最新事件之间的记录时长"}>
              <span className="sr-only">已用时 </span>{formatElapsed(elapsed)}
            </span>
            {completed ? null : <button type="button" className="btn btn-danger btn-sm" onClick={onCancel}><Icon name="stop" size={15} />取消研究</button>}
          </div>
        </div>
        <ol className="stepper" aria-label="研究阶段">
          {STAGES.map((stage, index) => {
            const state = stageIndex > index ? "done" : stageIndex === index ? "current" : "todo";
            return (
              <li key={stage} className="step" data-state={state} aria-current={state === "current" ? "step" : undefined}>
                <span className="label">{STEP_LABELS[stage]}</span>
                <span className="sr-only">{state === "done" ? "（已完成）" : state === "current" ? "（进行中）" : "（未开始）"}</span>
              </li>
            );
          })}
        </ol>
        {/* Counts describe recorded objects only; they are not progress or truth scores. */}
        <div className="counts">
          <span>计划任务 <b>{typeof plan?.payload?.taskCount === "number" ? plan.payload.taskCount : "—"}</b></span>
          <span>已完成工具任务 <b>{finishedTasks}</b></span>
          <span title="工具在 TASK_COMPLETED 事件中回报的证据条数，不是去重后的来源数，也不代表可信度">工具回报证据 <b>{evidence ?? "—"}</b> 条</span>
          <span>已记录事件 <b>{run.events.length}</b></span>
        </div>
      </div>

      <div className="awaiting">
        <strong>报告将在核验完成后出现。</strong>
        <span className="note" style={{ display: "block", marginTop: 4 }}>当前接口不提供逐步生成的答案或中途证据详情，因此这里不显示临时正文；来源会随最终结果一起公开。</span>
      </div>

      <div>
        <button type="button" className="btn btn-quiet btn-sm" aria-expanded={showTimeline} aria-controls="timeline" onClick={() => setShowTimeline((v) => !v)}>
          <Icon name="list" size={16} />{showTimeline ? "收起过程记录" : `查看过程记录（${run.events.length}）`}
        </button>
        <AnimatePresence initial={false}>
          {showTimeline ? (
            <motion.div key="tl" initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: "auto" }} exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.24, ease: [0.2, 0.8, 0.2, 1] }} style={{ overflow: "hidden" }}>
              <ol id="timeline" ref={list} className="timeline" aria-label="过程记录" style={{ marginTop: 12 }}
                onScroll={(e) => { const n = e.currentTarget; setAtLiveEdge(n.scrollHeight - n.scrollTop - n.clientHeight < 24); }}>
                {run.events.map((event) => {
                  const text = describeEvent(event);
                  return (
                    <li key={event.id ?? `${event.type}-${event.eventId}`} className="tl-item">
                      <time dateTime={event.createdAt ?? undefined}>{event.createdAt ? new Date(event.createdAt).toLocaleTimeString([], { hour12: false }) : "—"}</time>
                      <div><strong>{text.title}</strong> <span className="who">· {roleLabel(event.role)}</span><div className="note">{text.detail}</div></div>
                    </li>
                  );
                })}
              </ol>
              {!atLiveEdge ? (
                <button type="button" className="btn btn-quiet btn-sm follow-live" onClick={() => setAtLiveEdge(true)}>跳到最新记录</button>
              ) : null}
            </motion.div>
          ) : null}
        </AnimatePresence>
      </div>
    </section>
  );
}
