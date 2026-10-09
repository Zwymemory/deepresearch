import * as Tooltip from "@radix-ui/react-tooltip";
import { useState, type ReactNode } from "react";
import { kindLabel, safeCitationUrl, type NormalizedCitation } from "../../domain/citations";
import type { Block, Inline } from "../../domain/markdown";
import type { ClaimBlock } from "../../domain/publication";

const STATUS_LABEL: Record<ClaimBlock["status"], { label: string; tone: string }> = {
  supported: { label: "已支持", tone: "ok" }, refuted: { label: "被反驳", tone: "error" },
  contested: { label: "仍有争议", tone: "warn" }, insufficient: { label: "证据不足", tone: "warn" },
};

/** Text as published, for list or raw values. */
const list = (value: string[] | string | null) => value == null ? null
  : Array.isArray(value) ? <ul className="claim-list">{value.map((v, i) => <li key={i}>{v}</li>)}</ul> : <p>{value}</p>;
const nonEmpty = (value: string[] | string | null) => value != null && (!Array.isArray(value) || value.length > 0);

/** Code as published: original indentation, horizontal scroll inside the block, optional copy. */
function CodeBlock({ code, lang, untested }: { code: string; lang: string; untested: boolean }) {
  const [copied, setCopied] = useState(false);
  return (
    <figure className="code-block">
      <figcaption className="code-head">
        <span className="code-lang">{lang || "代码"}</span>
        {untested ? <span className="code-tag">示例 · 未运行</span> : null}
        <button type="button" className="code-copy" onClick={() => { void navigator.clipboard?.writeText(code).then(() => setCopied(true)); }}>{copied ? "已复制" : "复制"}</button>
      </figcaption>
      <pre><code>{code}</code></pre>
    </figure>
  );
}

/**
 * One published claim in reading order. Administrative metadata (unknown version/time, request
 * fragments recorded as conditions, the source line already represented by the citation) sits
 * behind "来源与核查详情"; substantive limits — status, gaps, dispute resolution, known version or
 * time — stay visible, so folding never turns a qualified claim into an unconditional one.
 */
function ClaimCard({ block, ctx, linked, k }: { block: ClaimBlock; ctx: CiteHandlers; linked: string | undefined; k: string }) {
  const s = block.scope;
  const status = STATUS_LABEL[block.status];
  const single = block.parts.length === 1 && block.parts[0].kind === "text" && block.parts[0].role === "body";
  const hidden = block.parts.filter((p) => (p.kind === "source" && p.matched) || (p.kind === "text" && p.role === "analogy" && !!p.tag) || (p.kind === "code" && p.caption));
  const versionKnown = !s.version.startsWith("未确定");
  return (
    <section className="claim-card" data-status={block.status} data-linked={linked} aria-labelledby={block.id.replace(/-body$/, "")}>
      {block.status !== "supported" ? <span className="status-tag" data-tone={status.tone}>{status.label}</span> : null}
      {single ? <p>{renderInline(block.inline, ctx, k)}</p> : block.parts.map((part, i) => {
        const pk = `${k}-p${i}`;
        if (part.kind === "code") return <CodeBlock key={pk} code={part.code} lang={part.lang} untested={part.untested} />;
        if (part.kind === "source") return part.matched ? null : <p key={pk} className="claim-source">{renderInline(part.inline, ctx, pk)}</p>;
        if (part.role === "body") return <p key={pk}>{renderInline(part.inline, ctx, pk)}</p>;
        return (
          <div key={pk} className="claim-part" data-role={part.role}>
            <p className="part-label">{part.label}{part.tag ? <span className="part-tag">{part.tag}</span> : null}</p>
            <p>{renderInline(part.inline, ctx, pk)}</p>
          </div>
        );
      })}
      {!single && block.citationInline.length ? <p className="claim-cites">依据 {renderInline(block.citationInline, ctx, `${k}-c`)}</p> : null}
      {versionKnown || s.validAt ? <p className="claim-limit">{versionKnown ? `适用版本：${s.version}` : ""}{versionKnown && s.validAt ? " · " : ""}{s.validAt ? `有效时间：${s.validAt}` : ""}</p> : null}
      {nonEmpty(block.gaps) ? <div className="claim-limit"><strong>记录的缺口</strong>{list(block.gaps)}</div> : null}
      {block.resolution ? <p className="claim-limit"><strong>争议解决依据：</strong>{block.resolution}</p> : null}
      <details className="claim-scope">
        <summary>来源与核查详情{nonEmpty(s.conditions) ? " · 含适用条件" : ""}</summary>
        <dl className="facts">
          <dt>适用版本</dt><dd>{s.version}</dd>
          <dt>有效时间</dt><dd>{s.validAt ?? "未确定"}</dd>
          {nonEmpty(s.conditions) ? <><dt>条件（原文记录）</dt><dd>{list(s.conditions)}</dd></> : null}
          {nonEmpty(block.gaps) ? <><dt>缺口</dt><dd>{list(block.gaps)}</dd></> : null}
          {block.resolution ? <><dt>争议解决依据</dt><dd>{block.resolution}</dd></> : null}
          {hidden.length ? <><dt>原文说明</dt><dd><ul className="claim-list">{hidden.map((p, i) => <li key={i}>{p.kind === "code" ? p.caption : p.original}</li>)}</ul></dd></> : null}
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
    if (node.type === "link") {
      const url = safeCitationUrl(node.href);
      return url ? <a key={k} className="inline-link" href={url.href} title={node.href} target="_blank" rel="noopener noreferrer">{node.label}</a> : node.href;
    }
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
          case "code": return <CodeBlock key={key} code={block.text} lang="" untested={false} />;
          case "aside": return <details key={key} className="report-aside"><summary>{block.title}</summary><p>{block.text}</p></details>;
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
