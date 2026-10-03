import { motion, useReducedMotion } from "motion/react";
import { useEffect, useRef, useState } from "react";
import { kindLabel, type NormalizedCitation } from "../../domain/citations";
import { Icon } from "../../ui/Icon";

function useNarrow(query = "(max-width: 760px)") {
  const [narrow, setNarrow] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setNarrow(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, [query]);
  return narrow;
}

const chipFor = (c: NormalizedCitation) => c.kind === "knowledge" ? "chip-kb" : c.kind === "unknown" ? "chip-warn" : "chip-web";

export function Inspector({ citation, all, statements, onClose, onNavigate, onCompare, demo }: {
  citation: NormalizedCitation; all: NormalizedCitation[]; statements: string[]; demo: boolean;
  onClose: () => void; onNavigate: (number: number) => void; onCompare: (other: number) => void;
}) {
  const narrow = useNarrow();
  const reduce = useReducedMotion();
  const panel = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  // The source picker belongs to one citation; switching sources closes it without an extra render.
  const [pickingFor, setPickingFor] = useState<number | null>(null);
  const picking = pickingFor === citation.number;
  const setPicking = (update: (open: boolean) => boolean) => setPickingFor(update(picking) ? citation.number : null);

  useEffect(() => { heading.current?.focus({ preventScroll: true }); }, [citation.number]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); onClose(); return; }
      // On narrow screens the sheet is modal: keep keyboard focus inside it.
      if (event.key === "Tab" && narrow && panel.current) {
        const focusables = Array.from(panel.current.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex="0"], summary'));
        if (!focusables.length) return;
        const first = focusables[0], last = focusables[focusables.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [narrow, onClose]);

  const web = citation.kind === "web-snapshot" || citation.kind === "web-original";
  const others = all.filter((c) => c.number !== citation.number);
  const offset = reduce ? 0 : narrow ? 40 : 24;

  return (
    <>
      {narrow ? <div className="scrim" onClick={onClose} aria-hidden="true" /> : null}
      <motion.aside ref={panel} id="inspector" className="inspector" role="dialog" aria-modal={narrow} aria-labelledby="inspector-title"
        initial={{ opacity: 0, x: narrow ? 0 : offset, y: narrow ? offset : 0 }} animate={{ opacity: 1, x: 0, y: 0 }}
        exit={{ opacity: 0, x: narrow ? 0 : offset, y: narrow ? offset : 0 }} transition={{ duration: 0.22, ease: [0.2, 0.8, 0.2, 1] }}>
        <div className="inspector-head">
          <div style={{ minWidth: 0 }}>
            <div className="flex flex-wrap items-center gap-2">
              <span className="num">来源{citation.number}</span>
              <span className={"chip " + chipFor(citation)}>{kindLabel(citation.kind)}</span>
              {demo ? <span className="chip chip-warn">示例数据</span> : null}
            </div>
            <h2 id="inspector-title" ref={heading} tabIndex={-1} className="insp-title">
              {citation.url ? (
                <a href={citation.url.href} target="_blank" rel="noopener noreferrer">{citation.displayTitle}</a>
              ) : citation.displayTitle}
            </h2>
            {citation.address ? <p className="insp-address">{citation.address}</p>
              : citation.kind === "knowledge" ? <p className="insp-address">知识库文档 · 无公网地址</p> : null}
          </div>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="关闭来源检查，回到正文"><Icon name="x" /></button>
        </div>

        <div className="inspector-body">
          {statements.length ? (
            <section>
              <h3 className="insp-label">本报告中引用它的语句</h3>
              {statements.map((s, i) => <p key={i} className="statement">{s}</p>)}
            </section>
          ) : null}

          <section>
            <h3 className="insp-label">{citation.kind === "web-snapshot" ? "搜索摘要（非全文核验引文）" : citation.kind === "web-original" ? "原文片段" : citation.kind === "knowledge" ? "知识库摘录" : "摘录"}</h3>
            {citation.excerpt.trim() ? <p className="excerpt">{citation.excerpt}</p> : null}
            {citation.missingReason ? <p className="missing" style={{ marginTop: 8 }}>{citation.missingReason}</p> : null}
          </section>

          <section>
            <h3 className="insp-label">记录与检查</h3>
            <dl className="facts">
              <dt>引用映射</dt>
              <dd>{citation.indexed ? "INDEXED_V1：编号与来源 ID 一一对应（结构检查，不代表语句被证明为真）" : "未建立可验证映射"}</dd>
              <dt>语句支持检查</dt><dd>当前接口未公开逐条结果</dd>
              <dt>发布日期</dt><dd>未记录</dd>
              <dt>更新日期</dt><dd>未记录</dd>
              <dt>检索时间</dt><dd>未记录</dd>
              <dt>适用时间</dt><dd>未记录</dd>
            </dl>
          </section>

          {picking ? (
            <section aria-label="选择另一来源进行比较">
              <h3 className="insp-label">与哪一个来源比较？</h3>
              <div className="picker">
                {others.map((c) => (
                  <button key={c.number} type="button" onClick={() => onCompare(c.number)}>
                    <span className="num">来源{c.number}</span>
                    <span><span className={"chip " + chipFor(c)}>{kindLabel(c.kind)}</span><br />{c.displayTitle}</span>
                  </button>
                ))}
              </div>
            </section>
          ) : null}

          <details>
            <summary className="note" style={{ cursor: "pointer", width: "fit-content" }}>来源 ID</summary>
            <p className="source-id" style={{ marginTop: 6 }}>{citation.sourceId}</p>
          </details>
        </div>

        <div className="inspector-actions">
          {web && citation.url ? (
            <a className="btn btn-quiet btn-sm" href={citation.url.href} target="_blank" rel="noopener noreferrer" aria-label="在新标签页打开原网页"><Icon name="external" size={15} /><span className="label-long">打开原网页</span><span className="label-short">原网页</span></a>
          ) : null}
          {others.length ? (
            <button type="button" className="btn btn-quiet btn-sm" aria-expanded={picking} aria-label="与另一来源比较" onClick={() => setPicking((v) => !v)}><Icon name="compare" size={15} /><span className="label-long">与另一来源比较</span><span className="label-short">比较</span></button>
          ) : null}
          <span className="pager">
            <button type="button" className="btn btn-quiet btn-sm" disabled={citation.number <= 1} onClick={() => onNavigate(citation.number - 1)} aria-label="上一个来源">‹</button>
            <button type="button" className="btn btn-quiet btn-sm" disabled={citation.number >= all.length} onClick={() => onNavigate(citation.number + 1)} aria-label="下一个来源">›</button>
          </span>
        </div>
      </motion.aside>
    </>
  );
}
