import { safeCitationUrl } from "../../domain/citations";
import {
  AVAILABILITY, DECISION_STATUS, DISPOSITION, LIMITATIONS, RELATION, linkedEvidence, recordedDisagreements, taggedDate,
  type Claim, type EvidenceView, type EvidenceViewResult, type LinkedEvidence,
} from "../../domain/evidenceView";
import { Icon } from "../../ui/Icon";

function EvidenceLine({ item }: { item: LinkedEvidence }) {
  const { link, evidence } = item;
  const url = safeCitationUrl(evidence?.url ?? null);
  return (
    <li className="ev-link">
      <div className="flex flex-wrap items-center gap-2">
        <span className={"chip " + (link.relation === "supports" ? "chip-ok" : link.relation === "refutes" ? "chip-error" : "chip-warn")}>{RELATION[link.relation] ?? link.relation}</span>
        <span className="chip">{DISPOSITION[link.disposition] ?? link.disposition}</span>
        {evidence ? <span className={"chip " + (evidence.kind === "knowledge" ? "chip-kb" : "chip-web")}>{evidence.kind === "knowledge" ? "知识库" : "网页"}</span> : null}
      </div>
      <div className="ev-title">
        {!evidence ? "该证据记录未在视图中投影" : url ? <a href={url.href} target="_blank" rel="noopener noreferrer">{evidence.title ?? url.host}</a> : evidence.title ?? "标题不可用"}
      </div>
      {link.quote?.text ? <blockquote className="ev-quote">{link.quote.text}</blockquote>
        : link.quote ? <p className="note">引文因长度限制未显示（保留哈希与位置，未做截断）。</p> : null}
      {evidence ? <p className="note">发布日期：{taggedDate(evidence.publishedAt)} · 读取记录时间：{evidence.observedAt ? new Date(evidence.observedAt).toLocaleString() : "未记录"}（不是发布日期）</p> : null}
    </li>
  );
}

function ClaimItem({ view, claim }: { view: EvidenceView; claim: Claim }) {
  const status = DECISION_STATUS[claim.decisionStatus] ?? { label: claim.decisionStatus, tone: "neutral" as const };
  const decision = view.decisions.find((d) => d.claimId === claim.identity.recordId);
  return (
    <details className="ev-claim">
      <summary>
        <span className="status-tag" data-tone={status.tone}>{status.label}</span>
        <span className="ev-claim-text">{claim.text}</span>
        <span className="note">{claim.publicationState === "IN_FINALIZED_REPORT" ? "在最终报告中" : "仅为记录"}{claim.latestRecordedRound ? "" : " · 早期轮次"}</span>
      </summary>
      <ul className="ev-links">{linkedEvidence(view, claim).map((item, i) => <EvidenceLine key={i} item={item} />)}</ul>
      {decision?.gapCodes.length ? <p className="note">记录的缺口：{decision.gapCodes.join("、")}</p> : null}
    </details>
  );
}

export function EvidenceRecord({ result, loading, demo, onCompare }: {
  result: EvidenceViewResult | null; loading: boolean; demo: boolean; onCompare: (index: number) => void;
}) {
  if (loading && !result) return <section className="ev-record"><p className="note">正在读取证据记录…</p></section>;
  if (!result) return null;
  return (
    <section className="ev-record" aria-labelledby="ev-title">
      <h2 id="ev-title"><Icon name="list" />证据记录{demo ? <span className="chip chip-warn">示例数据</span> : null}</h2>
      {result.state === "disabled" ? <p className="note">此部署未启用证据记录视图（EVIDENCE_VIEW_DISABLED）。报告与引用不受影响。</p>
        : result.state === "integrity" ? <p className="missing">服务端检查记录完整性未通过，未返回任何内容（EVIDENCE_VIEW_INTEGRITY_INVALID）。</p>
        : result.state === "error" ? <p className="missing">{result.message}</p>
        : <EvidenceBody view={result.view} onCompare={onCompare} />}
    </section>
  );
}

function EvidenceBody({ view, onCompare }: { view: EvidenceView; onCompare: (index: number) => void }) {
  const availability = AVAILABILITY[view.availability] ?? { label: view.availability, detail: "" };
  const disagreements = recordedDisagreements(view);
  const unresolvedBlocks = view.blockedAttempts.filter((b) => !b.resolvedByLaterCheck).length;
  return (
    <>
      <p><strong>{availability.label}</strong>{availability.detail ? <span className="note">　{availability.detail}</span> : null}</p>
      <div className="meta-row" style={{ marginTop: 8 }}>
        <span>论断 {view.claims.length}</span><span>证据记录 {view.evidence.length}</span><span>检查 {view.checks.length}</span>
        <span>记录的分歧 {view.disagreements.length}</span>{unresolvedBlocks ? <span>未解决的受阻尝试 {unresolvedBlocks}</span> : null}
        <span className="chip">{view.publicationState === "FINALIZED_REPORT" ? "已终结发布" : view.publicationState === "NOT_ASSESSED" ? "未评估发布" : "仅记录，未发布"}</span>
      </div>
      {disagreements.length ? (
        <div className="ev-disagreements">
          {disagreements.map((d, i) => (
            <div key={d.claim.identity.recordId} className="relation">
              <strong>记录的分歧：{d.claim.text}</strong>
              <span className="note">支持 {d.supporting.length} 条 · 反驳 {d.refuting.length} 条；系统未判定哪一方正确。</span>
              <button type="button" className="btn btn-quiet btn-sm" style={{ justifySelf: "start" }} onClick={() => onCompare(i)}><Icon name="compare" size={15} />对照查看两侧证据</button>
            </div>
          ))}
        </div>
      ) : view.availability !== "BOUNDED_OUT" ? <p className="note" style={{ marginTop: 8 }}>没有记录到分歧。这只表示未记录，不证明不存在分歧。</p> : null}
      {view.claims.length ? <div className="ev-claims">{view.claims.map((c) => <ClaimItem key={c.identity.recordId + "@" + c.identity.version} view={view} claim={c} />)}</div> : null}
      <ul className="ev-limitations">{view.limitations.map((l) => <li key={l}>{LIMITATIONS[l] ?? l}</li>)}</ul>
    </>
  );
}
