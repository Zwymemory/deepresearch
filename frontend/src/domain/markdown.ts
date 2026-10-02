// A deliberately small Markdown subset, parsed into a typed tree and rendered by
// React as text nodes (never innerHTML). Rules follow the V1 renderer.
import { isVerifiedMarker } from "./citations";

export type Inline =
  | { type: "text"; text: string }
  | { type: "strong"; children: Inline[] }
  | { type: "code"; text: string }
  | { type: "citation"; label: string; number: number | null; reason: string | null };

export type Block =
  | { type: "heading"; level: 2 | 3 | 4 | 5; id: string; inline: Inline[]; text: string }
  | { type: "paragraph"; inline: Inline[]; text: string; citations: number[] }
  | { type: "list"; ordered: boolean; start: number; items: { inline: Inline[]; text: string; citations: number[] }[] }
  | { type: "quote"; inline: Inline[]; text: string; citations: number[] }
  | { type: "code"; text: string }
  | { type: "hr" };

const INLINE_PATTERN = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[(?:来源|source)\s*\d+\]|[（(][^()（）\n]{1,80}\s+来源[）)])/gi;

export function parseInline(source: string, contract: string | null | undefined, count: number): Inline[] {
  const out: Inline[] = [];
  const text = String(source ?? "");
  let cursor = 0;
  for (const match of text.matchAll(INLINE_PATTERN)) {
    const index = match.index ?? 0;
    if (index > cursor) out.push({ type: "text", text: text.slice(cursor, index) });
    const token = match[0];
    if (token.startsWith("**")) {
      out.push({ type: "strong", children: parseInline(token.slice(2, -2), contract, count) });
    } else if (token.startsWith("`")) {
      out.push({ type: "code", text: token.slice(1, -1) });
    } else {
      const marker = /^\[(?:来源|source)\s*(\d+)\]$/i.exec(token);
      const number = isVerifiedMarker(token, contract, count);
      out.push({
        type: "citation", label: token, number,
        reason: number ? null : marker
          ? "编号没有与结构化引用列表建立映射，不能视为已验证引用"
          : "旧格式来源名称未建立唯一编号映射，不能自动猜测 URL",
      });
    }
    cursor = index + token.length;
  }
  if (cursor < text.length) out.push({ type: "text", text: text.slice(cursor) });
  return out;
}

export function inlineText(nodes: Inline[]): string {
  return nodes.map((node) => node.type === "strong" ? inlineText(node.children)
    : node.type === "citation" ? "" : node.text).join("").replace(/\s+/g, " ").trim();
}

function citationNumbers(nodes: Inline[]): number[] {
  const found: number[] = [];
  const walk = (list: Inline[]) => list.forEach((node) => {
    if (node.type === "citation" && node.number && !found.includes(node.number)) found.push(node.number);
    if (node.type === "strong") walk(node.children);
  });
  walk(nodes);
  return found;
}

export function parseMarkdown(source: string, contract: string | null | undefined, count: number): Block[] {
  const blocks: Block[] = [];
  const lines = String(source ?? "").replace(/\r\n?/g, "\n").split("\n");
  let paragraph: string[] = [];
  let list: Extract<Block, { type: "list" }> | null = null;
  let code: string[] | null = null;
  let headingCount = 0;

  const flush = () => {
    if (!paragraph.length) return;
    const inline = parseInline(paragraph.join(" "), contract, count);
    blocks.push({ type: "paragraph", inline, text: inlineText(inline), citations: citationNumbers(inline) });
    paragraph = [];
  };
  const closeList = () => { list = null; };

  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      if (code) { blocks.push({ type: "code", text: code.join("\n") }); code = null; }
      else { flush(); closeList(); code = []; }
      continue;
    }
    if (code) { code.push(line); continue; }
    const heading = /^\s*(#{1,6})\s+(.+?)\s*$/.exec(line);
    if (heading) {
      flush(); closeList();
      const inline = parseInline(heading[2], contract, count);
      const level = Math.min(5, Math.max(2, heading[1].length)) as 2 | 3 | 4 | 5;
      blocks.push({ type: "heading", level, id: `section-${++headingCount}`, inline, text: inlineText(inline) });
      continue;
    }
    const ordered = /^\s*(\d+)\.\s+(.+)$/.exec(line);
    const unordered = /^\s*[-+*]\s+(.+)$/.exec(line);
    if (ordered || unordered) {
      flush();
      const isOrdered = !!ordered;
      const current = list as Extract<Block, { type: "list" }> | null;
      if (!current || current.ordered !== isOrdered) {
        list = { type: "list", ordered: isOrdered, start: ordered ? Number(ordered[1]) : 1, items: [] };
        blocks.push(list);
      }
      const inline = parseInline(ordered ? ordered[2] : unordered![1], contract, count);
      (list as Extract<Block, { type: "list" }>).items.push({ inline, text: inlineText(inline), citations: citationNumbers(inline) });
      continue;
    }
    const quote = /^\s*>\s?(.*)$/.exec(line);
    if (quote) {
      flush(); closeList();
      const inline = parseInline(quote[1], contract, count);
      blocks.push({ type: "quote", inline, text: inlineText(inline), citations: citationNumbers(inline) });
      continue;
    }
    if (/^\s*(?:---+|___+|\*\*\*+)\s*$/.test(line)) { flush(); closeList(); blocks.push({ type: "hr" }); continue; }
    if (!line.trim()) { flush(); closeList(); continue; }
    closeList();
    paragraph.push(line.trim());
  }
  if (code) blocks.push({ type: "code", text: (code as string[]).join("\n") });
  flush();
  return blocks;
}

/** Plain text of the block that contains citation N (the statement it supports). */
export function statementsFor(blocks: Block[], number: number): string[] {
  const out: string[] = [];
  for (const block of blocks) {
    if ((block.type === "paragraph" || block.type === "quote") && block.citations.includes(number)) out.push(block.text);
    if (block.type === "list") block.items.forEach((item) => { if (item.citations.includes(number)) out.push(item.text); });
  }
  return out;
}
