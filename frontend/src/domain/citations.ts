// Citation normalization ported rule-for-rule from the V1 page (renderCitations,
// safeCitationUrl, citationPreview). Details are matched only by exact sourceId;
// never by array position, model label or an inferred URL.
import type { CitationDetail } from "./types";

export type SourceKind = "web-snapshot" | "web-original" | "knowledge" | "unknown";

export interface NormalizedCitation {
  /** 1-based number used by `[来源N]` markers and card ids. */
  number: number;
  sourceId: string;
  kind: SourceKind;
  /** True only when the citation contract is INDEXED_V1. */
  indexed: boolean;
  title: string;
  /** Title to show; falls back to an explicit "not recorded" label. */
  displayTitle: string;
  url: URL | null;
  /** host + path, for display only. */
  address: string | null;
  excerpt: string;
  preview: string;
  /** Why title/excerpt/link could not be shown, or null when complete. */
  missingReason: string | null;
}

export const INDEXED_CONTRACT = "INDEXED_V1";

export function safeCitationUrl(value: unknown): URL | null {
  // Control characters are rejected on purpose (V1 rule); the regex is intentional.
  // oxlint-disable-next-line no-control-regex
  if (typeof value !== "string" || /[\u0000- \u007f]/.test(value)) return null;
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && url.hostname && !url.username && !url.password
      ? url : null;
  } catch {
    return null;
  }
}

export function citationPreview(value: string | null | undefined): string {
  const text = String(value || "")
    .replace(/^\s*\[UNTRUSTED_DATA_BEGIN source=knowledge-base\]\s*\n/, "")
    .replace(/\n\s*\[UNTRUSTED_DATA_END source=knowledge-base\]\s*$/, "");
  // Skip short navigation paragraphs in the preview; the full excerpt stays available.
  const paragraph = text.split(/\n\s*\n/).find((part) => {
    const minimum = /[㐀-鿿]/.test(part) ? 40 : 80;
    return part.replace(/\s+/g, " ").trim().length >= minimum;
  }) || text;
  const compact = paragraph.replace(/\s+/g, " ").trim();
  return compact.length > 280 ? compact.slice(0, 280) + "…" : compact;
}

export function kindLabel(kind: SourceKind): string {
  switch (kind) {
    case "web-original": return "网页 · 原文";
    case "web-snapshot": return "网页 · 搜索摘要";
    case "knowledge": return "知识库文档";
    default: return "来源详情未记录";
  }
}

export function normalizeCitations(
  citations: unknown,
  citationContract: string | null | undefined,
  details: CitationDetail[] | null | undefined,
): NormalizedCitation[] {
  const ids = Array.isArray(citations) ? citations : [];
  const indexed = citationContract === INDEXED_CONTRACT;
  const pool = Array.isArray(details) ? details : [];
  return ids.map((raw, index) => {
    const sourceId = String(raw == null ? "" : raw);
    const matches = pool.filter((item) => item && item.sourceId === sourceId);
    // Duplicate metadata for one ID is ambiguous and must not create a link.
    let source: CitationDetail | null = indexed && matches.length === 1 ? matches[0] : null;
    const web = !!source && (source.kind === "WEB_SEARCH_SNAPSHOT" || source.kind === "WEB_ORIGINAL");
    const knowledge = !!source && source.kind === "KNOWLEDGE_CHUNK";
    if (!web && !knowledge) source = null;
    const kind: SourceKind = !source ? "unknown" : knowledge ? "knowledge"
      : source.kind === "WEB_ORIGINAL" ? "web-original" : "web-snapshot";
    const url = web && source ? safeCitationUrl(source.url) : null;
    const title = source && typeof source.title === "string" ? source.title.trim() : "";
    const excerpt = source && typeof source.excerpt === "string" ? source.excerpt : "";
    const displayTitle = title || (url ? url.hostname : knowledge ? "知识库文档（标题未记录）"
      : web ? "网页来源（标题未记录）" : "来源信息不足");
    const missingReason = !source ? "此结果缺少唯一的来源详情，无法确认标题、摘录或网页地址。"
      : web && !url ? "网页地址未记录或无法安全打开。"
      : !excerpt.trim() ? "此来源未保存摘录。" : null;
    return {
      number: index + 1,
      sourceId,
      kind,
      indexed,
      title,
      displayTitle,
      url,
      address: url ? url.host + (url.pathname === "/" ? "" : url.pathname) + url.search + url.hash : null,
      excerpt,
      preview: excerpt.trim() ? citationPreview(excerpt) : "",
      missingReason,
    };
  });
}

/** A `[来源N]` marker is verified only under INDEXED_V1 with an in-range number. */
export function isVerifiedMarker(token: string, contract: string | null | undefined, count: number): number | null {
  if (contract !== INDEXED_CONTRACT) return null;
  const match = /^\[来源(\d+)\]$/.exec(token);
  if (!match) return null;
  const n = Number(match[1]);
  return n >= 1 && n <= count ? n : null;
}
