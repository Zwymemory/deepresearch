// Learning notes (memory phase 5): extractive stage notes grouped by topic, built from original
// answer excerpts. Not evidence, not mastery, not semantic retrieval. Parsers are strict about the
// identifiers they are asked for and never take a URL from the payload to fetch with.
import { parseMarkdown, type Block, type Inline } from "./markdown";
import { ContractError } from "./progressMemory";
import { withLinks } from "./publication";

export const LEARNING_VIEW_SCHEMA = "learning-notes-view/1";
/** PATCH limit in Unicode code points ("" clears). */
export const NOTE_MAX_CHARS = 2000;

export interface LearningEntry {
  runId: string; question: string; hasExercise: boolean; hasCode: boolean;
  runStatus: string; createdAt: string;
  /** true only when the payload's source_url is exactly the expected same-origin API path. */
  sourceOk: boolean;
}
export interface LearningTopic {
  topicId: string; title: string; revision: number | null; correction: string; entryCount: number | null;
  discussed: Array<{ runId: string; text: string }>;
  openQuestions: Array<{ runId: string; text: string; kind: "user_question" | "research_gap" | "other" }>;
  entries: LearningEntry[];
}
export interface LearningNotesView { projectId: string; candidateLimit: number | null; items: LearningTopic[] }
export interface LearningSource { runId: string; question: string; answer: string; answerSha256: string }
export type LearningSelection =
  | { status: "EMPTY"; projectId: string | null }
  | { status: "UNAVAILABLE"; projectId: string | null }
  | { status: "SELECTED"; projectId: string | null; title: string; totalEntries: number | null; correction: string };

type Raw = Record<string, unknown>;
const obj = (v: unknown): Raw => (v && typeof v === "object" && !Array.isArray(v) ? (v as Raw) : {});
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const num = (v: unknown): number | null => (typeof v === "number" && Number.isFinite(v) ? v : null);

export const learningPaths = {
  list: (projectId: string) => `/api/research/projects/${encodeURIComponent(projectId)}/learning-notes`,
  source: (projectId: string, runId: string) => `/api/research/projects/${encodeURIComponent(projectId)}/learning-notes/sources/${encodeURIComponent(runId)}`,
  topic: (projectId: string, topicId: string) => `/api/research/projects/${encodeURIComponent(projectId)}/learning-notes/${encodeURIComponent(topicId)}`,
  selection: (runId: string) => `/api/research/agents/${encodeURIComponent(runId)}/learning-notes`,
};

export function noteLength(note: string): number {
  return [...note].length;
}

export function parseLearningNotes(raw: unknown, expectedProjectId: string): LearningNotesView {
  const v = obj(raw);
  if (v.schema_version !== LEARNING_VIEW_SCHEMA) throw new ContractError(`未知的学习笔记版本（${str(v.schema_version) || "缺失"}）。`);
  if (str(v.project_id) !== expectedProjectId) throw new ContractError("返回的学习笔记不属于当前项目。");
  if (v.trusted_as_evidence === true) throw new ContractError("学习笔记被标记为证据，与约定不符。");
  if (!Array.isArray(v.items)) throw new ContractError("学习笔记缺少 items。");
  const items = v.items.map((rawTopic): LearningTopic => {
    const t = obj(rawTopic);
    const topicId = str(t.topic_id);
    if (!topicId) throw new ContractError("学习主题缺少 topic_id。");
    return {
      topicId, title: str(t.title) || "（未命名主题）", revision: num(t.revision), correction: str(t.correction), entryCount: num(t.entry_count),
      discussed: arr(t.discussed).map(obj).filter((d) => str(d.text)).map((d) => ({ runId: str(d.run_id), text: str(d.text) })),
      openQuestions: arr(t.open_questions).map(obj).filter((q) => str(q.text)).map((q) => ({
        runId: str(q.run_id), text: str(q.text),
        kind: q.kind === "user_question" || q.kind === "research_gap" ? q.kind : "other" as const,
      })),
      entries: arr(t.entries).map(obj).filter((e) => str(e.run_id)).map((e) => {
        const runId = str(e.run_id);
        return {
          runId, question: str(e.question), hasExercise: e.has_exercise === true, hasCode: e.has_code === true,
          runStatus: str(e.run_status), createdAt: str(e.created_at),
          sourceOk: str(e.source_url) === learningPaths.source(expectedProjectId, runId),
        };
      }),
    };
  });
  return { projectId: expectedProjectId, candidateLimit: num(v.candidate_limit), items };
}

export function parseLearningSource(raw: unknown, expectedRunId: string): LearningSource {
  const v = obj(raw);
  if (str(v.run_id) !== expectedRunId) throw new ContractError("返回的原问答不属于所选记录。");
  if (typeof v.answer !== "string") throw new ContractError("原问答缺少 answer。");
  return { runId: expectedRunId, question: str(v.question), answer: v.answer, answerSha256: str(v.answer_sha256) };
}

export function parseLearningSelection(raw: unknown): LearningSelection {
  const v = obj(raw);
  const projectId = str(v.project_id) || null;
  if (v.status === "EMPTY") return { status: "EMPTY", projectId };
  // UNAVAILABLE clears details: nothing from learning_notes is kept.
  if (v.status === "UNAVAILABLE") return { status: "UNAVAILABLE", projectId };
  if (v.status === "SELECTED") {
    const notes = obj(v.learning_notes);
    if (!str(notes.title) && !str(notes.topic_id)) throw new ContractError("SELECTED 缺少学习笔记内容。");
    return { status: "SELECTED", projectId, title: str(notes.title) || "（未命名主题）", totalEntries: num(notes.total_entries), correction: str(notes.correction) };
  }
  throw new ContractError(`未知的学习笔记载入状态（${str(v.status) || "缺失"}）。`);
}

/**
 * A timestamp with an explicit zone (Z or ±hh:mm) is shown in the browser's local time zone,
 * "YYYY-MM-DD HH:mm". A zone-less timestamp is not reinterpreted: it is shown as given with an
 * explicit marker. Anything else is shown verbatim.
 */
export function formatNoteTime(value: string, timeZone?: string): string {
  const zoned = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}(?::\d{2})?)(?:\.(\d+))?(Z|[+-]\d{2}:?\d{2})$/i.exec(value.trim());
  if (zoned) {
    const [, date, time, fraction = "", zone] = zoned;
    const ms = fraction ? "." + fraction.slice(0, 3).padEnd(3, "0") : "";
    const offset = zone.toUpperCase() === "Z" ? "Z" : zone.length === 5 ? zone.slice(0, 3) + ":" + zone.slice(3) : zone;
    const instant = new Date(`${date}T${time.length === 5 ? time + ":00" : time}${ms}${offset}`);
    if (!Number.isNaN(instant.getTime())) {
      const parts = Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
        timeZone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23",
      }).formatToParts(instant).map((p) => [p.type, p.value]));
      return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
    }
  }
  const local = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::\d{2}(?:\.\d+)?)?$/.exec(value.trim());
  if (local) return `${local[1]} ${local[2]}（未标明时区）`;
  return value || "时间未记录";
}

/**
 * An old report's answer for display. Its [来源N] markers stay plain labels (they never link to the
 * current report's sources) and its heading ids are prefixed so they cannot collide with the
 * current report's section anchors.
 */
export function sourceBlocks(answer: string, idPrefix: string): Block[] {
  const link = (inline: Inline[]) => withLinks(inline);
  return parseMarkdown(answer, null, 0).map((b): Block => {
    if (b.type === "heading") return { ...b, id: `${idPrefix}-${b.id}`, inline: link(b.inline) };
    if (b.type === "paragraph" || b.type === "quote") return { ...b, inline: link(b.inline) };
    if (b.type === "list") return { ...b, items: b.items.map((it) => ({ ...it, inline: link(it.inline) })) };
    return b;
  });
}

