// Learning notes for an autonomous run's project (memory phase 5). Extractive stage notes from
// original answer excerpts: not evidence, not mastery, not semantic retrieval. Opening the section
// only reads (GET); nothing here starts research or calls a model.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import type { ApiContext } from "../../api/endpoints";
import { deleteLearningTopic, getLearningNotes, getLearningSelection, getLearningSource, learningErrorText, patchLearningNote } from "../../api/learningNotes";
import { safeCitationUrl } from "../../domain/citations";
import { formatNoteTime, noteLength, NOTE_MAX_CHARS, sourceBlocks, type DigestCategory, type LearningDigest, type LearningEntry, type LearningNotesView, type LearningSelection, type LearningTopic } from "../../domain/learningNotes";
import { withLinks } from "../../domain/publication";
import { Markdown, type CiteHandlers } from "../report/Markdown";

export const LEARNING_DISCLAIMER = "依据原回答摘录整理；已讨论不代表已掌握。";
const OPEN_KIND: Record<string, string> = { user_question: "你的追问", research_gap: "研究缺口", other: "待继续" };

/** Plain text with bare URLs shown as short, accessible links (host as label, full URL as title). */
export function LinkedText({ text }: { text: string }) {
  return <>{withLinks([{ type: "text", text }]).map((n, i) => {
    if (n.type !== "link") return n.type === "text" ? n.text : null;
    const url = safeCitationUrl(n.href);
    return url ? <a key={i} className="inline-link" href={url.href} title={n.href} target="_blank" rel="noopener noreferrer">{n.label}</a> : n.href;
  })}</>;
}

// ---- per-run strip: notes frozen into this request ----

export type SelectionRead = { state: "loading" } | { state: "absent" } | { state: "ok"; selection: LearningSelection };

/**
 * Only SELECTED and UNAVAILABLE are shown. SELECTED means relevant content was frozen into the
 * request, not that the model used it; total_entries counts what the topic has archived.
 */
export function LearningSelectionStrip({ read }: { read: SelectionRead }) {
  if (read.state !== "ok") return null;
  const s = read.selection;
  if (s.status === "UNAVAILABLE") return <p className="note summary-row learning-selection" data-status="UNAVAILABLE" role="status">学习笔记已变化，请重新开始追问。</p>;
  if (s.status !== "SELECTED") return null;
  return (
    <p className="note summary-row learning-selection" data-status="SELECTED">
      本次已载入的学习笔记：<strong>{s.title}</strong>{s.totalEntries != null ? `（该主题已记录 ${s.totalEntries} 轮，本次选取相关内容）` : "（本次选取相关内容）"}
      {s.correction ? <>　你的笔记：<span style={{ whiteSpace: "pre-wrap" }}>{s.correction}</span></> : null}
      <span className="block">这次追问提交时附带了其中的相关内容，供理解上下文；回答是否用到它无法确认，它也不是证据。</span>
    </p>
  );
}

// ---- original Q&A ----

const noCite: CiteHandlers = { citations: [], active: null, onCite: () => {} };

export function SourceBody({ runId, question, answer }: { runId: string; question: string; answer: string }) {
  return (
    <div className="learning-source">
      <p className="insp-label">原问题</p>
      <p style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{question || "（未记录）"}</p>
      <p className="insp-label">原回答（完整原文）</p>
      <Markdown blocks={sourceBlocks(answer, `learning-src-${runId}`)} ctx={noCite} />
      <p className="note">原回答中的 [来源N] 只对应当时那份报告的来源，这里不会跳转到本报告的引用。</p>
    </div>
  );
}

function SourceView({ ctx, scope, projectId, runId }: { ctx: ApiContext; scope: string; projectId: string; runId: string }) {
  const q = useQuery({
    queryKey: ["learning-source", scope, projectId, runId], retry: false, staleTime: 60_000, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => getLearningSource(ctx, projectId, runId, signal),
  });
  if (q.isFetching && !q.data) return <p className="note" role="status">正在读取原问答…</p>;
  if (q.error) return <p className="missing" role="alert">{learningErrorText(q.error, "source")}</p>;
  return q.data ? <SourceBody runId={q.data.runId} question={q.data.question} answer={q.data.answer} /> : null;
}

// ---- note editor ----

export type NoteSave = { state: "idle" } | { state: "saving" } | { state: "saved"; revision: number | null } | { state: "failed"; message: string };

export function NoteStatus({ save }: { save: NoteSave }) {
  if (save.state === "saving") return <p className="note" role="status">正在保存，等待服务端确认…</p>;
  if (save.state === "failed") return <p className="note" role="alert" style={{ color: "var(--error-ink)" }}>未保存：{save.message}</p>;
  if (save.state === "saved") return <p className="note" role="status">服务端已保存{save.revision != null ? `（第 ${save.revision} 版）` : ""}；之后的追问会使用新笔记。</p>;
  return null;
}

function NoteEditor({ topic, onSave }: { topic: LearningTopic; onSave: (note: string) => Promise<NoteSave> }) {
  const [draft, setDraft] = useState(topic.correction);
  const [save, setSave] = useState<NoteSave>({ state: "idle" });
  // The saved note arrives in the refreshed list: keep the confirmation, and follow server-side
  // changes only when the user has no unsaved edit.
  const [seen, setSeen] = useState(topic.correction);
  if (topic.correction !== seen) {
    setSeen(topic.correction);
    if (draft === seen) setDraft(topic.correction);
  }
  const id = `learning-note-${topic.topicId}`;
  const length = noteLength(draft);
  const busy = save.state === "saving";
  const submit = async (note: string) => {
    setSave({ state: "saving" });
    setSave(await onSave(note));
  };
  return (
    <div className="grid gap-2">
      <label className="insp-label" htmlFor={id}>我的笔记</label>
      <textarea id={id} className="text-input" rows={3} value={draft} disabled={busy} style={{ resize: "vertical" }}
        onChange={(e) => { setDraft(e.target.value); if (save.state !== "saving") setSave({ state: "idle" }); }}
        placeholder="例如：第二问还没理解，下次先解释这里" />
      <p className="note">{length} / {NOTE_MAX_CHARS}{length > NOTE_MAX_CHARS ? " · 超出上限，无法保存" : ""} · 留空保存即清除。</p>
      <div className="flex flex-wrap gap-2">
        <button type="button" className="btn btn-primary btn-sm" disabled={busy || length > NOTE_MAX_CHARS || draft === topic.correction} onClick={() => void submit(draft)}>
          {busy ? "正在保存…" : "保存笔记"}
        </button>
      </div>
      <NoteStatus save={save} />
    </div>
  );
}

// ---- grouped excerpts (digest) ----

const DIGEST_GROUPS: Array<{ category: DigestCategory; label: string }> = [
  { category: "concept", label: "核心知识" }, { category: "condition", label: "适用条件" }, { category: "practice", label: "练习与代码" },
];

/** The original questions behind one excerpt; full answers are fetched only when one is opened. */
function PointSources({ runIds, entries, ctx, scope, projectId }: { runIds: string[]; entries: LearningEntry[]; ctx: ApiContext; scope: string; projectId: string }) {
  const [open, setOpen] = useState<string | null>(null);
  const rows = runIds.map((id) => entries.find((e) => e.runId === id)).filter((e): e is LearningEntry => !!e);
  if (!rows.length) return null;
  return (
    <details className="note digest-sources">
      <summary style={{ cursor: "pointer", width: "fit-content" }}>来源（{rows.length} 轮）</summary>
      <ul className="learning-history">
        {rows.map((e) => (
          <li key={e.runId}>
            <span style={{ overflowWrap: "anywhere" }}>{e.question || "（未记录问题）"}</span>
            <span className="note">　{formatNoteTime(e.createdAt)}</span>
            {e.sourceOk ? (
              <button type="button" className="link-btn" aria-expanded={open === e.runId} onClick={() => setOpen((c) => (c === e.runId ? null : e.runId))}>
                {open === e.runId ? "收起原问答" : "查看原问答"}
              </button>
            ) : <span className="note">　原问答地址无法确认，未提供查看</span>}
            {open === e.runId ? <SourceView ctx={ctx} scope={scope} projectId={projectId} runId={e.runId} /> : null}
          </li>
        ))}
      </ul>
    </details>
  );
}

export function DigestView({ digest, entries, ctx, scope, projectId }: { digest: LearningDigest; entries: LearningEntry[]; ctx: ApiContext; scope: string; projectId: string }) {
  return (
    <div className="learning-digest">
      <p className="insp-label">已讨论（原回答摘录）</p>
      <p className="note">从原回答中挑选的原文摘录，不是全部历史的总结{digest.mergedCount ? `；已合并 ${digest.mergedCount} 处重复表述` : ""}{digest.omittedCount ? `；另有 ${digest.omittedCount} 条未显示` : ""}。</p>
      {DIGEST_GROUPS.map(({ category, label }) => {
        const points = digest.points.filter((p) => p.category === category);
        return points.length ? (
          <div key={category} className="digest-group">
            <p className="digest-head">{label}</p>
            <ul className="nb-gaps">{points.map((p) => (
              <li key={p.pointId} style={{ overflowWrap: "anywhere" }}>
                <LinkedText text={p.text} />
                <PointSources runIds={p.runIds} entries={entries} ctx={ctx} scope={scope} projectId={projectId} />
              </li>
            ))}</ul>
          </div>
        ) : null;
      })}
    </div>
  );
}

// ---- one topic ----

export function TopicCard({ topic, ctx, scope, projectId, onSave, onDelete }: {
  topic: LearningTopic; ctx: ApiContext; scope: string; projectId: string;
  onSave: (note: string) => Promise<NoteSave>; onDelete: () => Promise<string | null>;
}) {
  const [openSource, setOpenSource] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const count = topic.entryCount ?? topic.entries.length;
  return (
    <section className="learning-topic" aria-label={`学习主题：${topic.title}`}>
      <h4 className="learning-title">{topic.title}</h4>
      <p className="note">已记录 {count} 轮问答{topic.revision != null ? ` · 第 ${topic.revision} 版` : ""}</p>

      {topic.digest ? <DigestView digest={topic.digest} entries={topic.entries} ctx={ctx} scope={scope} projectId={projectId} />
        : topic.discussed.length ? (
        <div>
          <p className="insp-label">已讨论（原回答摘录）</p>
          <ul className="nb-gaps">{topic.discussed.map((d, i) => <li key={i} style={{ overflowWrap: "anywhere" }}><LinkedText text={d.text} /></li>)}</ul>
        </div>
      ) : null}
      {topic.openQuestions.length ? (
        <div>
          <p className="insp-label">待继续</p>
          <ul className="nb-gaps">{topic.openQuestions.map((q, i) => <li key={i} style={{ overflowWrap: "anywhere" }}><span className="chip">{OPEN_KIND[q.kind]}</span> <LinkedText text={q.text} /></li>)}</ul>
        </div>
      ) : null}

      <NoteEditor key={topic.topicId} topic={topic} onSave={onSave} />

      {topic.entries.length ? (
        <details className="note">
          <summary style={{ cursor: "pointer", width: "fit-content" }}>问题记录（{topic.entries.length}{count > topic.entries.length ? `，共 ${count} 轮` : ""}）</summary>
          <ol className="learning-history">
            {topic.entries.map((e) => (
              <li key={e.runId}>
                <span style={{ overflowWrap: "anywhere" }}>{e.question || "（未记录问题）"}</span>
                <span className="note">　{formatNoteTime(e.createdAt)}{e.hasExercise ? " · 练习" : ""}{e.hasCode ? " · 代码" : ""}{e.runStatus && e.runStatus !== "SUCCEEDED" ? ` · ${e.runStatus}` : ""}</span>
                {e.sourceOk ? (
                  <button type="button" className="link-btn" aria-expanded={openSource === e.runId} onClick={() => setOpenSource((c) => (c === e.runId ? null : e.runId))}>
                    {openSource === e.runId ? "收起原问答" : "查看原问答"}
                  </button>
                ) : <span className="note">　原问答地址无法确认，未提供查看</span>}
                {openSource === e.runId ? <SourceView ctx={ctx} scope={scope} projectId={projectId} runId={e.runId} /> : null}
              </li>
            ))}
          </ol>
        </details>
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        {confirming ? (
          <>
            <span className="note">删除这个学习主题？原报告会保留。</span>
            <button type="button" className="btn btn-quiet btn-sm" disabled={deleting} onClick={() => { setDeleting(true); void onDelete().then((err) => { setDeleting(false); setDeleteError(err); if (!err) setConfirming(false); }); }}>
              {deleting ? "正在删除…" : "确认删除"}
            </button>
            <button type="button" className="btn btn-quiet btn-sm" disabled={deleting} onClick={() => setConfirming(false)}>取消</button>
          </>
        ) : <button type="button" className="link-btn" onClick={() => { setDeleteError(null); setConfirming(true); }}>删除这个学习主题…</button>}
      </div>
      {deleteError ? <p className="note" role="alert" style={{ color: "var(--error-ink)" }}>未删除：{deleteError}</p> : null}
    </section>
  );
}

// ---- list body (pure; also used by tests) ----

export type NotesRead = { state: "loading" } | { state: "error"; message: string } | { state: "ok"; view: LearningNotesView };

export function NotesBody({ read, onRefresh, refreshing, renderTopic }: {
  read: NotesRead; onRefresh: () => void; refreshing: boolean; renderTopic: (topic: LearningTopic) => ReactNode;
}) {
  return (
    <div className="summary-body">
      <p className="note">{LEARNING_DISCLAIMER}</p>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="btn btn-quiet btn-sm" onClick={onRefresh} disabled={refreshing}>{refreshing ? "正在刷新…" : "刷新"}</button>
        <span className="note">研究完成后约 2 秒内自动整理；没看到最新一轮时可刷新。</span>
      </div>
      {read.state === "loading" ? <p className="note" role="status">正在读取学习笔记…</p>
        : read.state === "error" ? <p className="missing" role="alert">{read.message}</p>
        : !read.view.items.length ? <p className="note">这个项目还没有整理好的学习笔记。</p>
        : <>
            {read.view.items.map((t) => <div key={t.topicId}>{renderTopic(t)}</div>)}
            <p className="note">只包含最近{read.view.candidateLimit ? ` ${read.view.candidateLimit} ` : "的"}条问答记录，不是完整历史。</p>
          </>}
    </div>
  );
}

// ---- section ----

export function LearningNotesSection({ ctx, scope, runId, projectId }: { ctx: ApiContext; scope: string; runId: string; projectId: string | null }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const listKey = ["learning-notes", scope, projectId ?? ""] as const;

  const selection = useQuery({
    queryKey: ["learning-selection", scope, runId], retry: false, staleTime: 0, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => getLearningSelection(ctx, runId, signal),
  });
  const list = useQuery({
    queryKey: listKey, enabled: open && !!projectId, retry: false, staleTime: 0, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => getLearningNotes(ctx, projectId!, signal),
  });

  const afterChange = (view: LearningNotesView | null) => {
    if (view) queryClient.setQueryData(listKey, view); else void queryClient.invalidateQueries({ queryKey: listKey });
    // A changed note can make frozen selections unavailable: re-read them.
    void queryClient.invalidateQueries({ queryKey: ["learning-selection", scope] });
  };
  const saveNote = (topic: LearningTopic) => async (note: string): Promise<NoteSave> => {
    try {
      const view = await patchLearningNote(ctx, projectId!, topic.topicId, note);
      afterChange(view);
      return { state: "saved", revision: view.items.find((t) => t.topicId === topic.topicId)?.revision ?? null };
    } catch (error) {
      return { state: "failed", message: learningErrorText(error, "save") };
    }
  };
  const removeTopic = (topic: LearningTopic) => async (): Promise<string | null> => {
    try {
      if (!(await deleteLearningTopic(ctx, projectId!, topic.topicId))) return "服务端没有确认删除。";
      queryClient.setQueryData<LearningNotesView>(listKey, (old) => (old ? { ...old, items: old.items.filter((t) => t.topicId !== topic.topicId) } : old));
      afterChange(null);
      return null;
    } catch (error) {
      return learningErrorText(error, "delete");
    }
  };

  const selRead: SelectionRead = selection.data ? { state: "ok", selection: selection.data } : selection.isFetching ? { state: "loading" } : { state: "absent" };
  const read: NotesRead = list.data ? { state: "ok", view: list.data }
    : list.error ? { state: "error", message: learningErrorText(list.error, "list") } : { state: "loading" };

  return (
    <>
      <LearningSelectionStrip read={selRead} />
      {projectId ? (
        <details className="summary-disclosure learning-notes" onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}>
          <summary>学习笔记 · 本项目按主题整理的问答</summary>
          {open ? <NotesBody read={read} refreshing={list.isFetching} onRefresh={() => void list.refetch()}
            renderTopic={(t) => <TopicCard topic={t} ctx={ctx} scope={scope} projectId={projectId} onSave={saveNote(t)} onDelete={removeTopic(t)} />} /> : null}
        </details>
      ) : null}
    </>
  );
}
