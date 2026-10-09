// Cross-question recall of an agent run (memory M4), read-only: research-recall-view/1.
// Recalled records are saved research used as clues to re-check — never verified evidence, and old
// completed work never closes the new run's requirements. UNAVAILABLE carries no old payload.
import { parseSnapshot, type ProgressSnapshot } from "./progressMemory";

export type RecallStatus = "DISABLED" | "EMPTY" | "SELECTED" | "USED" | "UNAVAILABLE";
const STATUSES: readonly string[] = ["DISABLED", "EMPTY", "SELECTED", "USED", "UNAVAILABLE"];

export interface Unknownable { status: string; value: unknown; reason: string }
export interface RecallSource { sourceKey: string; label: string; evidenceId: string; sourceRunId: string; snapshotSha256: string }
export interface RecallRecord {
  sourceProjectId: string; sourceRunId: string; snapshotSha256: string;
  snapshot: ProgressSnapshot | null;
  /** Shape problem in the recalled snapshot: shown as "cannot confirm", never as its content. */
  snapshotError: string | null;
  reason: { method: string; matchedTerms: string[]; score: number | null };
  applicability: { status: string; cautions: string[]; mentionedVersions: string[]; time: Unknownable | null; conditions: Unknownable | null };
  sourceRefs: RecallSource[];
}
export interface RecallView {
  runId: string; projectId: string | null;
  status: RecallStatus | "UNKNOWN";
  plannerInputRecorded: boolean;
  records: RecallRecord[];
  selection: null | { candidateLimit: number | null; maxRecords: number | null; limitBytes: number | null; method: string;
    unrelated: number; inaccessible: number; duplicates: number; omitted: number };
  message: string;
}

export class RecallContractError extends Error { constructor(message: string) { super(message); this.name = "RecallContractError"; } }

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const strings = (v: unknown): string[] => arr(v).filter((x): x is string => typeof x === "string");
const unknownable = (v: unknown): Unknownable | null => {
  if (v == null) return null;
  const r = obj(v);
  return { status: str(r.status) ?? "unknown", value: r.value ?? null, reason: str(r.reason) ?? "" };
};
/** A readable label from whatever native source fields exist; falls back to the stable key. */
function sourceLabel(source: Record<string, unknown>, key: string): string {
  for (const field of ["title", "name", "url", "locator", "document_id", "documentId", "source_id"]) {
    const value = str(source[field]);
    if (value) return value;
  }
  return key;
}

export function parseRecall(value: unknown): RecallView {
  const raw = obj(value);
  if (raw.schema_version !== "research-recall-view/1") throw new RecallContractError("未知的历史参考格式：" + String(raw.schema_version ?? "缺失"));
  if (raw.trusted_as_evidence !== false) throw new RecallContractError("历史参考的 trusted_as_evidence 应为 false。");
  const status = str(raw.status);
  const known = status && STATUSES.includes(status) ? status as RecallStatus : "UNKNOWN";
  // UNAVAILABLE (and anything unknown) exposes no old payload, even if the server sent some.
  const exposeRecords = known === "SELECTED" || known === "USED";
  const sel = raw.selection == null ? null : obj(raw.selection);
  return {
    runId: str(raw.run_id) ?? "", projectId: str(raw.project_id), status: known,
    plannerInputRecorded: raw.planner_input_recorded === true && known === "USED",
    message: str(raw.message) ?? "",
    selection: sel && exposeRecords ? {
      candidateLimit: num(sel.candidate_limit), maxRecords: num(sel.max_records), limitBytes: num(sel.limit_bytes), method: str(sel.method) ?? "",
      unrelated: num(sel.unrelated) ?? 0, inaccessible: num(sel.inaccessible) ?? 0, duplicates: num(sel.duplicates) ?? 0, omitted: num(sel.omitted) ?? 0,
    } : null,
    records: exposeRecords ? arr(raw.records).map((r): RecallRecord => {
      const x = obj(r);
      let snapshot: ProgressSnapshot | null = null; let snapshotError: string | null = null;
      try { snapshot = parseSnapshot(x.snapshot); } catch (e) { snapshotError = (e as Error).message; }
      const reason = obj(x.reason), app = obj(x.applicability);
      // Deduplicate native sources by key: the same original counts once, never as independent evidence.
      const seen = new Set<string>();
      const sourceRefs: RecallSource[] = [];
      for (const s of arr(x.source_refs)) {
        const ref = obj(s), key = str(ref.source_key) ?? "";
        if (!key || seen.has(key)) continue;
        seen.add(key);
        sourceRefs.push({ sourceKey: key, label: sourceLabel(obj(ref.source), key), evidenceId: str(ref.evidence_id) ?? "", sourceRunId: str(ref.source_run_id) ?? "", snapshotSha256: str(ref.snapshot_sha256) ?? "" });
      }
      return {
        sourceProjectId: str(x.source_project_id) ?? "", sourceRunId: str(x.source_run_id) ?? "", snapshotSha256: str(x.snapshot_sha256) ?? "",
        snapshot, snapshotError,
        reason: { method: str(reason.method) ?? "", matchedTerms: strings(reason.matched_terms), score: num(reason.score) },
        applicability: { status: str(app.status) ?? "unknown", cautions: strings(app.cautions), mentionedVersions: strings(app.mentioned_versions),
          time: unknownable(app.time), conditions: unknownable(app.conditions) },
        sourceRefs,
      };
    }) : [],
  };
}
