// Project context summary used by an agent run (memory M2), read-only.
// GET /api/research/agents/{runId}/context-summary, schema project-context-summary-view/1.
// Everything shown is saved original text with its locator; nothing here is verified evidence.

export type SummaryStatus = "READY" | "FAILED" | "NOT_GENERATED";
export interface SummaryEntry { value: unknown; locator: string; recordSha256: string; sourceRef: string | null; span: [number, number] | null }
export interface SummarySource { category: string; locator: string; recordSha256: string; sourceRef: string; value: unknown }
export interface ContextSummary {
  status: SummaryStatus | "UNKNOWN";
  runId: string;
  projectId: string | null;
  errorCode: string | null;
  plannerInputRecorded: boolean | null;
  withinBudget: boolean | null;
  measurement: { method: string; beforeBytes: number | null; afterBytes: number | null; budgetBytes: number | null } | null;
  sourceSha256: string | null;
  summary: null | {
    summarySha256: string | null;
    /** Raw server note (English for this contract); shown only in technical details. */
    coverageNote: string;
    sections: Array<{ key: string; entries: SummaryEntry[] }>;
    excerpts: Array<{ category: string; text: string; sourceRef: string | null; span: [number, number] | null }>;
    coveredRecords: Array<{ locator: string; recordSha256: string; sourceRef: string | null }>;
  };
  /** Native sends source_ref strings; each is resolved against `sources`. Unresolved refs stay visible. */
  uncoveredRecords: Array<{ sourceRef: string; locator: string | null; recordSha256: string | null; resolved: boolean }>;
  sources: SummarySource[];
}

/** Server order of the summary sections. */
export const SECTION_ORDER = ["goals", "constraints", "findings", "disputes", "failed_attempts", "unfinished", "next_steps"] as const;

const obj = (v: unknown): Record<string, unknown> => (v && typeof v === "object" && !Array.isArray(v) ? v as Record<string, unknown> : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string | null => (typeof v === "string" ? v : null);
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);
const bool = (v: unknown): boolean | null => (typeof v === "boolean" ? v : null);
const span = (r: Record<string, unknown>): [number, number] | null =>
  num(r.start_codepoint) != null && num(r.end_codepoint) != null ? [num(r.start_codepoint)!, num(r.end_codepoint)!] : null;

export class SummaryContractError extends Error { constructor(message: string) { super(message); this.name = "SummaryContractError"; } }

export function parseContextSummary(raw: unknown): ContextSummary {
  const v = obj(raw);
  if (v.schema_version !== "project-context-summary-view/1") throw new SummaryContractError("未知的摘要格式：" + String(v.schema_version ?? "缺失"));
  const status = v.status === "READY" || v.status === "FAILED" || v.status === "NOT_GENERATED" ? v.status : "UNKNOWN";
  const m = v.measurement == null ? null : obj(v.measurement);
  const s = v.summary == null ? null : obj(v.summary);
  const sections = s ? SECTION_ORDER.map((key) => ({ key, entries: arr(obj(s.sections)[key]).map((e) => {
    const r = obj(e);
    return { value: r.value, locator: str(r.locator) ?? "", recordSha256: str(r.record_sha256) ?? "", sourceRef: str(r.source_ref), span: span(r) };
  }) })).filter((x) => x.entries.length) : [];
  const sources: SummarySource[] = arr(v.sources).map((e) => { const r = obj(e); return { category: str(r.category) ?? "", locator: str(r.locator) ?? "", recordSha256: str(r.record_sha256) ?? "", sourceRef: str(r.source_ref) ?? "", value: r.value }; });
  return {
    status, runId: str(v.run_id) ?? "", projectId: str(v.project_id), errorCode: str(v.error_code),
    plannerInputRecorded: bool(v.planner_input_recorded), withinBudget: bool(v.within_budget),
    measurement: m ? { method: str(m.method) ?? "", beforeBytes: num(m.before_bytes), afterBytes: num(m.after_bytes), budgetBytes: num(m.budget_bytes) } : null,
    sourceSha256: str(v.source_sha256),
    summary: s ? {
      summarySha256: str(s.summary_sha256), coverageNote: str(s.coverage_note) ?? "", sections,
      excerpts: arr(s.excerpts).map((e) => { const r = obj(e); return { category: str(r.category) ?? "", text: str(r.text) ?? "", sourceRef: str(r.source_ref), span: span(r) }; }),
      coveredRecords: arr(s.covered_records).map((e) => { const r = obj(e); return { locator: str(r.locator) ?? "", recordSha256: str(r.record_sha256) ?? "", sourceRef: str(r.source_ref) }; }),
    } : null,
    uncoveredRecords: arr(v.uncovered_records).map((e) => {
      const ref = typeof e === "string" ? e : str(obj(e).source_ref) ?? "";
      const source = sources.find((s) => s.sourceRef === ref);
      return { sourceRef: ref, locator: source?.locator ?? null, recordSha256: source?.recordSha256 ?? null, resolved: !!source };
    }),
    sources,
  };
}

/** Chinese wording for the known server coverage note; unknown notes are not used as the main explanation. */
export const KNOWN_COVERAGE_NOTES: Record<string, string> = {
  "Extractive selection; nonselected text remains in originals. Not verification.": "摘录式选择：未选入的文字仍完整保留在原始记录中；这不是核验。",
};

/** Plain text for a saved value (string, list or record), for display only; React escapes it. */
export function valueText(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "string") return value;
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) return value.map(valueText).filter(Boolean).join("；");
  return Object.entries(value as Record<string, unknown>)
    .filter(([k]) => !/sha256$/.test(k))
    .map(([k, x]) => `${k}: ${valueText(x)}`).join("，");
}
