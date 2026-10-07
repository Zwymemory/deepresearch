// Learning notes (memory phase 5): contract parsing against the captured native 20-turn fixture,
// same-origin request paths, and the rendered states (empty / error / selected / unavailable,
// note saving, original Q&A, escaped text). Fixture data is synthetic, not research evidence.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { deleteLearningTopic, getLearningNotes, getLearningSelection, getLearningSource, learningErrorText, patchLearningNote } from "../../api/learningNotes";
import { ApiError } from "../../api/http";
import { formatNoteTime, parseLearningNotes, parseLearningSelection, type LearningNotesView } from "../../domain/learningNotes";
import { LearningSelectionStrip, NotesBody, NoteStatus, SourceBody, TopicCard } from "./LearningNotes";

const fixture = JSON.parse(readFileSync(join(fileURLToPath(new URL(".", import.meta.url)), "../../api/__fixtures__/learning/twenty-turn.json"), "utf8")) as
  { view: Record<string, unknown> & { project_id: string; items: Array<Record<string, unknown>> }; selected: Record<string, unknown> };
const PROJECT = fixture.view.project_id;
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;
const html = (el: Parameters<typeof renderToStaticMarkup>[0]) => renderToStaticMarkup(el);

function fakeFetch(respond: (url: string, init: RequestInit) => { status: number; body: unknown }) {
  const calls: Array<{ url: string; method: string; body: string | undefined; auth: string | undefined }> = [];
  const fetch = (async (url: string, init: RequestInit) => {
    calls.push({ url, method: String(init.method), body: init.body as string | undefined, auth: (init.headers as Record<string, string>).Authorization });
    const r = respond(url, init);
    return new Response(r.body === undefined ? "" : JSON.stringify(r.body), { status: r.status });
  }) as unknown as typeof globalThis.fetch;
  return { calls, ctx: { origin: "http://127.0.0.1:5173", token: "t-secret", fetch } };
}

describe("learning notes contract", () => {
  const view = parseLearningNotes(fixture.view, PROJECT);

  it("parses the native list: topics newest first, counts, excerpts and same-origin source paths", () => {
    expect(view.items.map((t) => t.title)).toEqual(["HTTP 缓存 ETag 第5轮", "Spring Boot 构造器注入，给一道练习"]);
    expect(view.items[1].entryCount).toBe(17);
    expect(view.items[1].discussed[0].text).toContain("不代表用户掌握");
    expect(view.items.every((t) => t.entries.every((e) => e.sourceOk))).toBe(true);
    expect(view.candidateLimit).toBe(100);
  });

  it("accepts an empty list and rejects other projects or schema versions", () => {
    expect(parseLearningNotes({ ...fixture.view, items: [] }, PROJECT).items).toEqual([]);
    expect(() => parseLearningNotes(fixture.view, "project-other")).toThrow(/不属于当前项目/);
    expect(() => parseLearningNotes({ ...fixture.view, schema_version: "learning-notes-view/2" }, PROJECT)).toThrow(/版本/);
  });

  it("never trusts a payload source_url: a foreign or mismatched URL disables viewing", () => {
    const raw = clone(fixture.view);
    const entries = raw.items[0].entries as Array<Record<string, unknown>>;
    entries[0].source_url = "https://evil.example/steal";
    entries[1].source_url = `/api/research/projects/${PROJECT}/learning-notes/sources/wf-someone-else`;
    const parsed = parseLearningNotes(raw, PROJECT).items[0].entries;
    expect(parsed.map((e) => e.sourceOk)).toEqual([false, false, true]);
  });

  it("selection: EMPTY, SELECTED (title/total/correction only), UNAVAILABLE clears details", () => {
    expect(parseLearningSelection({ status: "EMPTY", project_id: PROJECT })).toEqual({ status: "EMPTY", projectId: PROJECT });
    const selected = parseLearningSelection(fixture.selected);
    expect(selected).toEqual({ status: "SELECTED", projectId: PROJECT, title: "Spring Boot 构造器注入，给一道练习", totalEntries: 17,
      correction: "为什么无需自己 new 我还没理解；请保留 PaymentGateway 原题。" });
    expect(parseLearningSelection({ ...fixture.selected, status: "UNAVAILABLE" })).toEqual({ status: "UNAVAILABLE", projectId: PROJECT });
    expect(() => parseLearningSelection({ status: "USED" })).toThrow();
  });
});

describe("learning notes requests", () => {
  it("GETs are read-only and go to the page's own API paths built from known ids", async () => {
    const { calls, ctx } = fakeFetch((url) => url.includes("/sources/")
      ? { status: 200, body: { run_id: "wf-710ccefe-749f-48a6-81da-b45f177f38e4", question: "q", answer: "a", answer_sha256: "x", trusted_as_evidence: false } }
      : url.endsWith("/api/research/agents/wf-run/learning-notes") ? { status: 200, body: fixture.selected } : { status: 200, body: fixture.view });
    await getLearningNotes(ctx, PROJECT);
    await getLearningSource(ctx, PROJECT, "wf-710ccefe-749f-48a6-81da-b45f177f38e4");
    await getLearningSelection(ctx, "wf-run");
    expect(calls.map((c) => [c.method, c.url, c.body])).toEqual([
      ["GET", `http://127.0.0.1:5173/api/research/projects/${PROJECT}/learning-notes`, undefined],
      ["GET", `http://127.0.0.1:5173/api/research/projects/${PROJECT}/learning-notes/sources/wf-710ccefe-749f-48a6-81da-b45f177f38e4`, undefined],
      ["GET", "http://127.0.0.1:5173/api/research/agents/wf-run/learning-notes", undefined],
    ]);
    expect(calls.every((c) => c.auth === "Bearer t-secret" && c.url.startsWith("http://127.0.0.1:5173/"))).toBe(true);
  });

  it("a source answer for another run is rejected", async () => {
    const { ctx } = fakeFetch(() => ({ status: 200, body: { run_id: "wf-other", question: "q", answer: "a" } }));
    await expect(getLearningSource(ctx, PROJECT, "wf-expected")).rejects.toThrow(/不属于所选记录/);
  });

  it("PATCH sends only {note} and returns the refreshed list; errors carry the native message", async () => {
    const updated = clone(fixture.view);
    updated.items[1].correction = "第二问还没理解";
    updated.items[1].revision = 3;
    const ok = fakeFetch(() => ({ status: 200, body: updated }));
    const view = await patchLearningNote(ok.ctx, PROJECT, "learn-658fd28c-0589-42df-a535-586b4a3d1228", "第二问还没理解");
    expect(ok.calls[0]).toMatchObject({ method: "PATCH", url: `http://127.0.0.1:5173/api/research/projects/${PROJECT}/learning-notes/learn-658fd28c-0589-42df-a535-586b4a3d1228` });
    expect(JSON.parse(ok.calls[0].body!)).toEqual({ note: "第二问还没理解" });
    expect(view.items[1]).toMatchObject({ correction: "第二问还没理解", revision: 3 });

    const bad = fakeFetch(() => ({ status: 400, body: { message: "note exceeds 2000 characters" } }));
    const error = await patchLearningNote(bad.ctx, PROJECT, "learn-x", "x").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(learningErrorText(error, "save")).toBe("笔记最多 2000 个字符，未保存。 服务端说明：note exceeds 2000 characters");
    expect(learningErrorText(new ApiError("HTTP 500", 500), "save")).toBe("服务端错误（HTTP 500）。");
  });

  it("DELETE succeeds only when the server confirms that topic", async () => {
    const ok = fakeFetch(() => ({ status: 200, body: { topic_id: "learn-a", deleted: true } }));
    expect(await deleteLearningTopic(ok.ctx, PROJECT, "learn-a")).toBe(true);
    expect(ok.calls[0].method).toBe("DELETE");
    const other = fakeFetch(() => ({ status: 200, body: { topic_id: "learn-b", deleted: true } }));
    expect(await deleteLearningTopic(other.ctx, PROJECT, "learn-a")).toBe(false);
  });
});

describe("learning notes rendering", () => {
  const view: LearningNotesView = parseLearningNotes(fixture.view, PROJECT);
  const noop = () => {};
  const topic = (t = view.items[1]) => createElement(TopicCard, { topic: t, ctx: { origin: "", token: "" }, scope: "s", projectId: PROJECT,
    onSave: async () => ({ state: "idle" as const }), onDelete: async () => null });

  it("selection strip: SELECTED shows title, turns and note without technical fields; UNAVAILABLE asks to restart; EMPTY shows nothing", () => {
    const selected = html(createElement(LearningSelectionStrip, { read: { state: "ok", selection: parseLearningSelection(fixture.selected) } }));
    expect(selected).toContain("本次已载入的学习笔记");
    expect(selected).toContain("Spring Boot 构造器注入，给一道练习");
    expect(selected).toContain("该主题已记录 17 轮，本次选取相关内容");
    expect(selected).toContain("PaymentGateway 原题");
    expect(selected).toContain("回答是否用到它无法确认");
    expect(selected).not.toMatch(/USED|已使用|冻结|topic_id|learn-658|source-excerpts|sha256/);
    const unavailable = html(createElement(LearningSelectionStrip, { read: { state: "ok", selection: { status: "UNAVAILABLE", projectId: PROJECT } } }));
    expect(unavailable).toContain("学习笔记已变化，请重新开始追问");
    expect(unavailable).not.toContain("Spring Boot");
    expect(html(createElement(LearningSelectionStrip, { read: { state: "ok", selection: { status: "EMPTY", projectId: PROJECT } } }))).toBe("");
    expect(html(createElement(LearningSelectionStrip, { read: { state: "absent" } }))).toBe("");
  });

  it("list body: disclaimer, empty and error states", () => {
    const empty = html(createElement(NotesBody, { read: { state: "ok", view: { ...view, items: [] } }, onRefresh: noop, refreshing: false, renderTopic: () => null }));
    expect(empty).toContain("依据原回答摘录整理；已讨论不代表已掌握。");
    expect(empty).toContain("还没有整理好的学习笔记");
    expect(empty).toContain("刷新");
    const error = html(createElement(NotesBody, { read: { state: "error", message: learningErrorText(new ApiError("HTTP 404", 404), "list") }, onRefresh: noop, refreshing: false, renderTopic: () => null }));
    expect(error).toContain("该项目不存在或无权访问");
    const ok = html(createElement(NotesBody, { read: { state: "ok", view }, onRefresh: noop, refreshing: false, renderTopic: () => null }));
    expect(ok).toContain("最近 100 条问答记录，不是完整历史");
    expect(ok).not.toMatch(/语义检索|已掌握的|已核验|冻结/);
  });

  it("a topic shows title, turns, discussed excerpts, editable note and question history with 查看原问答", () => {
    const out = html(topic());
    expect(out).toContain("Spring Boot 构造器注入，给一道练习");
    expect(out).toContain("已记录 17 轮问答");
    expect(out).toContain("已讨论（原回答摘录）");
    expect(out).toContain("例如：第二问还没理解，下次先解释这里");
    expect(out).toContain("问题记录（17）");
    expect(out.match(/查看原问答/g)).toHaveLength(17);
    expect(out).not.toContain("/api/research/projects/");
  });

  it("open questions keep their kind; user text and long URLs are escaped and shortened", () => {
    const t = { ...view.items[0], title: "<img src=x onerror=alert(1)>",
      discussed: [{ runId: "wf-1", text: "见 https://docs.example.org/a/very/long/path?q=1 <b>粗</b>" }],
      openQuestions: [{ runId: "wf-1", text: "第二问还没理解", kind: "user_question" as const }, { runId: "wf-1", text: "缺少版本信息", kind: "research_gap" as const }] };
    const out = html(topic(t));
    expect(out).toContain("&lt;img src=x onerror=alert(1)&gt;");
    expect(out).not.toContain("<img");
    expect(out).toContain("&lt;b&gt;粗&lt;/b&gt;");
    expect(out).toContain(">docs.example.org</a>");
    expect(out).toContain("你的追问");
    expect(out).toContain("研究缺口");
    expect(out).not.toContain("证据不足");
  });

  it("note saving: failure never shows success; success shows the server revision", () => {
    const failed = html(createElement(NoteStatus, { save: { state: "failed", message: "服务端错误（HTTP 500）。" } }));
    expect(failed).toContain("未保存：服务端错误（HTTP 500）。");
    expect(failed).not.toContain("已保存");
    expect(html(createElement(NoteStatus, { save: { state: "saved", revision: 3 } }))).toContain("服务端已保存（第 3 版）");
  });

  it("original Q&A: escaped text, plain old [来源N] labels (no jump), accessible short links, prefixed heading ids", () => {
    const out = html(createElement(SourceBody, { runId: "wf-1", question: "<script>alert(1)</script>",
      answer: "## 结论\n第2问：为什么不用自己 new？[来源1] 参见 https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html" }));
    expect(out).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(out).not.toContain("<script>");
    expect(out).toContain('class="cite-unverified"');
    expect(out).not.toContain('class="cite"');
    expect(out).toContain('title="https://docs.spring.io/spring-boot/reference/using/spring-beans-and-dependency-injection.html"');
    expect(out).toContain(">docs.spring.io</a>");
    expect(out).toContain('id="learning-src-wf-1-');
  });

  it("times: explicit-zone timestamps in the viewer's zone (crossing midnight); zone-less marked; unknown verbatim", () => {
    expect(formatNoteTime("2026-10-07T16:30:12.123456Z", "Asia/Shanghai")).toBe("2026-10-08 00:30");
    expect(formatNoteTime("2026-10-07T16:30:12Z", "UTC")).toBe("2026-10-07 16:30");
    expect(formatNoteTime("2026-10-07T23:30:00+08:00", "Asia/Shanghai")).toBe("2026-10-07 23:30");
    expect(formatNoteTime("2026-10-07 16:30:12.886901+0000", "Asia/Shanghai")).toBe("2026-10-08 00:30");
    expect(formatNoteTime("2026-10-07 23:33:02.886901")).toBe("2026-10-07 23:33（未标明时区）");
    expect(formatNoteTime("昨天")).toBe("昨天");
    expect(formatNoteTime("")).toBe("时间未记录");
  });
});
