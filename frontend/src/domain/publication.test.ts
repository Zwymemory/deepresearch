// Report polish: the strict publication adapter re-lays out the publisher's text per claim without
// changing it. Fixture: the published answer of a real native run (text only, no identities).
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { parseMarkdown, statementsFor } from "./markdown";
import { parsePublication, type ClaimBlock } from "./publication";

const fixture = JSON.parse(readFileSync(join(fileURLToPath(new URL(".", import.meta.url)), "../api/__fixtures__/publication/native-succeeded.json"), "utf8")) as
  { answer: string; citationContract: string; citationCount: number };
const parse = (text: string) => parsePublication(text, fixture.citationContract, fixture.citationCount);
const claims = (blocks: ReturnType<typeof parse>) => (blocks ?? []).filter((b): b is ClaimBlock => b.type === "claim");
const markers = (text: string) => (text.match(/\[来源\d+\]/g) ?? []).length;

describe("publication adapter (native answer)", () => {
  const blocks = parse(fixture.answer);

  it("splits the report into one block per published claim, labels taken verbatim", () => {
    expect(blocks).not.toBeNull();
    expect(claims(blocks).map((c) => c.label)).toEqual(["服务崩溃后继续研究", "缺少验证证据的部分", "网络断线后继续研究", "避免重复创建任务"]);
    expect(claims(blocks).every((c) => c.status === "supported")).toBe(true);
    for (const c of claims(blocks)) expect(fixture.answer).toContain(c.text);   // no paraphrase
    for (const c of claims(blocks)) {
      // The heading carries the label; the body shows the rest of the same text, nothing removed.
      const body = c.inline.filter((n) => n.type === "text").map((n) => (n as { text: string }).text).join("").trim();
      expect(`${c.label}：${body}`).toBe(c.text);
    }
  });

  it("keeps every citation marker in its claim, in published order", () => {
    expect(claims(blocks).map((c) => c.citations)).toEqual([[1], [1], [2, 3], [3, 2]]);
    const rendered = claims(blocks).flatMap((c) => c.inline.filter((n) => n.type === "citation")).length;
    expect(rendered).toBe(markers(fixture.answer));
  });

  it("keeps the unknown scope qualifiers and conditions of each claim", () => {
    for (const c of claims(blocks)) {
      expect(c.scope.version).toBe("未确定，仅描述引用快照");
      expect(c.scope.validAt).toBeNull();
      expect(Array.isArray(c.scope.conditions) && c.scope.conditions.length).toBeTruthy();
      expect(c.text).not.toContain("适用版本");
    }
  });

  it("a citation's statement is only the claims that cite it, never the whole report", () => {
    const s1 = statementsFor(blocks!, 1);
    expect(s1).toHaveLength(2);
    expect(s1.every((t) => !t.includes("范围说明") && !t.includes("网络断线"))).toBe(true);
    expect(statementsFor(blocks!, 2)).toHaveLength(2);
    // Before: the Markdown path joined all claims into one statement, so [来源1] "cited" unrelated claims.
    expect(statementsFor(parseMarkdown(fixture.answer, fixture.citationContract, fixture.citationCount), 1)[0]).toContain("网络断线后继续研究");
  });

  it("keeps the scope note as text", () => {
    expect(blocks!.some((b) => b.type === "aside" && b.text.startsWith("范围说明："))).toBe(true);
  });
});

describe("publication adapter: gaps, disputes and strict fallback", () => {
  const head = "研究报告（部分完成）\n\n";
  const tail = "\n\n范围说明：上述裁决绑定所列原文、版本与条件；结构和回执校验不保证模型语义判断正确。";

  it("parses gaps, dispute resolution, known time and unfinished goals", () => {
    const text = head + "仍有争议：\n效果：A 优于 B。（适用版本：2.0；有效时间：2025-01；条件：[\"同硬件\"]） [来源1]；缺口：[\"缺少对照\"]；争议解决依据：Scope clarification: 仅限同硬件\n\n未完成目标：\n测量延迟：缺少实测\n" + tail;
    const b = parse(text)!;
    const c = claims(b)[0];
    expect(c.status).toBe("contested");
    expect(c.scope).toEqual({ version: "2.0", validAt: "2025-01", conditions: ["同硬件"] });
    expect(c.gaps).toEqual(["缺少对照"]);
    expect(c.resolution).toBe("Scope clarification: 仅限同硬件");
    expect(b.some((x) => x.type === "list" && x.items[0].text === "测量延迟：缺少实测")).toBe(true);
  });

  it("falls back (null) for Markdown, unknown lines, ambiguous scope or lost markers", () => {
    expect(parse("## 结论\n\n正文 [来源1]")).toBeNull();
    expect(parse(fixture.answer.replace("已支持：\n", "已支持：\n这一行不是已发布的主张格式\n"))).toBeNull();
    expect(parse(head + "已支持：\n甲（适用版本：未确定；缺口：x；有效时间未确定） [来源1]" + tail)).toBeNull();
    expect(parse(head + "已支持：\n甲（适用版本：未确定；有效时间未确定）\n" + "多余 [来源1]" + tail)).toBeNull();
  });
});

describe("publication adapter (native multiline teaching report)", () => {
  const multi = JSON.parse(readFileSync(join(fileURLToPath(new URL(".", import.meta.url)), "../api/__fixtures__/publication/native-multiline.json"), "utf8")) as
    { answer: string; citationContract: string; citations: string[]; claims: unknown };
  const ctx = { sourceUrls: multi.citations, structuredClaims: multi.claims };
  const blocks = parsePublication(multi.answer, multi.citationContract, multi.citations.length, ctx);
  const cs = claims(blocks);
  const visible = (c: ClaimBlock) => c.parts.flatMap((p) => p.kind === "code" ? [p.code]
    : p.kind === "source" && p.matched ? [] : p.inline.map((n) => n.type === "text" ? n.text : n.type === "link" ? n.label : "")).join("\n");

  it("recognises both multi-line claims with their own subjects as headings", () => {
    expect(blocks).not.toBeNull();
    expect(cs).toHaveLength(2);
    expect(blocks!.flatMap((b) => b.type === "heading" && b.level === 3 ? [b.text] : []))
      .toEqual(["Spring Boot 官方文档中 Bean 的概念", "Spring Boot 官方文档中构造器依赖注入的含义与工作方式"]);
  });

  it("layers each claim into explanation, analogy, code and exercise without dropping text", () => {
    expect(cs[0].parts.map((p) => p.kind === "text" ? p.role : p.kind)).toEqual(["body", "analogy", "code", "exercise", "source"]);
    expect(cs[1].parts.map((p) => p.kind === "text" ? p.role : p.kind)).toEqual(["body", "code", "body", "analogy", "exercise", "source"]);
    const code = cs[0].parts.find((p) => p.kind === "code");
    expect(code).toMatchObject({ lang: "java", untested: true });
    expect(multi.answer).toContain("```java\n" + (code as { code: string }).code + "\n```");   // indentation kept exactly
    // The documentation's own example is not captioned or marked as untested.
    expect(cs[1].parts.find((p) => p.kind === "code")).toMatchObject({ caption: null, untested: false });
    for (const c of cs) for (const p of c.parts) if (p.kind !== "code") expect(multi.answer).toContain(p.original);
  });

  it("keeps each claim's sources and hides only source lines that match the cited source", () => {
    expect(cs.map((c) => c.citations)).toEqual([[1], [1]]);
    expect(cs.every((c) => c.parts.some((p) => p.kind === "source" && p.matched))).toBe(true);
    expect(statementsFor(blocks!, 1)).toHaveLength(2);
    const other = parsePublication(multi.answer, multi.citationContract, multi.citations.length, { ...ctx, sourceUrls: ["https://example.com/other"] });
    const src = claims(other)[0].parts.find((p) => p.kind === "source");
    expect(src).toMatchObject({ matched: false });   // unmatched: stays visible, URL as a compact link
    expect((src as { inline: Array<{ type: string }> }).inline.some((n) => n.type === "link")).toBe(true);
  });

  it("the reading view shows no raw URL and no administrative scope dump", () => {
    for (const c of cs) {
      const v = visible(c);
      expect(v).not.toMatch(/https?:\/\//);
      expect(v).not.toContain("适用版本");
      expect(v).not.toContain("仅为帮助理解的举例");
      expect(c.scope.version).toBe("未确定，仅描述引用快照");   // still available in details
    }
  });

  it("falls back to Markdown when multi-line boundaries cannot be validated", () => {
    expect(parsePublication(multi.answer, multi.citationContract, multi.citations.length, { sourceUrls: multi.citations })).toBeNull();
    expect(parsePublication(multi.answer.replace("```java\n@Service", "```java\n@Service\n```\n```"), multi.citationContract, multi.citations.length, ctx)).toBeNull();
  });
});
