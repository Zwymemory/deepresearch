// Single authoritative run representation plus pure transitions. Event cursor and
// duplicate handling follow the V1 page (eventKey / advanceEventCursor).
import type { FinalResponse, RunEvent, RunStatus, ToolName, Usage, WorkflowView } from "./types";

export const TERMINAL = new Set<string>([
  "SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED",
  "MAX_ROUNDS_REACHED", "CITATION_VALIDATION_FAILED", "MODEL_TIMEOUT", "TOOL_TIMEOUT",
  "MODEL_RATE_LIMITED", "MODEL_SCHEMA_INVALID", "MODEL_PROVIDER_FAILED",
  "MODEL_EXECUTION_FAILED", "MODEL_CALL_FAILED",
]);

export const STAGES = ["QUEUED", "PLANNING", "WORKING", "REVIEWING", "SYNTHESIZING", "FINALIZING"] as const;

export interface RunState {
  runId: string;
  question: string;
  status: RunStatus;
  stage: string;
  tools: ToolName[];
  events: RunEvent[];
  /** Keys of events already applied; replays are ignored. */
  seen: string[];
  lastEventId: string;
  finalResponse: FinalResponse | null;
  usage: Usage | null;
  errorCode: string | null;
}

export function emptyRun(question = "", tools: ToolName[] = []): RunState {
  return { runId: "", question, status: "READY", stage: "READY", tools, events: [], seen: [],
    lastEventId: "", finalResponse: null, usage: null, errorCode: null };
}

export const isTerminal = (status: string) => TERMINAL.has(status);

function eventKey(runId: string, event: RunEvent, cursorId?: string): string {
  return cursorId || event.id || (event.eventId == null ? "" : `${runId}:${event.eventId}`) || JSON.stringify(event);
}

/** Advance the SSE cursor; a numerically older id never moves it backwards. */
export function advanceCursor(runId: string, current: string, candidate: string): string {
  if (!candidate) return current;
  const prefix = runId + ":";
  const nextRaw = candidate.startsWith(prefix) ? candidate.slice(prefix.length) : "";
  const currentRaw = current.startsWith(prefix) ? current.slice(prefix.length) : "";
  const next = /^\d+$/.test(nextRaw) ? Number(nextRaw) : null;
  const now = /^\d+$/.test(currentRaw) ? Number(currentRaw) : null;
  if (next != null && now != null && next < now) return current;
  if (!current || next != null || candidate === current) return candidate;
  return current;
}

const STATUS_FROM_TYPE: Record<string, RunStatus> = {
  SUCCEEDED: "SUCCEEDED", INSUFFICIENT_EVIDENCE: "INSUFFICIENT_EVIDENCE", FAILED: "FAILED", CANCELLED: "CANCELLED",
};

export function applyEvent(run: RunState, event: RunEvent, cursorId?: string): RunState {
  const key = eventKey(run.runId, event, cursorId);
  if (run.seen.includes(key)) return run;
  const stage = event.type === "STAGE_CHANGED" && typeof event.payload?.stage === "string" ? event.payload.stage : null;
  const terminal = STATUS_FROM_TYPE[event.type];
  return {
    ...run,
    events: [...run.events, event],
    seen: [...run.seen, key],
    lastEventId: advanceCursor(run.runId, run.lastEventId, cursorId || event.id || ""),
    stage: terminal ? "TERMINAL" : stage ?? run.stage,
    status: terminal ?? (stage && !isTerminal(run.status) ? stage : run.status),
  };
}

/** Merge an authoritative snapshot (GET /workflows/{id}); trace events are de-duplicated. */
export function applyView(run: RunState, view: WorkflowView): RunState {
  let next: RunState = { ...run, runId: view.runId || run.runId };
  for (const event of view.trace ?? []) next = applyEvent(next, event, event.id ?? undefined);
  return {
    ...next,
    status: view.status ?? next.status,
    stage: view.stage ?? next.stage,
    tools: view.requestedTools ?? next.tools,
    finalResponse: view.finalResponse ?? next.finalResponse,
    usage: view.usage ?? next.usage,
    errorCode: view.errorCode ?? next.errorCode,
  };
}

/** Last stage actually entered; used to show where a non-successful run stopped. */
export function lastReachedStage(run: RunState): string | null {
  let reached: string | null = null;
  for (const event of run.events) {
    const stage = event.type === "STAGE_CHANGED" ? event.payload?.stage : null;
    if (typeof stage === "string" && (STAGES as readonly string[]).includes(stage)) reached = stage;
  }
  return reached;
}

/** Evidence counts as recorded by TASK_COMPLETED events (tool-reported, not truth scores). */
export function recordedEvidenceCount(run: RunState): number | null {
  let total: number | null = null;
  for (const event of run.events) {
    const count = event.type === "TASK_COMPLETED" ? event.payload?.evidenceCount : undefined;
    if (typeof count === "number") total = (total ?? 0) + count;
  }
  return total;
}
