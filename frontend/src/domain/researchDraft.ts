// Budget-exhausted autonomous runs: the stored-records draft (finalResponse.researchDraft,
// schema research-draft/1). It is NOT a published report: sources are unchecked material,
// checked claims are recorded decisions that were never finally published, and nothing here is
// counted as a report citation. Anything outside the contract makes the draft "invalid".

export type DraftSourceKind = "WEB_ORIGINAL" | "WEB_SEARCH_SNAPSHOT" | "KNOWLEDGE_CHUNK";
export interface DraftSource {
  sourceId: string; kind: DraftSourceKind; title: string; url: string | null;
  excerpt: string; excerptTruncated: boolean; observedAt: string | null;
}
export interface DraftScopeValue { status: string; value: string | null }
export interface DraftClaim {
  text: string; decisionStatus: string;
  applicability: { version: DraftScopeValue; validAt: DraftScopeValue; conditions: string[] };
  sourceIds: string[];
}
export interface ResearchDraft {
  sources: DraftSource[];
  checkedClaims: DraftClaim[];
  pendingTasks: Array<{ text: string; status: string }>;
  limits: { sourceLimit: number; excerptChars: number; omittedSources: number; omittedClaims: number; omittedTasks: number };
  markdown: string;
}
export type DraftRead = { state: "absent" } | { state: "invalid"; reason: string } | { state: "ok"; draft: ResearchDraft };

const KINDS: readonly string[] = ["WEB_ORIGINAL", "WEB_SEARCH_SNAPSHOT", "KNOWLEDGE_CHUNK"];
const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);
const isStr = (v: unknown): v is string => typeof v === "string";
const isNat = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v) && v >= 0;

class Invalid extends Error {}
const need = (ok: boolean, what: string) => { if (!ok) throw new Invalid(what); };

function scope(v: unknown, what: string): DraftScopeValue {
  need(isObj(v) && isStr(v.status) && (v.value === null || v.value === undefined || isStr(v.value)), what);
  const r = v as Record<string, unknown>;
  return { status: r.status as string, value: isStr(r.value) ? r.value : null };
}

/** Strictly validates the optional draft. Absent → "absent"; any contract mismatch → "invalid". */
export function readResearchDraft(value: unknown): DraftRead {
  if (value === undefined || value === null) return { state: "absent" };
  try {
    need(isObj(value), "草稿不是对象");
    const v = value as Record<string, unknown>;
    need(v.schemaVersion === "research-draft/1", "未知的草稿格式");
    need(v.reasonCode === "BUDGET_EXCEEDED", "草稿原因不是预算终止");
    need(v.generatedFrom === "STORED_RECORDS", "草稿来源不是已存储的记录");
    need(v.additionalModelCalls === 0, "草稿声明了额外的模型调用");
    need(Array.isArray(v.sources) && Array.isArray(v.checkedClaims) && Array.isArray(v.pendingTasks) && isObj(v.limits) && isStr(v.markdown), "草稿字段不完整");
    const sources = (v.sources as unknown[]).map((s, i): DraftSource => {
      need(isObj(s), `来源 ${i + 1} 格式不正确`);
      const r = s as Record<string, unknown>;
      need(isStr(r.sourceId) && isStr(r.kind) && KINDS.includes(r.kind) && isStr(r.title) && (r.url === null || isStr(r.url))
        && isStr(r.excerpt) && typeof r.excerptTruncated === "boolean" && (r.observedAt === null || isStr(r.observedAt))
        && r.verificationStatus === "NOT_CLAIM_CHECKED", `来源 ${i + 1} 不符合约定`);
      return { sourceId: r.sourceId as string, kind: r.kind as DraftSourceKind, title: r.title as string, url: (r.url as string | null) ?? null,
        excerpt: r.excerpt as string, excerptTruncated: r.excerptTruncated as boolean, observedAt: (r.observedAt as string | null) ?? null };
    });
    const claims = (v.checkedClaims as unknown[]).map((c, i): DraftClaim => {
      need(isObj(c), `结论 ${i + 1} 格式不正确`);
      const r = c as Record<string, unknown>;
      need(isStr(r.text) && isStr(r.decisionStatus) && isObj(r.applicability) && Array.isArray(r.sourceIds) && (r.sourceIds as unknown[]).every(isStr), `结论 ${i + 1} 不符合约定`);
      const a = r.applicability as Record<string, unknown>;
      need(Array.isArray(a.conditions) && (a.conditions as unknown[]).every(isStr), `结论 ${i + 1} 的条件不符合约定`);
      return { text: r.text as string, decisionStatus: r.decisionStatus as string, sourceIds: r.sourceIds as string[],
        applicability: { version: scope(a.version, `结论 ${i + 1} 的版本不符合约定`), validAt: scope(a.validAt, `结论 ${i + 1} 的时间不符合约定`), conditions: a.conditions as string[] } };
    });
    const tasks = (v.pendingTasks as unknown[]).map((t, i) => {
      need(isObj(t) && isStr(t.text) && isStr(t.status), `待办 ${i + 1} 不符合约定`);
      return { text: (t as Record<string, string>).text, status: (t as Record<string, string>).status };
    });
    const l = v.limits as Record<string, unknown>;
    need(isNat(l.sourceLimit) && isNat(l.excerptChars) && isNat(l.omittedSources) && isNat(l.omittedClaims) && isNat(l.omittedTasks), "草稿上限字段不符合约定");
    need(sources.length <= (l.sourceLimit as number), "来源数量超过声明的上限");
    return { state: "ok", draft: { sources, checkedClaims: claims, pendingTasks: tasks, markdown: v.markdown as string,
      limits: { sourceLimit: l.sourceLimit as number, excerptChars: l.excerptChars as number, omittedSources: l.omittedSources as number, omittedClaims: l.omittedClaims as number, omittedTasks: l.omittedTasks as number } } };
  } catch (e) {
    if (e instanceof Invalid) return { state: "invalid", reason: e.message };
    throw e;
  }
}

/** Exactly the server-provided Markdown as a downloadable file; nothing is added or changed. */
export function draftDownload(markdown: string, runId: string): { blob: Blob; filename: string } {
  const safeId = runId.replace(/[^A-Za-z0-9_-]/g, "") || "run";
  return { blob: new Blob([markdown], { type: "text/markdown;charset=utf-8" }), filename: `research-draft-${safeId}.md` };
}
