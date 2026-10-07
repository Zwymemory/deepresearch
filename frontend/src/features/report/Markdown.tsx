import * as Tooltip from "@radix-ui/react-tooltip";
import type { ReactNode } from "react";
import { kindLabel, type NormalizedCitation } from "../../domain/citations";
import type { Block, Inline } from "../../domain/markdown";
import type { ClaimBlock } from "../../domain/publication";

const STATUS_LABEL: Record<ClaimBlock["status"], { label: string; tone: string }> = {
  supported: { label: "已支持", tone: "ok" }, refuted: { label: "被反驳", tone: "error" },
  contested: { label: "仍有争议", tone: "warn" }, insufficient: { label: "证据不足", tone: "warn" },
};

/** Text as published, for list or raw values. */
const list = (value: string[] | string | null) => value == null ? null
  : Array.isArray(value) ? <ul className="claim-list">{value.map((v, i) => <li key={i}>{v}</li>)}</ul> : <p>{value}</p>;

/**
 * One published claim: its own text and citations, plus the scope it is bound to. Unknown scope
 * qualifiers stay visible in the collapsed summary so folding never makes a claim unconditional.
 */
function ClaimCard({ block, ctx, linked, k }: { block: ClaimBlock; ctx: CiteHandlers; linked: string | undefined; k: string }) {
  const s = block.scope;
  const versionUnknown = s.version.startsWith("未确定");
  const qualifiers = [
    versionUnknown ? "版本未确定" : `版本：${s.version}`,
    s.validAt ? `有效时间：${s.validAt}` : "有效时间未确定",
    ...(s.conditions && (!Array.isArray(s.conditions) || s.conditions.length) ? ["附带条件"] : []),
    ...(block.gaps && (!Array.isArray(block.gaps) || block.gaps.length) ? ["有记录的缺口"] : []),
    ...(block.resolution ? ["有争议解决依据"] : []),
  ];
  const status = STATUS_LABEL[block.status];
  return (
    <section className="claim-card" data-status={block.status} data-linked={linked} aria-labelledby={block.id.replace(/-body$/, "")}>
      {block.status !== "supported" ? <span className="status-tag" data-tone={status.tone}>{status.label}</span> : null}
      <p>{renderInline(block.inline, ctx, k)}</p>
      <details className="claim-scope">
        <summary>适用范围与核查详情 · {qualifiers.join(" · ")}</summary>
        <dl className="facts">
          <dt>适用版本</dt><dd>{s.version}</dd>
          <dt>有效时间</dt><dd>{s.validAt ?? "未确定"}</dd>
          {s.conditions ? <><dt>条件（原文记录）</dt><dd>{list(s.conditions)}</dd></> : null}
          {block.gaps ? <><dt>缺口</dt><dd>{list(block.gaps)}</dd></> : null}
          {block.resolution ? <><dt>争议解决依据</dt><dd>{block.resolution}</dd></> : null}
        </dl>
        <p className="note">裁决只绑定所列原文、版本与条件；核查不保证模型的语义判断正确。</p>
      </details>
    </section>
  );
}

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
          case "claim": return <ClaimCard key={key} k={key} block={block} ctx={ctx} linked={linked(block.citations)} />;
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
