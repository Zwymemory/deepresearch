// Strict presentation adapter for the evidence publisher's plain-text report
// (EvidenceService: "研究报告（…）", status groups, each claim's text — one or more lines,
// possibly with blank lines and fenced code — followed by its scope parenthetical, [来源N]
// markers, optional gaps / dispute resolution; then unfinished goals and the scope note).
// It only re-lays out the published text: no paraphrase, no new facts, every citation marker
// kept. Administrative metadata moves into per-claim details; substantive limits stay visible.
// Anything that does not match exactly returns null so the caller renders the unchanged Markdown.
import { inlineText, parseInline, type Block, type Inline } from "./markdown";

export type ClaimStatus = "supported" | "refuted" | "contested" | "insufficient";
const GROUPS: Record<string, { status: ClaimStatus; heading: string }> = {
  "已支持：": { status: "supported", heading: "已支持的结论" },
  "被反驳的主张：": { status: "refuted", heading: "被反驳的主张" },
  "仍有争议：": { status: "contested", heading: "仍有争议的结论" },
  "证据不足：": { status: "insufficient", heading: "证据不足的结论" },
};

export interface ClaimScope {
  /** Exactly as published, e.g. "未确定，仅描述引用快照". */
  version: string;
  /** null when the publisher wrote "有效时间未确定". */
  validAt: string | null;
  /** Parsed list when the published JSON is a list of strings; otherwise the raw published text. */
  conditions: string[] | string | null;
}

/** A piece of one claim's text, in published order. `original` is the exact published wording. */
export type ClaimPart =
  | { kind: "text"; role: "body" | "analogy" | "exercise"; label: string | null; tag: string | null; inline: Inline[]; original: string }
  | { kind: "code"; lang: string; code: string; caption: string | null; untested: boolean }
  /** A "来源：…" line. Hidden from the reading view only when every URL in it is one of this claim's cited sources. */
  | { kind: "source"; inline: Inline[]; original: string; matched: boolean };

export interface ClaimBlock {
  type: "claim";
  id: string;
  status: ClaimStatus;
  /** Short label taken verbatim from the claim text ("标签：正文"), or null. */
  label: string | null;
  /** Rendered body of a single-line claim (label prefix removed), plus its citations. */
  inline: Inline[];
  /** Ordered parts of the claim for display (one body part for single-line claims). */
  parts: ClaimPart[];
  /** Full published claim text (used for the citation's statement). */
  text: string;
  citations: number[];
  /** Citation markers rendered after a multi-part claim. */
  citationInline: Inline[];
  scope: ClaimScope;
  /** Parsed list when JSON of strings; otherwise raw text; null when none. */
  gaps: string[] | string | null;
  resolution: string | null;
}

/** Optional context for the adapter; used for presentation only. */
export interface PublicationContext {
  /** Report citation number - 1 → canonical source URL (from the structured citations). */
  sourceUrls?: Array<string | null>;
  /** finalResponse.claims, used only for a heading from the claim's own subject when the texts are identical. */
  structuredClaims?: unknown;
}

const SCOPE_TAIL = /（适用版本：(?<scope>.*)）(?<cites>(?: \[来源\d+\])*)(?<gaps>；缺口：.*?)?(?<resolution>；争议解决依据：.*)?$/;
const MARKER = /\[(?:来源|source)\s*\d+\]/gi;
const URL_RE = /https?:\/\/[^\s）)」"'<>，。；]+/g;

function jsonStrings(raw: string): string[] | string {
  try {
    const value = JSON.parse(raw) as unknown;
    if (Array.isArray(value) && value.every((v) => typeof v === "string")) return value as string[];
  } catch { /* keep the published text */ }
  return raw;
}

function parseScope(raw: string): ClaimScope | null {
  // "<version>[；有效时间：<t> | ；有效时间未确定][；条件：<json>]"
  const m = /^(?<version>.*?)(?:；有效时间：(?<time>.*?)|；(?<unknown>有效时间未确定))(?:；条件：(?<conditions>.*))?$/.exec(raw);
  if (!m?.groups) return null;
  const { version, time, unknown, conditions } = m.groups;
  if (!version || (!time && !unknown)) return null;
  return { version, validAt: time ?? null, conditions: conditions ? jsonStrings(conditions) : null };
}

/** "标签：正文" → label when the prefix is short and plain; the full text stays the claim text. */
function claimLabel(body: string): string | null {
  const m = /^([^：。；，,.;\n]{2,24})：/.exec(body);
  return m && !/\[(?:来源|source)\s*\d+\]/i.test(m[1]) ? m[1] : null;
}

const normalizeUrl = (u: string) => u.replace(/[)）。．.,，;；]+$/, "").replace(/\/$/, "");

/** Bare URLs in prose become compact link nodes (text inside code is never touched). */
function withLinks(nodes: Inline[]): Inline[] {
  const out: Inline[] = [];
  for (const node of nodes) {
    if (node.type !== "text") { out.push(node.type === "strong" ? { ...node, children: withLinks(node.children) } : node); continue; }
    let cursor = 0;
    for (const m of node.text.matchAll(URL_RE)) {
      const at = m.index ?? 0;
      if (at > cursor) out.push({ type: "text", text: node.text.slice(cursor, at) });
      let label = "链接";
      try { label = new URL(m[0]).host; } catch { /* keep the generic label */ }
      out.push({ type: "link", href: m[0], label });
      cursor = at + m[0].length;
    }
    if (cursor < node.text.length) out.push({ type: "text", text: node.text.slice(cursor) });
  }
  return out;
}

/** Splits a multi-line claim into display parts. Returns null when a code fence is malformed. */
function claimParts(body: string, cited: Set<string>, contract: string | null | undefined, count: number): ClaimPart[] | null {
  const lines = body.split("\n");
  const chunks: Array<{ code: true; lang: string; text: string } | { code: false; text: string }> = [];
  let para: string[] = [];
  const flush = () => { if (para.length) { chunks.push({ code: false, text: para.join("\n") }); para = []; } };
  for (let i = 0; i < lines.length; i++) {
    const fence = /^\s*```\s*([\w+#.-]*)\s*$/.exec(lines[i]);
    if (fence) {
      flush();
      const end = lines.findIndex((l, j) => j > i && /^\s*```\s*$/.test(l));
      if (end < 0) return null;
      chunks.push({ code: true, lang: fence[1] || "", text: lines.slice(i + 1, end).join("\n") });
      i = end;
      continue;
    }
    if (!lines[i].trim()) { flush(); continue; }
    para.push(lines[i].trim());
  }
  flush();

  const parts: ClaimPart[] = [];
  for (const chunk of chunks) {
    if (chunk.code) {
      // A short "…代码示例（…）：" paragraph right before the code becomes its caption.
      const prev = parts[parts.length - 1];
      let caption: string | null = null;
      if (prev?.kind === "text" && prev.role === "body" && /^[^。；\n]{0,24}代码示例[^。\n]{0,30}[：:]$/.test(prev.original)) {
        caption = prev.original;
        parts.pop();
      }
      parts.push({ kind: "code", lang: chunk.lang, code: chunk.text, caption, untested: !!caption && /未(?:实际)?运行|示意/.test(caption) });
      continue;
    }
    const text = chunk.text;
    if (/^来源[：:]/.test(text)) {
      const urls = [...text.matchAll(URL_RE)].map((m) => normalizeUrl(m[0]));
      parts.push({ kind: "source", original: text, matched: urls.length > 0 && urls.every((u) => cited.has(u)), inline: withLinks(parseInline(text, contract, count)) });
      continue;
    }
    const analogy = /^生活类比(（[^）]*）)?[：:]/.exec(text);
    if (analogy) {
      parts.push({ kind: "text", role: "analogy", label: "生活类比", tag: "帮助理解", original: text, inline: withLinks(parseInline(text.slice(analogy[0].length), contract, count)) });
      continue;
    }
    const exercise = /^练习[：:]/.exec(text);
    if (exercise) {
      parts.push({ kind: "text", role: "exercise", label: "练习", tag: null, original: text, inline: withLinks(parseInline(text.slice(exercise[0].length), contract, count)) });
      continue;
    }
    parts.push({ kind: "text", role: "body", label: null, tag: null, original: text, inline: withLinks(parseInline(text, contract, count)) });
  }
  return parts;
}

function structuredMatch(structured: unknown, claimText: string): boolean {
  return Array.isArray(structured) && structured.some((item) => (item as { claim?: { text?: unknown } })?.claim?.text === claimText);
}

/** Heading from the matching structured claim's own subject, only when the texts are identical. */
function subjectFor(structured: unknown, claimText: string): string | null {
  if (!Array.isArray(structured)) return null;
  for (const item of structured) {
    const claim = (item as { claim?: { text?: unknown; applicability?: { subject?: unknown } } })?.claim;
    if (claim && claim.text === claimText && typeof claim.applicability?.subject === "string" && claim.applicability.subject.trim()) {
      return claim.applicability.subject.trim();
    }
  }
  return null;
}

/** Returns structured blocks for an exactly recognised publication, otherwise null. */
export function parsePublication(source: string, contract: string | null | undefined, count: number, context: PublicationContext = {}): Block[] | null {
  const text = String(source ?? "").replace(/\r\n?/g, "\n");
  const lines = text.split("\n");
  const first = lines.findIndex((l) => l.trim());
  if (first < 0 || !/^研究报告（(?:已完成|部分完成|证据不足)）$/.test(lines[first].trim())) return null;

  const blocks: Block[] = [];
  let group: ClaimStatus | null = null;
  let mode: "claims" | "unfinished" | "after" | null = null;
  let headings = 0, claims = 0, parsedMarkers = 0;
  let unfinished: Extract<Block, { type: "list" }> | null = null;
  let pending: string[] = [];
  let inFence = false;
  const hasPending = () => pending.some((l) => l.trim());

  for (const raw of lines.slice(first + 1)) {
    const line = raw.trim();
    if (mode === "claims" && group) {
      // Inside a claim: collect lines (blank lines and fenced code included) until its scope tail.
      if (inFence) { pending.push(raw); if (/^\s*```\s*$/.test(raw)) inFence = false; continue; }
      if (/^\s*```/.test(raw)) { pending.push(raw); inFence = true; continue; }
    }
    if (!line) { if (mode === "claims" && hasPending()) pending.push(raw); continue; }
    if (GROUPS[line]) {
      if (hasPending()) return null;
      group = GROUPS[line].status; mode = "claims"; pending = [];
      blocks.push({ type: "heading", level: 2, id: `section-${++headings}`, inline: [{ type: "text", text: GROUPS[line].heading }], text: GROUPS[line].heading });
      continue;
    }
    if (line === "未完成目标：") {
      if (hasPending()) return null;
      mode = "unfinished"; group = null;
      blocks.push({ type: "heading", level: 2, id: `section-${++headings}`, inline: [{ type: "text", text: "未完成的目标" }], text: "未完成的目标" });
      unfinished = { type: "list", ordered: false, start: 1, items: [] };
      blocks.push(unfinished);
      continue;
    }
    if (line.startsWith("范围说明：") || line.startsWith("尚无完成核查的主张")) {
      if (hasPending()) return null;
      mode = "after"; group = null;
      const inline = parseInline(line, contract, count);
      // The generic scope statement goes behind a short disclosure; "no checked claims" stays visible.
      blocks.push(line.startsWith("范围说明：")
        ? { type: "aside", title: "核查范围说明", text: line }
        : { type: "paragraph", inline, text: inlineText(inline), citations: [] });
      continue;
    }
    if (mode === "claims" && group) {
      const tail = SCOPE_TAIL.exec(line);
      if (!tail?.groups) { pending.push(raw); continue; }
      const scope = parseScope(tail.groups.scope);
      // Ambiguity guard: the scope must not have swallowed citations, gaps or a resolution.
      if (!scope || /\[来源\d+\]|；缺口：|；争议解决依据：/.test(tail.groups.scope)) return null;
      const body = [...pending, line.slice(0, tail.index)].join("\n").replace(/^\n+/, "").replace(/\s+$/, "");
      pending = [];
      if (!body.trim()) return null;
      const cites = tail.groups.cites ?? "";
      const citationInline = parseInline(cites.trim(), contract, count).filter((n) => n.type === "citation");
      const citations: number[] = [];
      citationInline.forEach((n) => { if (n.type === "citation" && n.number && !citations.includes(n.number)) citations.push(n.number); });
      parsedMarkers += (cites.match(MARKER) ?? []).length + (body.match(MARKER) ?? []).length;
      const cited = new Set(citations.map((n) => context.sourceUrls?.[n - 1]).filter((u): u is string => !!u).map(normalizeUrl));
      const multiline = body.includes("\n");
      // Multi-line claim boundaries are only trusted when the whole text is exactly one structured claim.
      if (multiline && !structuredMatch(context.structuredClaims, body)) return null;
      const label = multiline ? null : claimLabel(body);
      const shown = label ? body.slice(label.length + 1) : body;
      const parts = multiline ? claimParts(body, cited, contract, count)
        : [{ kind: "text" as const, role: "body" as const, label: null, tag: null, original: shown, inline: withLinks(parseInline(shown, contract, count)) }];
      if (!parts) return null;
      const id = `claim-${++claims}`;
      const heading = label ?? subjectFor(context.structuredClaims, body) ?? `结论 ${claims}`;
      blocks.push({ type: "heading", level: 3, id, inline: [{ type: "text", text: heading }], text: heading });
      blocks.push({
        type: "claim", id: `${id}-body`, status: group, label, parts, citationInline, citations, scope,
        inline: withLinks(parseInline(shown + cites, contract, count)),
        text: inlineText(parseInline(body, contract, count)),
        gaps: tail.groups.gaps ? jsonStrings(tail.groups.gaps.slice("；缺口：".length)) : null,
        resolution: tail.groups.resolution ? tail.groups.resolution.slice("；争议解决依据：".length) : null,
      });
      continue;
    }
    if (mode === "unfinished" && unfinished) {
      const inline = parseInline(line, contract, count);
      if (inline.some((n) => n.type === "citation")) return null;
      unfinished.items.push({ inline, text: inlineText(inline), citations: [] });
      continue;
    }
    return null;   // any other line: not the known publication format
  }
  if (inFence || hasPending()) return null;
  // Every citation marker in the published text must still be present after re-layout.
  if (!claims || parsedMarkers !== (text.match(MARKER) ?? []).length) return null;
  return blocks;
}
