// Single authoritative run representation plus pure transitions. Event cursor and
// duplicate handling follow the V1 page (eventKey / advanceEventCursor).
import { publicTools } from "./tools";
import type { LegacyResponse } from "../api/endpoints";
import type { ExecutionMode, FinalResponse, RunEvent, RunStatus, ToolName, Usage, WorkflowView } from "./types";

export const TERMINAL = new Set<string>([
  "SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED",
  "MAX_ROUNDS_REACHED", "CITATION_VALIDATION_FAILED", "MODEL_TIMEOUT", "TOOL_TIMEOUT",
  "MODEL_RATE_LIMITED", "MODEL_SCHEMA_INVALID", "MODEL_PROVIDER_FAILED",
  "MODEL_EXECUTION_FAILED", "MODEL_CALL_FAILED",
]);

export const STAGES = ["QUEUED", "PLANNING", "WORKING", "REVIEWING", "SYNTHESIZING", "FINALIZING"] as const;

export interface RunState {
  runId: string;
  mode: ExecutionMode;
  sessionId: string;
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
  errorMessage: string | null;
  /**
   * True once a status snapshot (GET) that itself reported a terminal status has been applied.
   * A terminal SSE event alone is not enough: an earlier queued snapshot may already carry a
   * blank finalResponse, so the presence of finalResponse cannot mean "final details loaded".
   */
  terminalSnapshot?: boolean;
  /** Single Agent only: selected session context (selection is not proof of model use). */
  memoryContext?: { summarySelected?: boolean; recentMessageCount?: number; selectedMemoryCount?: number; modelUseVerification?: string } | null;
}

export function emptyRun(question = "", tools: ToolName[] = [], mode: ExecutionMode = "workflow"): RunState {
  return { runId: "", mode, sessionId: "", question, status: "READY", stage: "READY", tools, events: [], seen: [],
    lastEventId: "", finalResponse: null, usage: null, errorCode: null, errorMessage: null };
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
    sessionId: view.sessionId ?? next.sessionId,
    errorMessage: view.errorMessage ?? next.errorMessage,
    tools: view.requestedTools ? publicTools(view.requestedTools) : next.tools,
    finalResponse: view.finalResponse ?? next.finalResponse,
    usage: view.usage ?? next.usage,
    errorCode: view.errorCode ?? next.errorCode,
    terminalSnapshot: isTerminal(view.status ?? "") || (!!next.terminalSnapshot && isTerminal(next.status)),
  };
}

/** The run is terminal but its authoritative terminal snapshot has not been read yet. */
export const needsTerminalSnapshot = (run: RunState | null | undefined) => !!run && isTerminal(run.status) && !run.terminalSnapshot;

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

/** Map the synchronous Single Agent response (AgentResearchResponse) to a run (V1 renderLegacy rules). */
export function mapLegacy(question: string, data: LegacyResponse): RunState {
  const runId = data.runId || "";
  const native = String(data.status || "").trim().replace(/[\s-]+/g, "_").toUpperCase();
  let status: string = data.finished === false ? native || "FAILED" : "SUCCEEDED";
  if (!TERMINAL.has(status)) status = "FAILED";
  let run: RunState = { ...emptyRun(question, [], "legacy"), runId, sessionId: data.sessionId || "", status: "WORKING", stage: "WORKING" };
  for (const event of data.events ?? []) {
    const id = `${runId}:native:${event.seq}`;
    run = applyEvent(run, { id, type: event.type || "EVENT", role: event.action || "AGENT",
      taskId: event.round == null ? null : `round-${event.round}`, payload: { summary: event.message || "" } }, id);
  }
  const u = data.usage;
  const diagnostics = data.memoryContext?.diagnostics;
  return {
    ...run,
    status, stage: "TERMINAL",
    errorCode: data.finished === false ? native || "FAILED" : null,
    finalResponse: {
      answer: data.answer || "",
      citations: Array.isArray(data.citations) ? data.citations : [],
      citationContract: data.citationContract || "NONE",
      citationDetails: Array.isArray(data.citationDetails) ? (data.citationDetails as FinalResponse["citationDetails"]) : undefined,
    },
    usage: u ? { modelCalls: u.modelCalls ?? data.rounds ?? null, toolCalls: u.toolCalls ?? null, totalTokens: u.totalTokens ?? null,
      inputTokens: u.inputTokens ?? null, outputTokens: u.outputTokens ?? null, durationMs: u.durationMs ?? null,
      estimatedCost: u.estimatedCost ?? null, costCurrency: u.costCurrency, estimated: !!u.estimated }
      : { modelCalls: data.rounds ?? null, toolCalls: (data.steps ?? []).length },
    memoryContext: diagnostics ? { summarySelected: diagnostics.summarySelected, recentMessageCount: diagnostics.recentMessageCount,
      selectedMemoryCount: diagnostics.selectedMemoryCount, modelUseVerification: diagnostics.modelUseVerification ?? "unknown" } : null,
  };
}

export interface PlanTask { objective: string; status: string; evidenceCount: number | null; criteria: Array<{ text: string; status: string }> }
export interface RecordedPlan { version: number | null; reason: string; tasks: PlanTask[]; revised: boolean }

/** Latest plan recorded by an autonomous run (AGENT_PLAN_UPDATED / AGENT_PLAN_REVISED), as published. */
export function latestPlan(run: RunState): RecordedPlan | null {
  for (let i = run.events.length - 1; i >= 0; i -= 1) {
    const event = run.events[i];
    if (event.type !== "AGENT_PLAN_UPDATED" && event.type !== "AGENT_PLAN_REVISED") continue;
    const p = (event.payload ?? {}) as Record<string, unknown>;
    const tasks = Array.isArray(p.tasks) ? p.tasks : [];
    return {
      version: typeof p.planVersion === "number" ? p.planVersion : null,
      reason: typeof p.reason === "string" ? p.reason : "",
      revised: event.type === "AGENT_PLAN_REVISED",
      tasks: tasks.filter((t): t is Record<string, unknown> => !!t && typeof t === "object").map((t) => ({
        objective: String(t.objective ?? "未命名任务"),
        status: String(t.status ?? "pending"),
        evidenceCount: typeof t.evidenceCount === "number" ? t.evidenceCount : null,
        criteria: Array.isArray(t.criteria) ? t.criteria.filter((c): c is Record<string, unknown> => !!c && typeof c === "object")
          .map((c) => ({ text: String(c.text ?? ""), status: String(c.status ?? "uncovered") })) : [],
      })),
    };
  }
  return null;
}

/** Gaps recorded when an autonomous run stopped early (AGENT_STOPPED_WITH_GAPS). */
export function recordedGaps(run: RunState): string[] {
  return run.events.filter((e) => e.type === "AGENT_STOPPED_WITH_GAPS")
    .flatMap((e) => Array.isArray(e.payload?.gaps) ? (e.payload!.gaps as unknown[]).map(String) : []);
}
