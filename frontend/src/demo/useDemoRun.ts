// Demo-mode run controller. It never performs network requests: the script is
// replayed locally through the same pure reducer the live adapter will use.
import { useCallback, useEffect, useRef, useState } from "react";
import { applyEvent, emptyRun, isTerminal, type RunState } from "../domain/runState";
import type { RunEvent, ToolName } from "../domain/types";
import { DEMO_QUESTION, DEMO_TOOLS, DEMO_USAGE, PARTIAL_RESPONSE, RUN_SCRIPT, SUCCESS_RESPONSE } from "./fixtures";

export type DemoOutcome = "success" | "partial";

function scriptFor(outcome: DemoOutcome): Array<[number, RunEvent]> {
  if (outcome === "success") return RUN_SCRIPT;
  const body = RUN_SCRIPT.filter(([, e]) => e.type !== "SUCCEEDED" && !(e.type === "STAGE_CHANGED" && e.payload?.stage === "FINALIZING"));
  return [...body.map(([t, e]): [number, RunEvent] => e.type === "REVIEW_COMPLETED"
    ? [t, { ...e, payload: { sufficient: false, revisionTaskCount: 1 } }] : [t, e]),
    [9300, { type: "INSUFFICIENT_EVIDENCE", role: "SYSTEM", payload: {} }]];
}

function withIds(runId: string, script: Array<[number, RunEvent]>, startedAt: number) {
  return script.map(([delay, event], index): [number, RunEvent] => [delay, {
    ...event, eventId: index + 1, id: `${runId}:${index + 1}`,
    createdAt: new Date(startedAt + delay).toISOString(),
  }]);
}

function finish(run: RunState, outcome: DemoOutcome): RunState {
  if (run.status === "SUCCEEDED") return { ...run, finalResponse: SUCCESS_RESPONSE, usage: DEMO_USAGE };
  if (run.status === "INSUFFICIENT_EVIDENCE" || outcome === "partial") return { ...run, finalResponse: PARTIAL_RESPONSE, usage: DEMO_USAGE };
  return run;
}

/** Apply every scripted event up to `untilMs` synchronously (deterministic previews). */
export function snapshotRun(outcome: DemoOutcome, untilMs: number, question = DEMO_QUESTION, tools: ToolName[] = DEMO_TOOLS): RunState {
  const runId = "demo-run-0001";
  const startedAt = Date.parse("2026-10-02T09:00:00Z");
  let run: RunState = { ...emptyRun(question, tools), runId, status: "QUEUED", stage: "QUEUED" };
  for (const [delay, event] of withIds(runId, scriptFor(outcome), startedAt)) {
    if (delay <= untilMs) run = applyEvent(run, event, event.id);
  }
  return isTerminal(run.status) ? finish(run, outcome) : run;
}

export function useDemoRun(initial: RunState) {
  const [run, setRun] = useState<RunState>(initial);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const timers = useRef<number[]>([]);

  const clear = () => { timers.current.forEach((id) => window.clearTimeout(id)); timers.current = []; };
  useEffect(() => clear, []);

  const start = useCallback((question: string, tools: ToolName[], outcome: DemoOutcome) => {
    clear();
    const runId = `demo-run-${Date.now().toString(36)}`;
    const now = Date.now();
    setStartedAt(now);
    setRun({ ...emptyRun(question, tools), runId, status: "QUEUED", stage: "QUEUED" });
    for (const [delay, event] of withIds(runId, scriptFor(outcome), now)) {
      timers.current.push(window.setTimeout(() => {
        setRun((current) => {
          if (current.runId !== runId) return current;
          const next = applyEvent(current, event, event.id);
          return isTerminal(next.status) ? finish(next, outcome) : next;
        });
      }, delay));
    }
  }, []);

  /** Demo equivalent of POST /cancel: the run ends as CANCELLED; nothing is fabricated. */
  const cancel = useCallback(() => {
    clear();
    setRun((current) => isTerminal(current.status) ? current
      : applyEvent(current, { type: "CANCELLED", role: "SYSTEM", id: `${current.runId}:cancel`, payload: {} }, `${current.runId}:cancel`));
  }, []);

  const reset = useCallback((next: RunState) => { clear(); setStartedAt(null); setRun(next); }, []);

  return { run, startedAt, start, cancel, reset };
}
