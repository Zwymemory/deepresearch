import { useEffect, useRef, useState } from "react";
import { safeCitationUrl } from "../../domain/citations";
import { DECISION_STATUS, DISPOSITION, taggedDate, type LinkedEvidence, type RecordedDisagreement } from "../../domain/evidenceView";
import { Icon } from "../../ui/Icon";

function Side({ side, title, items }: { side: "a" | "b"; title: string; items: LinkedEvidence[] }) {
  return (
    <article className="passage" data-side={side} aria-labelledby={`side-${side}`}>
      <h2 id={`side-${side}`} style={{ fontSize: 16, fontWeight: 700 }}>{title}（{items.length}）</h2>
      {items.length === 0 ? <p className="note">此侧没有可投影的证据记录。</p> : items.map(({ link, evidence }, i) => {
        const url = safeCitationUrl(evidence?.url ?? null);
        return (
          <div key={i} className="grid gap-2">
            <div className="flex flex-wrap gap-2">
              {evidence ? <span className={"chip " + (evidence.kind === "knowledge" ? "chip-kb" : "chip-web")}>{evidence.kind === "knowledge" ? "知识库" : "网页"}</span> : null}
              <span className="chip">{DISPOSITION[link.disposition] ?? link.disposition}</span>
            </div>
            <strong style={{ overflowWrap: "anywhere" }}>{!evidence ? "证据记录未投影" : url ? <a href={url.href} target="_blank" rel="noopener noreferrer">{evidence.title ?? url.host}</a> : evidence.title ?? "标题不可用"}</strong>
            {link.quote?.text ? <p className="excerpt">{link.quote.text}</p> : <p className="note">{link.quote ? "引文因长度限制未显示。" : "没有绑定引文。"}</p>}
            {evidence ? <dl className="facts"><dt>发布日期</dt><dd>{taggedDate(evidence.publishedAt)}</dd><dt>适用时间</dt><dd>{taggedDate(evidence.applicability?.validAt)}</dd>
              {evidence.applicability?.conditions?.length ? <><dt>适用条件</dt><dd>{evidence.applicability.conditions.join("；")}</dd></> : null}</dl> : null}
          </div>
        );
      })}
    </article>
  );
}

/** A backend-recorded disagreement (evidence-view/1). Visibly distinct from a user-chosen comparison. */
export function RecordedDisagreementView({ disagreement, demo, onBack }: { disagreement: RecordedDisagreement; demo: boolean; onBack: () => void }) {
  const [pane, setPane] = useState<"a" | "b">("a");
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { title.current?.focus({ preventScroll: true }); window.scrollTo({ top: 0 }); }, []);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === "Escape") onBack(); };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onBack]);
  const status = DECISION_STATUS[disagreement.claim.decisionStatus];
  return (
    <section className="compare" aria-labelledby="disagreement-title">
      <div className="compare-bar">
        <button type="button" className="btn btn-quiet btn-sm" onClick={onBack}><Icon name="arrowLeft" size={16} />返回报告</button>
      </div>
      <div>
        <h1 id="disagreement-title" ref={title} tabIndex={-1} className="entry-title" style={{ textAlign: "left", fontSize: "clamp(24px,3vw,32px)" }}>记录的分歧</h1>
        <div className="flex flex-wrap gap-2" style={{ marginTop: 10 }}>
          <span className="chip chip-warn">后端记录的分歧 · 证据记录视图</span>
          {status ? <span className="status-tag" data-tone={status.tone}>{status.label}</span> : null}
          {demo ? <span className="chip chip-warn">示例数据</span> : null}
        </div>
      </div>
      <div className="compare-statement">
        <span className="insp-label" style={{ display: "block", fontFamily: "var(--font-sans)" }}>涉及的论断</span>
        {disagreement.claim.text}
      </div>
      <p className="note">这是服务端记录的关系：两侧证据分别被判定为支持与反驳，处置为未解决。浏览器不判断哪一方正确，也不依据来源数量、日期或域名作结论。</p>
      <div className="segmented compare-switch" role="tablist" aria-label="选择查看的一侧">
        <button type="button" role="tab" className="seg-btn" aria-selected={pane === "a"} onClick={() => setPane("a")}>支持</button>
        <button type="button" role="tab" className="seg-btn" aria-selected={pane === "b"} onClick={() => setPane("b")}>反驳</button>
      </div>
      <div className="compare-grid" data-mobile-pane={pane}>
        <Side side="a" title="支持该论断的证据" items={disagreement.supporting} />
        <Side side="b" title="反驳该论断的证据" items={disagreement.refuting} />
      </div>
    </section>
  );
}
