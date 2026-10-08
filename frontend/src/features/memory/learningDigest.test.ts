// Learning-note digest (additive `digest` on learning-notes-view/1): parsing, fallback, source-run
// matching and rendering. Fixture: the native "before" view and answers with a TEST digest whose
// spans are exact UTF-16 spans of those native answers (not backend digest output).
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { parseDigest, parseLearningNotes, type LearningTopic } from "../../domain/learningNotes";
import { DigestView, TopicCard } from "./LearningNotes";

type RawTopic = Record<string, unknown> & { title: string; entries: Array<{ run_id: string; source_url: string }>; digest?: Record<string, unknown> & { points: Array<Record<string, unknown>>; stats: Record<string, unknown> } };
const fixture = JSON.parse(readFileSync(join(fileURLToPath(new URL(".", import.meta.url)), "../../api/__fixtures__/learning/digest-native-before.json"), "utf8")) as
  { view: { project_id: string; items: RawTopic[] }; sources: Array<{ run_id: string; answer: string }> };
const PROJECT = fixture.view.project_id;
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
const beanRaw = () => clone(fixture.view.items.find((t) => t.title === "Bean 和构造器依赖注入")!);
const topicOf = (raw: RawTopic) => parseLearningNotes({ ...fixture.view, items: [raw] }, PROJECT).items[0];
const ctx = { origin: "", token: "" };
const card = (topic: LearningTopic) => renderToStaticMarkup(createElement(TopicCard, { topic, ctx, scope: "s", projectId: PROJECT, onSave: async () => ({ state: "idle" as const }), onDelete: async () => null }));
const digestHtml = (topic: LearningTopic) => renderToStaticMarkup(createElement(DigestView, { digest: topic.digest!, entries: topic.entries, ctx, scope: "s", projectId: PROJECT }));

describe("digest parsing", () => {
  it("test fixture texts are exact spans of the native answers", () => {
    const answers = new Map(fixture.sources.map((s) => [s.run_id, s.answer]));
    for (const p of beanRaw().digest!.points) for (const s of p.sources as Array<{ run_id: string; start: number; end: number }>)
      expect(answers.get(s.run_id)!.slice(s.start, s.end)).toBe(p.text);
  });

  it("a valid digest is parsed with categories, distinct source runs and merge stats", () => {
    const d = topicOf(beanRaw()).digest!;
    expect(d.points.map((p) => [p.category, p.runIds.length])).toEqual([["concept", 1], ["condition", 3], ["practice", 1]]);
    expect(d.mergedCount).toBe(2);
    expect(d.omittedCount).toBe(0);
  });

  it("source runs are counted once each and only when they are entries of the topic", () => {
    const raw = beanRaw();
    const sources = raw.digest!.points[1].sources as unknown[];
    sources.push({ ...(sources[0] as object), start: 0, end: 5 });                       // same run, second span
    sources.push({ run_id: "wf-not-in-this-topic", start: 0, end: 5 });       // unknown run
    sources.push({ run_id: "", start: 0, end: 5 }, { run_id: "wf-x", start: 5, end: 5 }, "junk");
    expect(topicOf(raw).digest!.points[1].runIds).toEqual(sources.slice(0, 3).map((s) => (s as { run_id: string }).run_id));
  });

  it("absent, unknown-version or unknown-method digests fall back; malformed points are dropped", () => {
    const entries = topicOf(beanRaw()).entries;
    expect(parseDigest(undefined, entries)).toBeNull();
    expect(parseDigest({ ...beanRaw().digest, schema_version: "learning-note-digest/2" }, entries)).toBeNull();
    expect(parseDigest({ ...beanRaw().digest, method: "llm-summary/1" }, entries)).toBeNull();
    expect(parseDigest("not an object", entries)).toBeNull();
    const raw = beanRaw();
    raw.digest!.points[0].category = "opinion";
    delete raw.digest!.points[2].representative;
    raw.digest!.points.push({ ...raw.digest!.points[1] });                    // duplicate point_id
    const d = topicOf(raw).digest!;
    expect(d.points.map((p) => p.category)).toEqual(["condition"]);
    raw.digest!.points = raw.digest!.points.map((p) => ({ ...p, text: "  " }));
    expect(topicOf(raw).digest).toBeNull();
  });
});

describe("digest rendering", () => {
  it("groups by 核心知识 / 适用条件 / 练习与代码 with a merge caption and collapsed per-point sources", () => {
    const html = digestHtml(topicOf(beanRaw()));
    const order = ["核心知识", "适用条件", "练习与代码"].map((h) => html.indexOf(h));
    expect(order.every((i) => i > 0) && order[0] < order[1] && order[1] < order[2]).toBe(true);
    expect(html).toContain("从原回答中挑选的原文摘录，不是全部历史的总结；已合并 2 处重复表述");
    expect(html).toContain("来源（3 轮）");
    expect(html.match(/来源（1 轮）/g)).toHaveLength(2);
    expect(html).toContain("<details");
    expect(html).not.toContain("研究报告（已完成）");                         // full answers are not inlined
    expect(html).not.toMatch(/point-fixture|source-grouping|run_id|learn-|wf-|已掌握|语义/);
  });

  it("empty groups and a zero merge count are omitted", () => {
    const raw = beanRaw();
    raw.digest!.points = raw.digest!.points.filter((p) => p.category === "concept");
    raw.digest!.stats.merged_count = 0;
    const html = digestHtml(topicOf(raw));
    expect(html).toContain("核心知识");
    expect(html).not.toMatch(/适用条件|练习与代码|已合并/);
  });

  it("the topic prefers the digest over the flat list and falls back without one", () => {
    const withDigest = card(topicOf(beanRaw()));
    expect(withDigest).toContain("核心知识");
    expect(withDigest.match(/为什么不需要自己 new PaymentGateway/g)?.length).toBe(1);   // not repeated by the flat list
    expect(withDigest).toContain("问题记录（5）");                                        // history kept
    expect(withDigest).toContain("我的笔记");
    const raw = beanRaw();
    delete raw.digest;
    const flat = card(topicOf(raw));
    expect(flat).not.toContain("核心知识");
    expect(flat.match(/为什么不需要自己 new PaymentGateway/g)?.length).toBe(3);         // old flat discussed list
    const broken = beanRaw();
    broken.digest!.schema_version = "learning-note-digest/9";
    expect(card(topicOf(broken))).not.toContain("来源（");
  });

  it("an entry without a confirmed source path offers no 查看原问答 in the point sources", () => {
    const raw = beanRaw();
    raw.entries.forEach((e) => { e.source_url = "https://evil.example/" + e.run_id; });
    const html = digestHtml(topicOf(raw));
    expect(html).toContain("来源（3 轮）");
    expect(html).not.toContain("查看原问答");
    expect(html).toContain("原问答地址无法确认");
  });

  it("excerpt text is escaped and long URLs are shortened", () => {
    const raw = beanRaw();
    raw.digest!.points[0].text = "<img src=x onerror=alert(1)> 见 https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html";
    const html = digestHtml(topicOf(raw));
    expect(html).toContain("&lt;img src=x onerror=alert(1)&gt;");
    expect(html).not.toContain("<img");
    expect(html).toContain(">docs.spring.io</a>");
  });
});
