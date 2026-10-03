// Types mirror the existing public contracts; they are adapters, not new contracts.
// Sources: WorkflowDtos.View / Event / Accepted (Java) and the finalResponse
// fields read by the V1 page (answer, citations, citationContract, citationDetails,
// report_status, unfinished_goals).

export type RunStatus =
  | "READY" | "QUEUED" | "PLANNING" | "WORKING" | "REVIEWING" | "SYNTHESIZING" | "FINALIZING"
  | "SUCCEEDED" | "INSUFFICIENT_EVIDENCE" | "FAILED" | "CANCELLED" | "TIMED_OUT" | "BUDGET_EXCEEDED"
  | (string & {});

export type ExecutionMode = "workflow" | "agent" | "legacy";

export type ToolName = "kb_search" | "web_search" | "calculator";

/** WorkflowDtos.Event */
export interface RunEvent {
  eventId?: number;
  id?: string;
  type: string;
  role?: string | null;
  taskId?: string | null;
  payload?: Record<string, unknown> | null;
  createdAt?: string | null;
}

/** One entry of finalResponse.citationDetails (matched only by exact sourceId). */
export interface CitationDetail {
  sourceId: string;
  kind: "WEB_SEARCH_SNAPSHOT" | "WEB_ORIGINAL" | "KNOWLEDGE_CHUNK" | (string & {});
  title?: string | null;
  url?: string | null;
  excerpt?: string | null;
}

/** Items in finalResponse.unfinished_goals (shape read from EvidenceService). */
export interface UnfinishedGoal {
  text?: string;
  task_id?: string;
  criterion_id?: string;
  investigation_id?: string;
  call_id?: string;
  reason?: string;
}

export interface FinalResponse {
  answer?: string;
  citations?: string[];
  citationContract?: string;
  citationDetails?: CitationDetail[];
  report_status?: "complete" | "partial" | "insufficient" | (string & {});
  unfinished_goals?: Array<UnfinishedGoal | string>;
  /** Workflow modes: true when the run ended as INSUFFICIENT_EVIDENCE. */
  insufficientEvidence?: boolean;
  /** Autonomous (candidate) runs: published claims. Shape not yet mapped in the UI. */
  claims?: unknown;
  semantic_truth_guaranteed?: boolean;
  /** Dify engine: safe failure diagnostics ({ node, code }). */
  diagnostics?: { node?: string; code?: string } | Record<string, never>;
}

export interface Usage {
  modelCalls?: number | null;
  toolCalls?: number | null;
  totalTokens?: number | null;
  inputTokens?: number | null;
  outputTokens?: number | null;
  durationMs?: number | null;
  estimatedCost?: number | null;
  currency?: string;
  costCurrency?: string;
  /** Single Agent usage may be estimated; shown as such, never upgraded to measured. */
  estimated?: boolean;
  costStatus?: "unknown" | (string & {});
  inputTokensStatus?: "unknown" | (string & {});
  outputTokensStatus?: "unknown" | (string & {});
}

/** WorkflowDtos.View */
export interface WorkflowView {
  runId: string;
  sessionId?: string | null;
  status: RunStatus;
  stage?: string | null;
  progress?: number;
  requestedTools?: ToolName[];
  trace?: RunEvent[];
  usage?: Usage | null;
  finalResponse?: FinalResponse | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}
