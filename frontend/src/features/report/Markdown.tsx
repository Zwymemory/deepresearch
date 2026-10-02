import * as Tooltip from "@radix-ui/react-tooltip";
import type { ReactNode } from "react";
import { kindLabel, type NormalizedCitation } from "../../domain/citations";
import type { Block, Inline } from "../../domain/markdown";

export interface CiteHandlers {
  citations: NormalizedCitation[];
  active: number | null;
  onCite: (number: number, trigger: HTMLElement) => void;
}

function CiteButton({ number, label, id, ctx }: { number: number; label: string; id: string; ctx: CiteHandlers }) {
  const citation = ctx.citations[number - 1];
  return (
    <Tooltip.Root delayDuration={250}>
      <Tooltip.Trigger asChild>
        <button type="button" id={id} className="cite" data-cite={number}
          aria-expanded={ctx.active === number} aria-controls="inspector"
          aria-label={`${label}：${citation?.displayTitle ?? "来源"}，打开来源检查`}
          onClick={(event) => ctx.onCite(number, event.currentTarget)}>
          {label}
        </button>
      </Tooltip.Trigger>
      <Tooltip.Portal>
        {/* Preview is an enhancement only; clicking provides the complete interaction. */}
        <Tooltip.Content side="top" sideOffset={8} collisionPadding={12} className="preview" aria-hidden="true">
          <span className={"chip " + (citation?.kind === "knowledge" ? "chip-kb" : citation?.kind === "unknown" ? "chip-warn" : "chip-web")}>{kindLabel(citation?.kind ?? "unknown")}</span>
          <span style={{ display: "block", marginTop: 6, fontWeight: 650 }}>{citation?.displayTitle}</span>
          {citation?.address ? <span className="insp-address" style={{ display: "block" }}>{citation.url?.host}</span> : null}
        </Tooltip.Content>
      </Tooltip.Portal>
    </Tooltip.Root>
  );
}

function renderInline(nodes: Inline[], ctx: CiteHandlers, key: string): ReactNode[] {
  return nodes.map((node, i) => {
    const k = `${key}-${i}`;
    if (node.type === "text") return node.text;
    if (node.type === "code") return <code key={k}>{node.text}</code>;
    if (node.type === "strong") return <strong key={k}>{renderInline(node.children, ctx, k)}</strong>;
    return node.number
      ? <CiteButton key={k} id={`cite-${k}`} number={node.number} label={node.label} ctx={ctx} />
      : <span key={k} className="cite-unverified" title={node.reason ?? undefined}>{node.label}</span>;
  });
}

export function Markdown({ blocks, ctx }: { blocks: Block[]; ctx: CiteHandlers }) {
  const linked = (citations: number[]) => ctx.active != null && citations.includes(ctx.active) ? "true" : undefined;
  return (
    <div className="prose">
      {blocks.map((block, i) => {
        const key = `b${i}`;
        switch (block.type) {
          case "heading": {
            const Tag = (`h${block.level}`) as "h2" | "h3" | "h4" | "h5";
            return <Tag key={key} id={block.id}>{renderInline(block.inline, ctx, key)}</Tag>;
          }
          case "paragraph": return <p key={key} data-linked={linked(block.citations)}>{renderInline(block.inline, ctx, key)}</p>;
          case "quote": return <blockquote key={key} data-linked={linked(block.citations)}>{renderInline(block.inline, ctx, key)}</blockquote>;
          case "code": return <pre key={key}><code>{block.text}</code></pre>;
          case "hr": return <hr key={key} />;
          case "list": {
            const items = block.items.map((item, j) => (
              <li key={`${key}-${j}`} data-linked={linked(item.citations)}>{renderInline(item.inline, ctx, `${key}-${j}`)}</li>
            ));
            return block.ordered ? <ol key={key} start={block.start}>{items}</ol> : <ul key={key}>{items}</ul>;
          }
        }
      })}
    </div>
  );
}
