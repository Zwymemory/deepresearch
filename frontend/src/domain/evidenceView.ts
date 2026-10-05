// Public evidence read API: GET /api/research/workflows/{runId}/evidence (schema evidence-view/1).
// Types mirror EvidenceViewDtos (backend). Only autonomous runs have a projection.
//
// Join rules (verified server-side): a claim link's (evidenceId, evidenceVersion) refers to
// evidence[].identity (recordId, version). Evidence `sourceId` is the evidence record's own
// source identity and is NOT guaranteed to equal a report citation ID, so this view is never
// joined onto `[来源N]` markers.

export interface Tagged { status: "known" | "unknown" | (string & {}); value: string | null }
export interface Applicability { version: Tagged | null; validAt: Tagged | null; conditions: string[] | null }
export interface Identity { recordType: string; recordId: string; version: number; payloadSha256: string; recordedAt: string }
export interface EvidenceRef {
  identity: Identity; sourceId: string; kind: "knowledge" | "web" | (string & {}); title: string | null; url: string | null;
  publishedAt: Tagged | null; observedAt: string | null; snapshotSha256: string; applicability: Applicability | null;
}
export interface Quote { start: number; end: number; sha256: string; text: string | null; textAvailability: string | null }
export interface Link { evidenceId: string; evidenceVersion: number; relation: "supports" | "refutes" | "insufficient" | (string & {});
  disposition: "adopted" | "unresolved" | "dismissed" | (string & {}); quote: Quote | null }
export interface Claim {
  identity: Identity; text: string; kind: string; applicability: Applicability | null; decisionStatus: string;
  checkId: string; latestRecordedRound: boolean; publicationState: "RECORDED_ONLY" | "IN_FINALIZED_REPORT" | (string & {}); evidenceLinks: Link[];
}
export interface Decision { identity: Identity; claimId: string; decisionStatus: string; policyVersion: string;
  adoptedEvidenceIds: string[]; unresolvedEvidenceIds: string[]; dismissedEvidence: { evidenceId: string }[]; gapCodes: string[] }
export interface Check { checkId: string; investigationId: string; disputeRound: number; parentCheckId: string | null; status: string;
  requestSha256: string; recordedAt: string; completedAt: string | null; latestRecordedRound: boolean; claimIds: string[]; evidenceIds: string[] }
export interface Disagreement { claimId: string; decisionId: string; checkId: string; supportingEvidenceIds: string[]; refutingEvidenceIds: string[] }
export interface Blocked { attemptId: string; investigationId: string; disputeRound: number; errorCode: string; payloadSha256: string;
  recordedAt: string; resolvedByLaterCheck: boolean }

export interface EvidenceView {
  schemaVersion: string; runId: string; runStatus: string;
  availability: "UNSUPPORTED_MODE" | "NO_RECORDS_YET" | "RECORDED_INCOMPLETE" | "AVAILABLE" | "BOUNDED_OUT" | (string & {});
  publicationState: "RECORDED_ONLY" | "FINALIZED_REPORT" | "NOT_ASSESSED" | (string & {});
  limits: { records: number; checks: number; sourceReads: number; blockedAttempts: number; responseBytes: number; completeProjection: boolean };
  limitations: string[];
  evidence: EvidenceRef[]; claims: Claim[]; decisions: Decision[]; checks: Check[]; disagreements: Disagreement[]; blockedAttempts: Blocked[];
}

/** Outcome of reading the evidence view, including non-200 capability states. */
export type EvidenceViewResult =
  | { state: "ok"; view: EvidenceView }
  | { state: "disabled" }        // 503 EVIDENCE_VIEW_DISABLED
  | { state: "integrity" }       // 409 EVIDENCE_VIEW_INTEGRITY_INVALID
  | { state: "error"; message: string };

export const AVAILABILITY: Record<string, { label: string; detail: string }> = {
  UNSUPPORTED_MODE: { label: "此执行方式没有证据记录视图", detail: "LangGraph 与 Dify 工作流不提供论断级证据记录；报告仍保留引用级来源。" },
  NO_RECORDS_YET: { label: "尚无证据记录", detail: "该运行还没有记录证据、读取、检查或受阻尝试。" },
  RECORDED_INCOMPLETE: { label: "记录尚未完成", detail: "仍有待完成的读取、等待中的检查、未解决的受阻尝试，或还没有完成的论断。" },
  AVAILABLE: { label: "证据记录可查看", detail: "本视图范围内没有未完成的检查或读取；这不表示研究目标已全部完成，也可能包含有争议或不足的结论。" },
  BOUNDED_OUT: { label: "记录超出可展示上限", detail: "为避免只展示部分关系，服务端没有返回任何记录。这不代表没有研究内容或没有分歧。" },
};

export const LIMITATIONS: Record<string, string> = {
  RECORDED_OBSERVATIONS_ONLY: "只包含已记录的观察，不重新检索来源。",
  EMPTY_DOES_NOT_PROVE_NO_CONFLICT: "列表为空不能证明不存在分歧。",
  MODEL_RELATIONS_ARE_NOT_TRUTH_GUARANTEES: "“支持 / 反驳”是模型判定的关系，不是事实保证。",
  PUBLICATION_REQUIRES_EXISTING_SEAL_AND_FINALIZATION: "论断只有在发布封存并完成终结后才属于最终报告。",
  NO_SOURCE_REFRESH: "不会刷新或重新读取来源。",
  NO_RAW_SNAPSHOTS_OR_MODEL_RATIONALES: "不包含原始快照或模型推理内容。",
  PROJECTION_CAPACITY_EXCEEDED_NO_PARTIAL_DATA: "超出容量，没有返回部分数据。",
};

export const DECISION_STATUS: Record<string, { label: string; tone: "ok" | "warn" | "error" | "neutral" }> = {
  supported: { label: "有证据支持", tone: "ok" },
  refuted: { label: "被证据反驳", tone: "error" },
  contested: { label: "存在记录的分歧", tone: "warn" },
  insufficient: { label: "证据不足", tone: "neutral" },
};

export const RELATION: Record<string, string> = { supports: "支持", refutes: "反驳", insufficient: "不足以判断" };
export const DISPOSITION: Record<string, string> = { adopted: "已采纳", unresolved: "未解决", dismissed: "已排除" };

export const evidenceKey = (id: string, version: number) => `${id}@${version}`;

export function evidenceIndex(view: EvidenceView): Map<string, EvidenceRef> {
  return new Map(view.evidence.map((e) => [evidenceKey(e.identity.recordId, e.identity.version), e]));
}

export interface LinkedEvidence { link: Link; evidence: EvidenceRef | null }

/** Claim links resolved to their evidence records (null when the referenced record is not projected). */
export function linkedEvidence(view: EvidenceView, claim: Claim): LinkedEvidence[] {
  const index = evidenceIndex(view);
  return claim.evidenceLinks.map((link) => ({ link, evidence: index.get(evidenceKey(link.evidenceId, link.evidenceVersion)) ?? null }));
}

export interface RecordedDisagreement {
  claim: Claim;
  decision: Decision | null;
  supporting: LinkedEvidence[];
  refuting: LinkedEvidence[];
}

/** Disagreements exactly as recorded; nothing is inferred from user-chosen comparisons or source counts. */
export function recordedDisagreements(view: EvidenceView): RecordedDisagreement[] {
  return view.disagreements.flatMap((d) => {
    const claim = view.claims.find((c) => c.identity.recordId === d.claimId);
    if (!claim) return [];
    const links = linkedEvidence(view, claim);
    const pick = (ids: string[]) => links.filter((l) => ids.includes(l.link.evidenceId));
    return [{ claim, decision: view.decisions.find((x) => x.identity.recordId === d.decisionId) ?? null,
      supporting: pick(d.supportingEvidenceIds), refuting: pick(d.refutingEvidenceIds) }];
  });
}

export function taggedDate(tag: Tagged | null | undefined): string {
  return tag && tag.status === "known" && tag.value ? tag.value : "未知";
}
