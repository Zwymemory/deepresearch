import { useEffect, useRef, useState } from "react";
import { kindLabel, type NormalizedCitation } from "../../domain/citations";
import { FUTURE_RECORDED_DISAGREEMENT } from "../../demo/fixtures";
import { Icon } from "../../ui/Icon";

const chipFor = (c: NormalizedCitation) => c.kind === "knowledge" ? "chip-kb" : c.kind === "unknown" ? "chip-warn" : "chip-web";

function Passage({ side, citation }: { side: "a" | "b"; citation: NormalizedCitation }) {
  return (
    <article className="passage" data-side={side} aria-labelledby={`passage-${side}`}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="num">来源{citation.number}</span>
        <span className={"chip " + chipFor(citation)}>{kindLabel(citation.kind)}</span>
      </div>
      <h2 id={`passage-${side}`} style={{ fontSize: 17, fontWeight: 650, lineHeight: 1.5, overflowWrap: "anywhere" }}>
        {citation.url ? <a href={citation.url.href} target="_blank" rel="noopener noreferrer">{citation.displayTitle}</a> : citation.displayTitle}
      </h2>
      {citation.address ? <p className="insp-address">{citation.address}</p> : null}
      {citation.excerpt.trim() ? <p className="excerpt">{citation.excerpt}</p> : <p className="missing">{citation.missingReason}</p>}
      <dl className="facts"><dt>发布日期</dt><dd>未记录</dd><dt>适用时间</dt><dd>未记录</dd></dl>
    </article>
  );
}

export function CompareView({ statement, a, b, all, recorded, onChangeB, onBack, demo }: {
  statement: string | null; a: NormalizedCitation; b: NormalizedCitation; all: NormalizedCitation[]; demo: boolean;
  recorded: boolean; onChangeB: (n: number) => void; onBack: () => void;
}) {
  const [pane, setPane] = useState<"a" | "b">("a");
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { title.current?.focus({ preventScroll: true }); window.scrollTo({ top: 0 }); }, []);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onBack(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onBack]);
  const shownStatement = recorded ? FUTURE_RECORDED_DISAGREEMENT.statement : statement;

  return (
    <section className="compare" aria-labelledby="compare-title">
      <div className="compare-bar">
        <button type="button" className="btn btn-quiet btn-sm" onClick={onBack}><Icon name="arrowLeft" size={16} />返回报告</button>
        <label className="mode-picker note">
          <span>比较对象</span>
          <select value={b.number} onChange={(e) => onChangeB(Number(e.target.value))}>
            {all.filter((c) => c.number !== a.number).map((c) => <option key={c.number} value={c.number}>来源{c.number} · {c.displayTitle.slice(0, 24)}</option>)}
          </select>
        </label>
      </div>

      <div>
        <h1 id="compare-title" ref={title} tabIndex={-1} className="entry-title" style={{ textAlign: "left", fontSize: "clamp(24px,3vw,32px)" }}>来源比较</h1>
        <div className="flex flex-wrap gap-2" style={{ marginTop: 10 }}>
          {recorded
            ? <span className="chip chip-future">后端记录的分歧 · 未来契约示例（当前接口未提供）</span>
            : <span className="chip chip-accent">你选择的比较 · 系统未对两者作出判定</span>}
          {demo ? <span className="chip chip-warn">示例数据</span> : null}
        </div>
      </div>

      {shownStatement ? (
        <div className="compare-statement">
          <span className="insp-label" style={{ display: "block", fontFamily: "var(--font-sans)" }}>{recorded ? "涉及的语句" : "报告中的语句"}</span>
          {shownStatement}
        </div>
      ) : null}

      {recorded ? (
        <div className="relation" role="note">
          <strong>记录的关系：{FUTURE_RECORDED_DISAGREEMENT.relation}</strong>
          <p style={{ color: "var(--ink-2)" }}>{FUTURE_RECORDED_DISAGREEMENT.summary}</p>
          <p className="note">参考：{FUTURE_RECORDED_DISAGREEMENT.references.join("、")}。这是提议的版式示例；真实内容需要一个只读、经认证的公开投影，浏览器不会自行裁决哪一方正确。</p>
        </div>
      ) : (
        <p className="note">并列展示只帮助阅读两段材料；来源数量、域名或日期都不决定哪一方正确。</p>
      )}

      <div className="segmented compare-switch" role="tablist" aria-label="选择查看的来源">
        <button type="button" role="tab" className="seg-btn" aria-selected={pane === "a"} onClick={() => setPane("a")}>来源{a.number}</button>
        <button type="button" role="tab" className="seg-btn" aria-selected={pane === "b"} onClick={() => setPane("b")}>来源{b.number}</button>
      </div>
      <div className="compare-grid" data-mobile-pane={pane}>
        <Passage side="a" citation={a} />
        <Passage side="b" citation={b} />
      </div>
    </section>
  );
}
