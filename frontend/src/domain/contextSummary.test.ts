// Memory M2: parsing the owned context-summary view. READY is the recorded verified native response
// (synthetic fixture data); the other states are derived from it to cover honest wording paths.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { KNOWN_COVERAGE_NOTES, parseContextSummary, valueText } from "./contextSummary";

const ready = JSON.parse(readFileSync(join(fileURLToPath(new URL(".", import.meta.url)), "../api/__fixtures__/context-summary/ready.json"), "utf8"));

describe("context summary view", () => {
  it("READY: sections in server order, measurement in bytes, sources and coverage kept", () => {
    const v = parseContextSummary(ready);
    expect(v.status).toBe("READY");
    expect(v.measurement).toEqual({ method: "utf8-canonical-bytes/1", beforeBytes: 50840, afterBytes: 23379, budgetBytes: 24000 });
    expect(v.withinBudget).toBe(true);
    expect(v.plannerInputRecorded).toBe(true);
    expect(v.summary?.sections.map((s) => s.key)).toEqual(["goals", "constraints", "findings", "disputes", "failed_attempts", "unfinished", "next_steps"]);
    const constraints = v.summary!.sections.find((s) => s.key === "constraints")!;
    expect(valueText(constraints.entries[0].value)).toBe("同数据集；同硬件；无实测数据不得编造数值");
    const disputes = v.summary!.sections.find((s) => s.key === "disputes")!;
    expect(valueText(disputes.entries[0].value)).toContain("contested");
    expect(valueText(disputes.entries[0].value)).not.toContain("record_sha256");
    expect(v.summary?.coveredRecords).toHaveLength(6);
    expect(v.sources).toHaveLength(6);
    expect(v.summary?.excerpts[0].span).toEqual([ready.summary.excerpts[0].start_codepoint, ready.summary.excerpts[0].end_codepoint]);
  });

  it("FAILED (native shape): uncovered source_ref strings resolve to locator/hash; unknown refs stay visible", () => {
    const [first, second] = ready.sources as Array<{ source_ref: string; locator: string; record_sha256: string }>;
    const v = parseContextSummary({ ...ready, status: "FAILED", error_code: "PROJECT_CONTEXT_SUMMARY_FAILED",
      uncovered_records: [first.source_ref, second.source_ref, "src-not-in-sources"] });
    expect(v.status).toBe("FAILED");
    expect(v.errorCode).toBe("PROJECT_CONTEXT_SUMMARY_FAILED");
    expect(v.summary).not.toBeNull();                     // previous valid summary retained
    expect(v.uncoveredRecords).toEqual([
      { sourceRef: first.source_ref, locator: first.locator, recordSha256: first.record_sha256, resolved: true },
      { sourceRef: second.source_ref, locator: second.locator, recordSha256: second.record_sha256, resolved: true },
      { sourceRef: "src-not-in-sources", locator: null, recordSha256: null, resolved: false },
    ]);
    expect(v.sources).toHaveLength(6);                    // originals still listed in full
  });

  it("maps the known English coverage note to Chinese; unknown notes are not translated", () => {
    expect(KNOWN_COVERAGE_NOTES[parseContextSummary(ready).summary!.coverageNote]).toContain("这不是核验");
    expect(KNOWN_COVERAGE_NOTES["something else"]).toBeUndefined();
  });

  it("FAILED without a previous summary, and NOT_GENERATED, have no summary content", () => {
    expect(parseContextSummary({ ...ready, status: "FAILED", summary: null }).summary).toBeNull();
    const none = parseContextSummary({ schema_version: "project-context-summary-view/1", status: "NOT_GENERATED", run_id: "wf-x", summary: null, sources: [], uncovered_records: [] });
    expect(none.status).toBe("NOT_GENERATED");
    expect(none.summary).toBeNull();
  });

  it("unknown schema is rejected; an unknown status is UNKNOWN, not READY", () => {
    expect(() => parseContextSummary({ ...ready, schema_version: "project-context-summary-view/2" })).toThrow(/未知的摘要格式/);
    expect(parseContextSummary({ ...ready, status: "DONE" }).status).toBe("UNKNOWN");
  });
});
