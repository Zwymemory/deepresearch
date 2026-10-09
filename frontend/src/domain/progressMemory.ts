// Research progress memory — types and defensive normalization for the backend contract
// (RESEARCH_PROGRESS_FRONTEND_HANDOFF_2026-10-05; schemas research-progress/1,
// research-progress-list/1, research-resume-context/1). Saved progress is prior context:
// `trusted_as_evidence` is always false and nothing here is newly verified.

export interface ProgressCriterion {
  criterionId: string | null;
  text: string;
  status: string;
  gaps: string[];
}

export interface ProgressGoal {
  /** Run-level gap objects have no task id or criteria. */
  taskId: string | null;
  goal: string;
  status: string;
  completionVerified: boolean;
  gaps: string[];
  criteria: ProgressCriterion[];
  errorCode: string | null;
  /** Carried over from an earlier saved snapshot (M3); never proof for the current run. */
  historical: boolean;
  /** Run the carried item came from, when historical. */
  fromRunId: string | null;
}

export interface ProgressSnapshot {
  projectId: string;
  sourceRunId: string;
  sourceSessionId: string | null;
  originalGoal: string;
  /** Status at explicit save time — not a live subscription. */
  runStatus: string;
  completedWork: ProgressGoal[];
  unresolvedQuestions: ProgressGoal[];
  nextSteps: string[];
  sourceEvidence: Array<{ evidenceId: string; receiptId: string | null; snapshotSha256: string | null; sourceId: string | null }>;
  sourceClaims: Array<{ claimId: string; recordSha256: string | null; decisionStatus: string; freshness: string | null }>;
  /** M3 fields; absent on older snapshots. */
  currentQuestion: string | null;
  /** "automatic" | "manual" as saved by the server; null when not recorded. */
  saveOrigin: string | null;
  /** Earlier saved progress carried forward; never proof of current completion. */
  historicalCompletedWork: ProgressGoal[];
  priorMemoryRefs: Array<{ sourceRunId: string; snapshotSha256: string | null }>;
  /** User annotation; never edits verified facts. "" means none. */
  userCorrection: string;
}

export interface ResumeContext {
  projectId: string;
  targetSessionId: string;
  progress: ProgressSnapshot[];
  usageInstruction: string;
}

export class ContractError extends Error {
  constructor(message: string) { super(message); this.name = "ContractError"; }
}

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === "object" && !Array.isArray(v) ? (v as Raw) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" && v.trim() ? v : null);
const strings = (v: unknown): string[] => arr(v).filter((x): x is string => typeof x === "string");

/** Notebook record identity: (project_id, source_run_id). There is no separate record UUID. */
export const recordKey = (projectId: string, runId: string) => `${projectId}\u0000${runId}`;

function goal(value: unknown): ProgressGoal {
  const g = obj(value);
  return {
    taskId: str(g.task_id),
    goal: str(g.goal) ?? "未命名目标",
    status: str(g.status) ?? "unknown",
    completionVerified: g.completion_verified === true,
    gaps: strings(g.gaps),
    // Nested criteria keep the existing camelCase fields; they are not snake_case.
    criteria: arr(g.criteria).map((c) => {
      // Some saved snapshots record criteria as plain strings: keep the text verbatim, with no invented status.
      if (typeof c === "string") return { criterionId: null, text: c, status: "", gaps: [] };
      const r = obj(c);
      return { criterionId: str(r.criterionId), text: str(r.text) ?? "", status: str(r.status) ?? "unknown", gaps: strings(r.gaps) };
    }),
    errorCode: str(g.error_code),
    historical: g.historical === true,
    fromRunId: str(g.source_run_id),
  };
}

function requireTrustBoundary(raw: Raw) {
  // The contract fixes these values; anything else means a shape we do not understand.
  if (raw.context_kind !== "prior_progress" || raw.trusted_as_evidence !== false) {
    throw new ContractError("研究进度的上下文类型不符合约定（应为 prior_progress 且 trusted_as_evidence=false）。");
  }
}

export function parseSnapshot(value: unknown): ProgressSnapshot {
  const raw = obj(value);
  if (raw.schema_version !== "research-progress/1") throw new ContractError("不支持的研究进度格式：" + String(raw.schema_version));
  requireTrustBoundary(raw);
  const projectId = str(raw.project_id), runId = str(raw.source_run_id);
  if (!projectId || !runId) throw new ContractError("研究进度缺少 project_id 或 source_run_id。");
  return {
    projectId, sourceRunId: runId, sourceSessionId: str(raw.source_session_id),
    originalGoal: str(raw.original_goal) ?? "（未记录原始问题）",
    runStatus: str(raw.run_status) ?? "UNKNOWN",
    completedWork: arr(raw.completed_work).map(goal),
    unresolvedQuestions: arr(raw.unresolved_questions).map(goal),
    nextSteps: strings(raw.next_steps),
    sourceEvidence: arr(raw.source_evidence).map((e) => {
      const r = obj(e);
      return { evidenceId: str(r.evidence_id) ?? "", receiptId: str(r.receipt_id), snapshotSha256: str(r.snapshot_sha256), sourceId: str(r.source_id) };
    }),
    sourceClaims: arr(raw.source_claims).map((c) => {
      const r = obj(c);
      return { claimId: str(r.claim_id) ?? "", recordSha256: str(r.record_sha256), decisionStatus: str(r.decision_status) ?? "unknown", freshness: str(r.freshness) };
    }),
    currentQuestion: str(raw.current_question),
    saveOrigin: str(raw.save_origin),
    historicalCompletedWork: arr(raw.historical_completed_work).map(goal),
    priorMemoryRefs: arr(raw.prior_memory_refs).map((r) => { const x = obj(r); return { sourceRunId: str(x.source_run_id) ?? "", snapshotSha256: str(x.snapshot_sha256) }; }),
    userCorrection: str(raw.user_correction) ?? "",
  };
}

// ---- automatic save status (research-progress-save/1, M3) ----
export type ProgressSaveStatus = "WAITING" | "PENDING" | "SAVED" | "FAILED" | "DELETED" | "NOT_ENABLED" | "UNAVAILABLE";
const SAVE_STATUSES: readonly string[] = ["WAITING", "PENDING", "SAVED", "FAILED", "DELETED", "NOT_ENABLED", "UNAVAILABLE"];
export interface ProgressSaveView {
  runId: string; projectId: string | null; enabled: boolean; saved: boolean;
  status: ProgressSaveStatus | "UNKNOWN"; errorCode: string | null;
  /** "automatic" | "manual" when the server reports it; null otherwise (never assumed automatic). */
  saveOrigin: string | null;
}

export function parseProgressSave(value: unknown): ProgressSaveView {
  const raw = obj(value);
  if (raw.schema_version !== "research-progress-save/1") throw new ContractError("不支持的保存状态格式：" + String(raw.schema_version));
  if (raw.trusted_as_evidence !== false) throw new ContractError("保存状态的 trusted_as_evidence 应为 false。");
  const status = str(raw.status);
  return {
    runId: str(raw.run_id) ?? "", projectId: str(raw.project_id),
    enabled: raw.enabled === true, saved: raw.saved === true,
    status: status && SAVE_STATUSES.includes(status) ? status as ProgressSaveStatus : "UNKNOWN",
    errorCode: str(raw.error_code), saveOrigin: str(raw.save_origin),
  };
}

export function parseList(value: unknown): { items: ProgressSnapshot[]; candidateLimit: number | null } {
  const raw = obj(value);
  if (raw.schema_version !== "research-progress-list/1") throw new ContractError("不支持的研究进度列表格式：" + String(raw.schema_version));
  return { items: arr(raw.items).map(parseSnapshot), candidateLimit: typeof raw.candidate_limit === "number" ? raw.candidate_limit : null };
}

export function parseResume(value: unknown): ResumeContext {
  const raw = obj(value);
  if (raw.schema_version !== "research-resume-context/1") throw new ContractError("不支持的上下文载入格式：" + String(raw.schema_version));
  requireTrustBoundary(raw);
  const projectId = str(raw.project_id), targetSessionId = str(raw.target_session_id);
  if (!projectId || !targetSessionId) throw new ContractError("载入结果缺少 project_id 或 target_session_id。");
  return { projectId, targetSessionId, progress: arr(raw.progress).map(parseSnapshot), usageInstruction: str(raw.usage_instruction) ?? "" };
}

export function parseProject(value: unknown): { projectId: string; runId: string } {
  const raw = obj(value);
  const projectId = str(raw.project_id), runId = str(raw.run_id);
  if (!projectId || !runId) throw new ContractError("项目发现结果缺少 project_id 或 run_id。");
  return { projectId, runId };
}

export const CLAIM_STATUS: Record<string, { label: string; chip: string }> = {
  supported: { label: "当时有证据支持", chip: "chip-ok" },
  refuted: { label: "当时被反驳", chip: "chip-error" },
  contested: { label: "有争议", chip: "chip-warn" },
  insufficient: { label: "证据不足", chip: "chip" },
  unverified: { label: "未核查", chip: "chip" },
};
