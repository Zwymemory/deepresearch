import { useState } from "react";
import { kindLabel, type NormalizedCitation } from "../../domain/citations";
import { Sheet } from "../shell/Dialogs";

type Filter = "all" | "knowledge" | "web";

/** Filtering never renumbers: each row keeps its original citation number and kind. */
export function SourcesDialog({ open, onOpenChange, citations, onInspect }: {
  open: boolean; onOpenChange: (open: boolean) => void; citations: NormalizedCitation[]; onInspect: (n: number) => void;
}) {
  const [filter, setFilter] = useState<Filter>("all");
  const shown = citations.filter((c) => filter === "all" || (filter === "knowledge" ? c.kind === "knowledge" : c.kind === "web-snapshot" || c.kind === "web-original"));
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="全部来源" description="按引用编号排列；顺序不代表可信度。示例数据。">
      <div className="filter segmented" role="radiogroup" aria-label="来源类型">
        {(["all", "knowledge", "web"] as Filter[]).map((f) => (
          <button key={f} type="button" role="radio" className="seg-btn" aria-checked={filter === f} onClick={() => setFilter(f)}>
            {f === "all" ? `全部 ${citations.length}` : f === "knowledge" ? "知识库" : "网页"}
          </button>
        ))}
      </div>
      <div>
        {shown.map((c) => (
          <div key={c.sourceId} className="source-row">
            <span className="num">来源{c.number}</span>
            <div style={{ minWidth: 0 }}>
              <span className={"chip " + (c.kind === "knowledge" ? "chip-kb" : c.kind === "unknown" ? "chip-warn" : "chip-web")}>{kindLabel(c.kind)}</span>
              <div className="title" style={{ marginTop: 4 }}>{c.displayTitle}</div>
              {c.address ? <div className="insp-address">{c.address}</div> : null}
              <button type="button" className="link-btn" style={{ marginTop: 6 }} onClick={() => onInspect(c.number)}>在报告旁检查</button>
            </div>
          </div>
        ))}
      </div>
    </Sheet>
  );
}
