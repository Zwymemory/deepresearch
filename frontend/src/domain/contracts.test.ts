// Contract tests built from the backend's published synthetic fixtures
// (docs/agent/fixtures/frontend-sources/*.json and FRONTEND_EVIDENCE_READ_API_2026-10-03.md).
import { describe, expect, it } from "vitest";
import { getEvidenceView } from "../api/endpoints";
import { normalizeCitations, readModeLabel } from "./citations";
import { linkedEvidence, recordedDisagreements, taggedDate, type EvidenceView } from "./evidenceView";
import type { CitationDetail } from "./types";

const KB_ID = "ragflow:fixture-dataset:fixture-document:fixture-chunk";
const WEB_ID = "https://example.org/limits";

describe("source metadata contract", () => {
  it("langgraph-available: both kinds, KB without URL, provenance marked available", () => {
    const details: CitationDetail[] = [
      { sourceId: KB_ID, kind: "KNOWLEDGE_CHUNK", title: "Synthetic managed document", url: null, excerpt: "Synthetic retrieved knowledge excerpt.", metadataStatus: "AVAILABLE", unavailableReason: null },
      { sourceId: WEB_ID, kind: "WEB_SEARCH_SNAPSHOT", title: "Synthetic public source", url: WEB_ID, excerpt: "Synthetic search excerpt.", metadataStatus: "AVAILABLE", unavailableReason: null },
    ];
    const [kb, web] = normalizeCitations([KB_ID, WEB_ID], "INDEXED_V1", details);
    expect([kb.kind, kb.url, kb.metadataStatus]).toEqual(["knowledge", null, "available"]);
    expect([web.kind, web.url?.href, web.missingReason]).toEqual(["web-snapshot", WEB_ID, null]);
    expect(readModeLabel(web.kind)).toMatch(/非原页/);
  });
  it("ambiguous / historical / limit snapshots stay unavailable with their exact reason", () => {
    for (const reason of ["AMBIGUOUS_SNAPSHOT", "MISSING_SNAPSHOT", "SNAPSHOT_LIMIT"]) {
      const [c] = normalizeCitations([WEB_ID], "INDEXED_V1", [{ sourceId: WEB_ID, kind: "UNKNOWN", title: null, url: null, excerpt: null, metadataStatus: "UNAVAILABLE", unavailableReason: reason }]);
      expect(c.kind).toBe("unknown");
      expect(c.url).toBeNull();
      expect(c.metadataStatus).toBe("unavailable");
      expect(c.unavailableReason).toBe(reason);
    }
    const [ambiguous] = normalizeCitations([WEB_ID], "INDEXED_V1", [{ sourceId: WEB_ID, kind: "UNKNOWN", metadataStatus: "UNAVAILABLE", unavailableReason: "AMBIGUOUS_SNAPSHOT" }]);
    expect(ambiguous.missingReason).toMatch(/不一致/);
  });
  it("Dify keeps its shape: no availability field is not treated as AVAILABLE; retrievedAt is retrieval time", () => {
    const id = "web:tavily:90509117c65eb3520516861eca48cb1f7e0f441320d634802dc6b500062c4e0c";
    const [c] = normalizeCitations([id], "INDEXED_V1", [{ sourceId: id, kind: "WEB_SEARCH_SNAPSHOT", title: "Synthetic public source", url: WEB_ID, excerpt: "x", retrievedAt: "2026-01-01T00:00:00Z" }]);
    expect(c.metadataStatus).toBe("unspecified");
    expect(c.retrievedAt).toBe("2026-01-01T00:00:00Z");
  });
  it("autonomous KB items with url \"\" never become links", () => {
    const id = "kb:" + KB_ID;
    const [c] = normalizeCitations([id], "INDEXED_V1", [{ sourceId: id, kind: "KNOWLEDGE_CHUNK", title: "Synthetic managed document", url: "", excerpt: "Synthetic verified quotation." }]);
    expect([c.kind, c.url]).toEqual(["knowledge", null]);
  });
  it("manual-react: NONE contract with empty lists has nothing to inspect", () => {
    expect(normalizeCitations([], "NONE", [])).toEqual([]);
  });
});

const identity = (recordType: string, recordId: string) => ({ recordType, recordId, version: 1, payloadSha256: "0".repeat(64), recordedAt: "2026-10-05T00:00:00Z" });
const evidence = (id: string, kind: "web" | "knowledge", url: string | null) => ({ identity: identity("Evidence", id), sourceId: "src-" + id, kind, title: "Synthetic " + id, url,
  publishedAt: { status: "unknown", value: null }, observedAt: "2026-10-05T00:00:00Z", snapshotSha256: "1".repeat(64), applicability: null });
const CONFLICT_VIEW: EvidenceView = {
  schemaVersion: "evidence-view/1", runId: "run-demo", runStatus: "INSUFFICIENT_EVIDENCE", availability: "AVAILABLE", publicationState: "RECORDED_ONLY",
  limits: { records: 256, checks: 128, sourceReads: 128, blockedAttempts: 128, responseBytes: 262144, completeProjection: true },
  limitations: ["RECORDED_OBSERVATIONS_ONLY", "EMPTY_DOES_NOT_PROVE_NO_CONFLICT"],
  evidence: [evidence("evidence-0", "web", "https://example.org/a"), evidence("evidence-1", "knowledge", null)],
  claims: [{ identity: identity("Claim", "claim-demo"), text: "API limit hypothesis", kind: "fact", applicability: null, decisionStatus: "contested", checkId: "check-demo",
    latestRecordedRound: true, publicationState: "RECORDED_ONLY", evidenceLinks: [
      { evidenceId: "evidence-0", evidenceVersion: 1, relation: "supports", disposition: "unresolved", quote: { start: 0, end: 5, sha256: "2".repeat(64), text: "quote", textAvailability: "AVAILABLE" } },
      { evidenceId: "evidence-1", evidenceVersion: 1, relation: "refutes", disposition: "unresolved", quote: null }] }],
  decisions: [{ identity: identity("DecisionRecord", "decision-claim-demo"), claimId: "claim-demo", decisionStatus: "contested", policyVersion: "p1",
    adoptedEvidenceIds: [], unresolvedEvidenceIds: ["evidence-0", "evidence-1"], dismissedEvidence: [], gapCodes: [] }],
  checks: [], disagreements: [{ claimId: "claim-demo", decisionId: "decision-claim-demo", checkId: "check-demo", supportingEvidenceIds: ["evidence-0"], refutingEvidenceIds: ["evidence-1"] }],
  blockedAttempts: [],
};

describe("evidence view", () => {
  it("joins claim links to evidence by record id and version, and reports recorded disagreements as recorded", () => {
    const linked = linkedEvidence(CONFLICT_VIEW, CONFLICT_VIEW.claims[0]);
    expect(linked.map((l) => l.evidence?.identity.recordId)).toEqual(["evidence-0", "evidence-1"]);
    const [d] = recordedDisagreements(CONFLICT_VIEW);
    expect(d.claim.text).toBe("API limit hypothesis");
    expect(d.supporting.map((s) => s.evidence?.kind)).toEqual(["web"]);
    expect(d.refuting.map((s) => s.evidence?.kind)).toEqual(["knowledge"]);
    expect(d.decision?.decisionStatus).toBe("contested");
  });
  it("a version mismatch leaves the evidence unresolved instead of guessing", () => {
    const claim = { ...CONFLICT_VIEW.claims[0], evidenceLinks: [{ ...CONFLICT_VIEW.claims[0].evidenceLinks[0], evidenceVersion: 2 }] };
    expect(linkedEvidence(CONFLICT_VIEW, claim)[0].evidence).toBeNull();
  });
  it("an insufficient outcome has no disagreements and unknown dates stay unknown", () => {
    expect(recordedDisagreements({ ...CONFLICT_VIEW, disagreements: [] })).toEqual([]);
    expect(taggedDate({ status: "unknown", value: null })).toBe("未知");
  });
  it("maps 503 and 409 to capability states", async () => {
    const make = (status: number, body: object) => (async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;
    expect(await getEvidenceView({ origin: "http://x", token: "t", fetch: make(503, { errorCode: "EVIDENCE_VIEW_DISABLED" }) }, "r")).toEqual({ state: "disabled" });
    expect(await getEvidenceView({ origin: "http://x", token: "t", fetch: make(409, { errorCode: "EVIDENCE_VIEW_INTEGRITY_INVALID" }) }, "r")).toEqual({ state: "integrity" });
    const ok = await getEvidenceView({ origin: "http://x", token: "t", fetch: make(200, CONFLICT_VIEW) }, "r");
    expect(ok.state).toBe("ok");
    await expect(getEvidenceView({ origin: "http://x", token: "t", fetch: make(404, { error: "x" }) }, "r")).rejects.toMatchObject({ status: 404 });
  });
});

describe("notebook preview honesty", () => {
  it("saving a run never marks its cited sources as verified", async () => {
    const { previewRecordFromRun } = await import("../demo/memoryPreview");
    const { snapshotRun } = await import("../demo/useDemoRun");
    const record = previewRecordFromRun(snapshotRun("success", Infinity));
    expect(record.provenance.length).toBeGreaterThan(0);
    expect(record.provenance.every((p) => p.status === "unverified")).toBe(true);
  });
});
