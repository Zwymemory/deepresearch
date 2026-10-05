import { describe, expect, it } from "vitest";
import { citationPreview, isVerifiedMarker, normalizeCitations, safeCitationUrl } from "./citations";
import { parseMarkdown, statementsFor } from "./markdown";
import { advanceCursor, applyEvent, applyView, emptyRun, lastReachedStage, recordedEvidenceCount } from "./runState";
import type { CitationDetail } from "./types";

const web: CitationDetail = { sourceId: "web:a", kind: "WEB_SEARCH_SNAPSHOT", title: "Doc A", url: "https://example.com/a", excerpt: "x".repeat(90) };
const kb: CitationDetail = { sourceId: "kb:1", kind: "KNOWLEDGE_CHUNK", title: "architecture.md", excerpt: "知识库摘录".repeat(10) };

describe("normalizeCitations", () => {
  it("matches details only by exact sourceId, keeping citation order", () => {
    const result = normalizeCitations(["web:a", "kb:1"], "INDEXED_V1", [kb, web]);
    expect(result.map((c) => [c.number, c.sourceId, c.kind])).toEqual([[1, "web:a", "web-snapshot"], [2, "kb:1", "knowledge"]]);
    expect(result[0].url?.href).toBe("https://example.com/a");
    expect(result[1].url).toBeNull();
  });
  it("treats missing, duplicate or mismatched details as insufficient", () => {
    expect(normalizeCitations(["web:a"], "INDEXED_V1", [])[0].kind).toBe("unknown");
    expect(normalizeCitations(["web:a"], "INDEXED_V1", [web, { ...web, title: "Other" }])[0].missingReason).toMatch(/不一致/);
    expect(normalizeCitations(["web:a"], "INDEXED_V1", [{ ...web, sourceId: "web:b" }])[0].url).toBeNull();
  });
  it("never links without the INDEXED_V1 contract", () => {
    const [c] = normalizeCitations(["web:a"], "NONE", [web]);
    expect(c.indexed).toBe(false);
    expect(c.url).toBeNull();
  });
  it("rejects unsafe URLs", () => {
    for (const bad of ["javascript:alert(1)", "data:text/html,x", "file:///etc", "//x.test/a", "https://u:p@x.test/", "https://x.test/\na"]) {
      expect(safeCitationUrl(bad)).toBeNull();
    }
    const [c] = normalizeCitations(["web:a"], "INDEXED_V1", [{ ...web, url: "javascript:alert(1)" }]);
    expect(c.url).toBeNull();
    expect(c.missingReason).toMatch(/网页地址/);
  });
  it("strips the knowledge-base wrapper only in the preview", () => {
    const body = "合成中文片段：这段文字用于验证摘录预览跳过导航并保留知识库内容，不能作为真实问答或来源支持的证明。";
    const wrapped = "[UNTRUSTED_DATA_BEGIN source=knowledge-base]\n## 标题\n\n" + body + "\n\n[UNTRUSTED_DATA_END source=knowledge-base]";
    expect(citationPreview(wrapped)).toBe(body);
    const [c] = normalizeCitations(["kb:1"], "INDEXED_V1", [{ ...kb, excerpt: wrapped }]);
    expect(c.excerpt).toBe(wrapped);
  });
});

describe("markers and markdown", () => {
  it("verifies only canonical in-range markers under INDEXED_V1", () => {
    expect(isVerifiedMarker("[来源1]", "INDEXED_V1", 1)).toBe(1);
    expect(isVerifiedMarker("[来源 1]", "INDEXED_V1", 1)).toBeNull();
    expect(isVerifiedMarker("[source 1]", "INDEXED_V1", 1)).toBeNull();
    expect(isVerifiedMarker("[来源2]", "INDEXED_V1", 1)).toBeNull();
    expect(isVerifiedMarker("[来源1]", "NONE", 1)).toBeNull();
  });
  it("keeps markup as text and maps statements to citations", () => {
    const blocks = parseMarkdown("## 标题\n\n<img src=x onerror=alert(1)> 结论 [来源1] **重点 [来源2]**\n\n- 列表项 [来源2]", "INDEXED_V1", 2);
    expect(blocks[0]).toMatchObject({ type: "heading", text: "标题", id: "section-1" });
    expect(blocks[1]).toMatchObject({ type: "paragraph", citations: [1, 2] });
    expect((blocks[1] as { text: string }).text).toContain("<img src=x onerror=alert(1)>");
    expect(statementsFor(blocks, 2)).toHaveLength(2);
  });
});

describe("run state", () => {
  const base = { ...emptyRun("q"), runId: "r1" };
  it("ignores duplicate replayed events", () => {
    const event = { id: "r1:3", eventId: 3, type: "STAGE_CHANGED", payload: { stage: "WORKING" } };
    const once = applyEvent(base, event, "r1:3");
    expect(applyEvent(once, event, "r1:3")).toBe(once);
    expect(once.status).toBe("WORKING");
    expect(once.lastEventId).toBe("r1:3");
  });
  it("never moves the cursor backwards", () => {
    expect(advanceCursor("r1", "r1:9", "r1:4")).toBe("r1:9");
    expect(advanceCursor("r1", "r1:4", "r1:9")).toBe("r1:9");
  });
  it("merges snapshots and records where a failed run stopped", () => {
    const run = applyView(base, { runId: "r1", status: "FAILED", stage: "TERMINAL", trace: [
      { id: "r1:1", type: "STAGE_CHANGED", payload: { stage: "PLANNING" } },
      { id: "r1:2", type: "TASK_COMPLETED", payload: { evidenceCount: 3 } },
      { id: "r1:3", type: "STAGE_CHANGED", payload: { stage: "SYNTHESIZING" } },
      { id: "r1:4", type: "FAILED", payload: { errorCode: "MODEL_PROVIDER_FAILED" } },
    ] });
    expect(run.status).toBe("FAILED");
    expect(lastReachedStage(run)).toBe("SYNTHESIZING");
    expect(recordedEvidenceCount(run)).toBe(3);
    expect(recordedEvidenceCount(base)).toBeNull();
  });
});
