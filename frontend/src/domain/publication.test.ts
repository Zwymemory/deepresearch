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
    expect(blocks!.some((b) => b.type === "paragraph" && b.text.startsWith("范围说明："))).toBe(true);
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
