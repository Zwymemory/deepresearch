import { AnimatePresence, motion, useAnimate, useReducedMotion } from "motion/react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { describeEvent, roleLabel, STEP_LABELS, toolLabel } from "../../domain/eventText";
import { latestPlan, recordedEvidenceCount, STAGES, type RunState } from "../../domain/runState";
import type { StreamStatus } from "../../live/useLiveResearch";
import { Icon } from "../../ui/Icon";

const TASK_STATUS: Record<string, string> = { pending: "待执行", running: "执行中", done: "已完成", blocked: "待解决", cancelled: "已取消" };
const CRITERION_STATUS: Record<string, string> = { resolved: "已核查", uncovered: "尚未核查", blocked: "待解决", stale: "前提已变化，需复核" };

function formatElapsed(ms: number | null) {
  if (ms == null || ms < 0) return "—";
  const s = Math.floor(ms / 1000);
  return `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}`;
}

export function RunningView({ run, startedAt, modeLabel, completed, demo, onCancel, onViewReport, onInspectingChange,
  canCancel = true, cancelling = false, stream, reconnects = 0, onDisconnectDrill, onReconnectNow, origin }: {
  run: RunState; startedAt: number | null; modeLabel: string; completed: boolean; demo: boolean;
  onCancel: () => void; onViewReport: () => void; onInspectingChange: (inspecting: boolean) => void;
  canCancel?: boolean; cancelling?: boolean; stream?: StreamStatus; reconnects?: number;
  onDisconnectDrill?: () => void; onReconnectNow?: () => void;
  /** Viewport rect of the composer question at submit time, for stage continuity. */
  origin?: { left: number; top: number } | null;
}) {
  const synchronous = run.mode === "legacy";
  const autonomous = run.mode === "agent";
  const recordedPlan = autonomous ? latestPlan(run) : null;
  const reduce = useReducedMotion();
  const [questionRef, animateQuestion] = useAnimate<HTMLParagraphElement>();
  // Stage continuity: the question carries over from where it sat in the composer (transform/opacity only).
  useLayoutEffect(() => {
    const el = questionRef.current;
    if (!origin || reduce || !el) return;
    const rect = el.getBoundingClientRect();
    const controls = animateQuestion(el, { x: [origin.left - rect.left, 0], y: [origin.top - rect.top, 0], opacity: [0.35, 1] },
      { duration: 0.42, ease: [0.2, 0.8, 0.2, 1] });
    return () => controls.stop();
    // Runs once on mount: the origin belongs to the submit that created this view.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  // Items already present when the record opens are shown statically; only later arrivals animate.
  const seenOnOpen = useRef<number | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [expanded, setExpanded] = useState(false);
  const [showTimeline, setShowTimeline] = useState(false);
  const [atLiveEdge, setAtLiveEdge] = useState(true);
  const list = useRef<HTMLOListElement>(null);

  // While the reader inspects the process record, completion must not move them away.
  useEffect(() => { onInspectingChange(showTimeline); }, [showTimeline, onInspectingChange]);

  const firstRecorded = run.events[0]?.createdAt ? Date.parse(run.events[0].createdAt) : null;
  // Elapsed time starts at the local submit (demo) or the first recorded event (live/recovered runs).
  // Demo snapshots have fixed fixture timestamps, so they show the recorded span instead.
  const elapsedOrigin = startedAt ?? (!demo && Number.isFinite(firstRecorded) ? firstRecorded : null);
  useEffect(() => {
    if (elapsedOrigin == null || completed) return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [elapsedOrigin, completed]);

  // Follow new events only when the reader is already at the live edge.
  useLayoutEffect(() => {
    const node = list.current;
    if (node && atLiveEdge) node.scrollTop = node.scrollHeight;
  }, [run.events.length, atLiveEdge, showTimeline]);

  const latest = run.events[run.events.length - 1];
  const current = latest ? describeEvent(latest) : synchronous
    ? { title: "等待 Single Agent 同步结果", detail: "该模式在完成前不返回过程事件；最长等待 45 秒。" }
    : { title: "正在提交", detail: "等待服务端接受请求。" };
  const stageIndex = STAGES.indexOf(run.stage as (typeof STAGES)[number]);
  const firstAt = run.events[0]?.createdAt ? Date.parse(run.events[0].createdAt) : null;
  const lastAt = latest?.createdAt ? Date.parse(latest.createdAt) : null;
  const recordedSpan = firstAt != null && lastAt != null ? lastAt - firstAt : null;
  const elapsed = completed || elapsedOrigin == null ? recordedSpan
    : Math.max(0, now - elapsedOrigin);
  const plan = run.events.find((e) => e.type === "PLAN_COMPLETED");
  const evidence = recordedEvidenceCount(run);
  const finishedTasks = run.events.filter((e) => e.type === "TASK_COMPLETED").length;

  return (
    <section className="run-wrap" aria-labelledby="run-question">
      <div className="question-summary">
        <span className="eyebrow">正在研究{demo ? " · 示例数据" : ""}</span>
        <p id="run-question" ref={questionRef} className="q" data-clamped={!expanded}>{run.question}</p>
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

      {stream && (stream.state === "reconnecting" || stream.state === "paused" || stream.state === "fatal") && !completed ? (
        <div className="connection" data-tone={stream.state === "fatal" ? "error" : "warn"} role="status">
          <span>{stream.state === "reconnecting"
            ? `事件流中断，${Math.round(stream.delayMs / 1000)} 秒后从持久游标恢复（第 ${stream.attempt} 次）。任务仍在服务端运行。`
            : stream.message}</span>
          {stream.state !== "paused" && onReconnectNow ? <button type="button" className="btn btn-quiet btn-sm" onClick={onReconnectNow}>立即恢复</button> : null}
        </div>
      ) : null}

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
            <AnimatePresence mode="popLayout" initial={false}>
              <motion.div key={latest?.id ?? latest?.type ?? "none"} style={{ minWidth: 0 }}
                initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18 }}>
                <div className="now-label">{current.title}</div>
                <div className="now-sub">{current.detail}</div>
              </motion.div>
            </AnimatePresence>
          </div>
          <div className="flex items-center gap-3">
            <span className="elapsed" title={startedAt != null ? "自提交起的本地计时" : completed ? "首条与最后一条记录事件之间的时长" : "自首条记录事件起的时长（按本机时钟）"}>
              <span className="sr-only">已用时 </span>{formatElapsed(elapsed)}
            </span>
            {completed ? null : canCancel
              ? <button type="button" className="btn btn-danger btn-sm" onClick={onCancel} disabled={cancelling}><Icon name="stop" size={15} />{cancelling ? "正在请求取消…" : "取消研究"}</button>
              : <span className="note" title="Single Agent 基线没有服务端取消接口">不支持取消</span>}
          </div>
        </div>
        {autonomous ? (
          <p className="note">自主研究不按固定阶段推进；下面显示它记录的计划与当前动作。</p>
        ) : synchronous ? (
          <p className="note">Single Agent 基线在一次同步请求中完成，没有可展示的阶段或持久事件；结果返回后直接进入报告。</p>
        ) : <>
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
          {!demo ? <span title="本页面自动恢复事件流的次数">SSE 重连 <b>{reconnects}</b></span> : null}
        </div>
        </>}
      </div>

      {recordedPlan ? (
        <section className="plan" aria-labelledby="plan-title">
          <h2 id="plan-title">{recordedPlan.revised ? "修订后的研究计划" : "研究计划"}{recordedPlan.version != null ? <span className="note">　版本 {recordedPlan.version}</span> : null}</h2>
          {recordedPlan.reason ? <p className="note">{recordedPlan.reason}</p> : null}
          <AnimatePresence initial={false} mode="popLayout">
            <motion.ol key={recordedPlan.version ?? "plan"} className="plan-tasks" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.22 }}>
              {recordedPlan.tasks.map((task, i) => (
                <li key={i} className="plan-task">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="chip" data-task={task.status}>{TASK_STATUS[task.status] ?? task.status}</span>
                    <strong style={{ overflowWrap: "anywhere" }}>{task.objective}</strong>
                    {task.evidenceCount != null ? <span className="note">已记录证据 {task.evidenceCount} 条</span> : null}
                  </div>
                  {task.criteria.length ? <ul className="plan-criteria">{task.criteria.map((c, j) => (
                    <li key={j}><span className="note">{CRITERION_STATUS[c.status] ?? c.status}</span>　{c.text}</li>))}</ul> : null}
                </li>
              ))}
            </motion.ol>
          </AnimatePresence>
        </section>
      ) : autonomous ? <p className="note">还没有记录研究计划。</p> : null}

      <div className="awaiting">
        <strong>报告将在核验完成后出现。</strong>
        <span className="note" style={{ display: "block", marginTop: 4 }}>当前接口不提供逐步生成的答案或中途证据详情，因此这里不显示临时正文；来源会随最终结果一起公开。</span>
      </div>

      {!synchronous ? <div>
        <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="btn btn-quiet btn-sm" aria-expanded={showTimeline} aria-controls="timeline" onClick={() => setShowTimeline((v) => !v)}>
          <Icon name="list" size={16} />{showTimeline ? "收起过程记录" : `查看过程记录（${run.events.length}）`}
        </button>
        {onDisconnectDrill && !completed ? (
          <button type="button" className="btn btn-quiet btn-sm" onClick={onDisconnectDrill} title="只断开事件流，不重新创建或取消任务">断线演练</button>
        ) : null}
        </div>
        <AnimatePresence initial={false}>
          {showTimeline ? (
            <motion.div key="tl" initial={{ opacity: 0, height: 0 }} animate={{ opacity: 1, height: "auto" }} exit={{ opacity: 0, height: 0 }}
              transition={{ duration: 0.24, ease: [0.2, 0.8, 0.2, 1] }} style={{ overflow: "hidden" }}>
              <ol id="timeline" ref={list} className="timeline" aria-label="过程记录" style={{ marginTop: 12 }}
                onScroll={(e) => { const n = e.currentTarget; setAtLiveEdge(n.scrollHeight - n.scrollTop - n.clientHeight < 24); }}>
                {run.events.map((event, index) => {
                  const text = describeEvent(event);
                  if (seenOnOpen.current == null) seenOnOpen.current = run.events.length;
                  const fresh = index >= seenOnOpen.current;
                  return (
                    <motion.li key={event.id ?? `${event.type}-${event.eventId}`} className={"tl-item" + (fresh ? " fresh" : "")}
                      initial={fresh ? { opacity: 0, y: 6 } : false} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.2 }}>
                      <time dateTime={event.createdAt ?? undefined}>{event.createdAt ? new Date(event.createdAt).toLocaleTimeString([], { hour12: false }) : "—"}</time>
                      <div><strong>{text.title}</strong> <span className="who">· {roleLabel(event.role)}</span><div className="note">{text.detail}</div></div>
                    </motion.li>
                  );
                })}
              </ol>
              {!atLiveEdge ? (
                <button type="button" className="btn btn-quiet btn-sm follow-live" onClick={() => setAtLiveEdge(true)}>跳到最新记录</button>
              ) : null}
            </motion.div>
          ) : null}
        </AnimatePresence>
      </div> : null}
    </section>
  );
}
