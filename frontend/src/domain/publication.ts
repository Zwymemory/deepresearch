// Strict presentation adapter for the evidence publisher's plain-text report
// (EvidenceService: "研究报告（…）", status groups, one line per claim with a scope
// parenthetical, [来源N] markers, optional gaps / dispute resolution, unfinished goals,
// scope note). It only re-lays out the published text: no paraphrase, no new facts, every
// citation marker kept in place. Anything that does not match exactly returns null so the
// caller falls back to the unchanged Markdown rendering.
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
export interface ClaimBlock {
  type: "claim";
  id: string;
  status: ClaimStatus;
  /** Short label taken verbatim from the claim text ("标签：正文"), or null. */
  label: string | null;
  /** Rendered body: the claim text without its "标签：" prefix (the label is the heading), plus its citations. */
  inline: Inline[];
  /** Full published claim text (used for the citation's statement). */
  text: string;
  citations: number[];
  scope: ClaimScope;
  /** Parsed list when JSON of strings; otherwise raw text; null when none. */
  gaps: string[] | string | null;
  resolution: string | null;
}

const CLAIM_LINE = /^(?<body>.+?)（适用版本：(?<scope>.*)）(?<cites>(?: \[来源\d+\])*)(?<gaps>；缺口：.*?)?(?<resolution>；争议解决依据：.*)?$/;
const MARKER = /\[(?:来源|source)\s*\d+\]/gi;

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

/** Returns structured blocks for an exactly recognised publication, otherwise null. */
export function parsePublication(source: string, contract: string | null | undefined, count: number): Block[] | null {
  const text = String(source ?? "").replace(/\r\n?/g, "\n");
  const lines = text.split("\n");
  const first = lines.findIndex((l) => l.trim());
  if (first < 0 || !/^研究报告（(?:已完成|部分完成|证据不足)）$/.test(lines[first].trim())) return null;

  const blocks: Block[] = [];
  const note = (t: string) => { const inline = parseInline(t, contract, count); blocks.push({ type: "paragraph", inline, text: inlineText(inline), citations: [] }); };
  let group: ClaimStatus | null = null;
  let mode: "claims" | "unfinished" | "after" | null = null;
  let headings = 0, claims = 0, parsedMarkers = 0;
  let unfinished: Extract<Block, { type: "list" }> | null = null;

  for (const raw of lines.slice(first + 1)) {
    const line = raw.trim();
    if (!line) continue;
    if (GROUPS[line]) {
      group = GROUPS[line].status; mode = "claims";
      blocks.push({ type: "heading", level: 2, id: `section-${++headings}`, inline: [{ type: "text", text: GROUPS[line].heading }], text: GROUPS[line].heading });
      continue;
    }
    if (line === "未完成目标：") {
      mode = "unfinished"; group = null;
      blocks.push({ type: "heading", level: 2, id: `section-${++headings}`, inline: [{ type: "text", text: "未完成的目标" }], text: "未完成的目标" });
      unfinished = { type: "list", ordered: false, start: 1, items: [] };
      blocks.push(unfinished);
      continue;
    }
    if (line.startsWith("范围说明：") || line.startsWith("尚无完成核查的主张")) { mode = "after"; group = null; note(line); continue; }
    if (mode === "claims" && group) {
      const m = CLAIM_LINE.exec(line);
      if (!m?.groups) return null;
      const scope = parseScope(m.groups.scope);
      // Ambiguity guard: the scope must not have swallowed citations, gaps or a resolution.
      if (!scope || /\[来源\d+\]|；缺口：|；争议解决依据：/.test(m.groups.scope)) return null;
      const body = m.groups.body;
      const cites = m.groups.cites ?? "";
      const label = claimLabel(body);
      const shown = label ? body.slice(label.length + 1) : body;
      const inline = parseInline(shown + cites, contract, count);
      const citations: number[] = [];
      inline.forEach((n) => { if (n.type === "citation" && n.number && !citations.includes(n.number)) citations.push(n.number); });
      parsedMarkers += (cites.match(MARKER) ?? []).length + (shown.match(MARKER) ?? []).length;
      const id = `claim-${++claims}`;
      // Each claim gets a heading (its own label or a number) so the section guide can reach it.
      blocks.push({ type: "heading", level: 3, id, inline: [{ type: "text", text: label ?? `结论 ${claims}` }], text: label ?? `结论 ${claims}` });
      blocks.push({
        type: "claim", id: `${id}-body`, status: group, label, inline, text: inlineText(parseInline(body, contract, count)), citations, scope,
        gaps: m.groups.gaps ? jsonStrings(m.groups.gaps.slice("；缺口：".length)) : null,
        resolution: m.groups.resolution ? m.groups.resolution.slice("；争议解决依据：".length) : null,
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
  // Every citation marker in the published text must still be present after re-layout.
  if (!claims || parsedMarkers !== (text.match(MARKER) ?? []).length) return null;
  return blocks;
}
