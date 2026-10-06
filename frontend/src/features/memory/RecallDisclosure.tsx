import { Fragment } from "react";
import type { RecallView } from "../../domain/memoryRecall";
import { CLAIM_STATUS } from "../../domain/progressMemory";
import { recallHeadline } from "./recallText";

const short = (id: string) => (id.length > 16 ? id.slice(0, 8) + "…" + id.slice(-6) : id);

export type RecallRead = { state: "loading" } | { state: "absent" } | { state: "unavailable"; message: string } | { state: "ok"; view: RecallView };

/**
 * Read-only "历史研究参考" for an autonomous run. Recalled research is a clue to re-check: applicability
 * (time, versions, conditions) is unknown unless stated, disputes stay disputes, and shared sources count once.
 */
export function RecallDisclosure({ read }: { read: RecallRead }) {
  if (read.state === "absent") return null;
  if (read.state === "loading") return <p className="note summary-row">历史研究参考：正在读取…</p>;
  if (read.state === "unavailable") return <p className="note summary-row recall-unavailable" role="status">历史研究参考：{read.message} 页面不会显示缓存的旧内容。</p>;
  const v = read.view;
  const headline = recallHeadline(v);
  if (!v.records.length) return <p className="note summary-row recall-line" data-status={v.status} role="status">历史研究参考：{headline}</p>;
  return (
    <details className="summary-disclosure recall-disclosure" data-status={v.status}>
      <summary>历史研究参考 · {headline}</summary>
      <div className="summary-body">
        <p className="note">这些是你以前保存的研究，只作调查线索：时间、版本和条件都需要重新核查；旧的“已完成”不能证明本次问题已解决。{v.message ? `服务端说明：${v.message}` : ""}</p>
        {v.records.map((r, i) => {
          const s = r.snapshot;
          const disputed = s?.sourceClaims.filter((c) => c.decisionStatus === "contested") ?? [];
          return (
            <section key={r.sourceRunId || i} className="recall-record">
              <h4 className="insp-label">参考 {i + 1}：{s ? s.originalGoal : "无法确认该记录的内容"}</h4>
              {r.snapshotError ? <p className="missing">{r.snapshotError}</p> : null}
              {s?.currentQuestion && s.currentQuestion !== s.originalGoal ? <p className="note">当时的问题：{s.currentQuestion}</p> : null}
              <p className="note">相关原因：关键词 {r.reason.matchedTerms.length ? r.reason.matchedTerms.map((t) => `“${t}”`).join("、") : "（未记录）"}{r.reason.method ? `（${r.reason.method}）` : ""}</p>
              <ul className="nb-gaps">
                <li>适用性：{r.applicability.status === "RECHECK_REQUIRED" ? "需要重新核查" : r.applicability.status}</li>
                {r.applicability.mentionedVersions.length ? <li>涉及版本：{r.applicability.mentionedVersions.join("、")}（与本次问题的版本可能不同）</li> : null}
                {r.applicability.cautions.map((c, j) => <li key={j}>注意：{c}</li>)}
                <li>时间：{r.applicability.time?.status === "unknown" || !r.applicability.time ? `未知${r.applicability.time?.reason ? `（${r.applicability.time.reason}）` : ""}` : String(r.applicability.time.value)}</li>
                <li>条件：{r.applicability.conditions?.status === "unknown" || !r.applicability.conditions ? `未知${r.applicability.conditions?.reason ? `（${r.applicability.conditions.reason}）` : ""}` : String(r.applicability.conditions.value)}</li>
                {disputed.length ? <li className="recall-dispute">有争议：{disputed.length} 条当时的论断仍有争议（{disputed.map((c) => CLAIM_STATUS[c.decisionStatus]?.label ?? c.decisionStatus).join("、")}），不能当作结论。</li> : null}
                {s?.userCorrection ? <li>用户纠正说明：{s.userCorrection}</li> : null}
              </ul>
              {s && s.unresolvedQuestions.length ? <p className="note">当时未解决：{s.unresolvedQuestions.map((g) => g.goal).join("；")}</p> : null}
              {r.sourceRefs.length ? (
                <div>
                  <p className="insp-label">原始来源（去重后 {r.sourceRefs.length} 个；同一来源只计一次，不算独立证据）</p>
                  <ul className="nb-gaps">{r.sourceRefs.map((src) => <li key={src.sourceKey}>{src.label}<span className="source-id">　证据 {short(src.evidenceId)}</span></li>)}</ul>
                </div>
              ) : <p className="note">没有可列出的原始来源。</p>}
            </section>
          );
        })}
        <details className="note">
          <summary style={{ cursor: "pointer", width: "fit-content" }}>技术详情</summary>
          <dl className="facts" style={{ marginTop: 8 }}>
            <dt>status</dt><dd className="source-id">{v.status}</dd>
            <dt>planner_input_recorded</dt><dd className="source-id">{String(v.plannerInputRecorded)}</dd>
            <dt>runId</dt><dd className="source-id">{v.runId}</dd>
            {v.selection ? <><dt>selection</dt><dd className="source-id">{`${v.selection.method} 候选上限 ${v.selection.candidateLimit ?? "—"} · 最多 ${v.selection.maxRecords ?? "—"} 条 · ${v.selection.limitBytes ?? "—"} 字节 · 不相关 ${v.selection.unrelated} · 不可访问 ${v.selection.inaccessible} · 重复 ${v.selection.duplicates} · 省略 ${v.selection.omitted}`}</dd></> : null}
            {v.records.map((r, i) => <Fragment key={r.sourceRunId || i}><dt>{short(r.sourceRunId)}</dt><dd className="source-id">{r.sourceProjectId} · {r.snapshotSha256.slice(0, 16)}… · score {r.reason.score ?? "—"}</dd></Fragment>)}
          </dl>
        </details>
      </div>
    </details>
  );
}
