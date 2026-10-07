// Budget-stop draft (research-draft/1): strict validation, text-only content, exact download.
import { describe, expect, it } from "vitest";
import { normalizeCitations } from "./citations";
import { draftDownload, readResearchDraft } from "./researchDraft";

const valid = () => ({
  schemaVersion: "research-draft/1", reasonCode: "BUDGET_EXCEEDED", generatedFrom: "STORED_RECORDS", additionalModelCalls: 0,
  sources: [
    { sourceId: "s1", kind: "WEB_ORIGINAL", title: "Kafka docs", url: "https://kafka.apache.org/doc", excerpt: "<script>alert(1)</script> latency", excerptTruncated: true, observedAt: "2026-10-07T00:00:00Z", verificationStatus: "NOT_CLAIM_CHECKED" },
    { sourceId: "s2", kind: "WEB_SEARCH_SNAPSHOT", title: "Search result", url: null, excerpt: "snippet", excerptTruncated: false, observedAt: null, verificationStatus: "NOT_CLAIM_CHECKED" },
  ],
  checkedClaims: [{ text: "A 比 B 快", decisionStatus: "contested", applicability: { version: { status: "unknown", value: null }, validAt: { status: "unknown", value: null }, conditions: ["同硬件"] }, sourceIds: ["s1"] }],
  pendingTasks: [{ text: "测量延迟", status: "pending" }],
  limits: { sourceLimit: 20, excerptChars: 1800, omittedSources: 3, omittedClaims: 0, omittedTasks: 1 },
  markdown: "# 阶段性资料草稿\n\n- Kafka docs（尚未核查）\n",
});

describe("research draft", () => {
  it("absent keeps the previous budget-stop behaviour", () => {
    expect(readResearchDraft(undefined)).toEqual({ state: "absent" });
    expect(readResearchDraft(null)).toEqual({ state: "absent" });
  });

  it("accepts the exact contract and keeps excerpts as plain text", () => {
    const r = readResearchDraft(valid());
    expect(r.state).toBe("ok");
    if (r.state !== "ok") return;
    expect(r.draft.sources.map((s) => s.kind)).toEqual(["WEB_ORIGINAL", "WEB_SEARCH_SNAPSHOT"]);
    expect(r.draft.sources[0].excerpt).toBe("<script>alert(1)</script> latency");   // rendered as React text, never HTML
    expect(r.draft.checkedClaims[0].applicability.version).toEqual({ status: "unknown", value: null });
    expect(r.draft.limits.omittedSources).toBe(3);
  });

  it("rejects anything outside the contract instead of guessing", () => {
    const bad: Array<[string, (d: ReturnType<typeof valid>) => unknown]> = [
      ["schema", (d) => ({ ...d, schemaVersion: "research-draft/2" })],
      ["reason", (d) => ({ ...d, reasonCode: "FAILED" })],
      ["origin", (d) => ({ ...d, generatedFrom: "MODEL" })],
      ["model calls", (d) => ({ ...d, additionalModelCalls: 1 })],
      ["kind", (d) => ({ ...d, sources: [{ ...d.sources[0], kind: "BLOG" }] })],
      ["verification", (d) => ({ ...d, sources: [{ ...d.sources[0], verificationStatus: "CHECKED" }] })],
      ["limit", (d) => ({ ...d, limits: { ...d.limits, sourceLimit: 1 } })],
      ["claim ids", (d) => ({ ...d, checkedClaims: [{ ...d.checkedClaims[0], sourceIds: [1] }] })],
      ["markdown", (d) => ({ ...d, markdown: null })],
      ["not object", () => "draft"],
    ];
    for (const [name, mutate] of bad) expect(readResearchDraft(mutate(valid())).state, name).toBe("invalid");
  });

  it("download is exactly the server markdown, with a safe filename", async () => {
    const d = valid();
    const { blob, filename } = draftDownload(d.markdown, "wf-50124710/../x");
    expect(await blob.text()).toBe(d.markdown);
    expect(filename).toBe("research-draft-wf-50124710x.md");
  });

  it("draft sources never enter the report's citations", () => {
    expect(normalizeCitations([], "INDEXED_V1", []).length).toBe(0);
    expect(normalizeCitations(undefined, undefined, undefined).length).toBe(0);
  });
});
