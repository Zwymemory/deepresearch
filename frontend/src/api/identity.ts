// Identity and browser storage. Keys and formats are shared with the V1 page so
// either page can resume the other's run on the same origin.
//  - auth settings (no token)      → localStorage
//  - bearer token (opt-in only)     → sessionStorage
//  - current run / pending create   → sessionStorage
//  - recent run ids (≤5)            → localStorage
import { validatedBase } from "./http";

export const STORE = {
  run: "deepresearch.console.currentRun",
  recent: "deepresearch.console.recentRuns",
  pending: "deepresearch.console.pendingCreate",
  auth: "deepresearch.console.auth",
  token: "deepresearch.console.sessionToken",
} as const;

export interface Identity {
  baseUrl: string;
  tenantId: string;
  userId: string;
  /** Kept in memory; persisted to sessionStorage only when `remember` is true. */
  token: string;
  remember: boolean;
}

export interface RequestScope { origin: string; tenantId: string; userId: string; credential: string }

type Storage = Pick<globalThis.Storage, "getItem" | "setItem" | "removeItem">;

function read<T>(storage: Storage | undefined, key: string, fallback: T): T {
  try {
    const raw = storage?.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

export function storages(): { local?: Storage; session?: Storage } {
  try { return { local: window.localStorage, session: window.sessionStorage }; } catch { return {}; }
}

/** FNV-1a fingerprint, identical to V1; never the token itself. */
export function tokenFingerprint(token: string): string {
  let hash = 2166136261;
  for (const ch of String(token || "")) {
    hash ^= ch.charCodeAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}

export function requestScope(identity: Identity, origin: string): RequestScope {
  return { origin, tenantId: identity.tenantId, userId: identity.userId, credential: tokenFingerprint(identity.token) };
}

export function sameScope(a: RequestScope | null | undefined, b: RequestScope): boolean {
  return !!a && a.origin === b.origin && a.tenantId === b.tenantId && a.userId === b.userId && a.credential === b.credential;
}

/** Cache partition key: changes whenever the identity or credential changes. */
export function scopeKey(identity: Identity, origin: string): string {
  const s = requestScope(identity, origin);
  return [s.origin, s.tenantId, s.userId, s.credential].join("|");
}

export function loadIdentity(s = storages(), location: { origin: string; href: string } = window.location): { identity: Identity; rejectedCrossOrigin: boolean } {
  const saved = read<{ baseUrl?: string; tenantId?: string; userId?: string }>(s.local, STORE.auth, {});
  let baseUrl = "";
  let rejectedCrossOrigin = false;
  try {
    baseUrl = validatedBase(saved.baseUrl || "", location);
  } catch {
    // Old cross-origin settings are discarded together with session credentials (V1 rule).
    rejectedCrossOrigin = true;
    s.local?.removeItem(STORE.auth);
    for (const key of [STORE.token, STORE.run, STORE.pending]) s.session?.removeItem(key);
  }
  let token = "";
  if (!rejectedCrossOrigin) { try { token = s.session?.getItem(STORE.token) || ""; } catch { token = ""; } }
  return {
    identity: { baseUrl, tenantId: saved.tenantId || "demo-tenant", userId: saved.userId || "demo-user", token, remember: !!token },
    rejectedCrossOrigin,
  };
}

export function saveIdentity(identity: Identity, s = storages()) {
  try {
    s.local?.setItem(STORE.auth, JSON.stringify({ baseUrl: identity.baseUrl, tenantId: identity.tenantId, userId: identity.userId }));
    if (identity.remember && identity.token) s.session?.setItem(STORE.token, identity.token);
    else s.session?.removeItem(STORE.token);
  } catch { /* storage unavailable: identity stays in memory */ }
}

export interface SavedRun {
  mode: "workflow" | "agent";
  runId: string;
  sessionId?: string;
  status?: string;
  stage?: string;
  progress?: number;
  lastEventId?: string;
  question?: string;
}

export const loadSavedRun = (s = storages()) => read<SavedRun | null>(s.session, STORE.run, null);
export function saveRun(run: SavedRun | null, s = storages()) {
  try {
    if (run?.runId) s.session?.setItem(STORE.run, JSON.stringify(run));
    else s.session?.removeItem(STORE.run);
  } catch { /* ignore */ }
}

export interface RecentRun { mode: string; runId: string; sessionId?: string; status?: string; question?: string; updatedAt: number }
export const loadRecent = (s = storages()) => {
  const rows = read<RecentRun[]>(s.local, STORE.recent, []);
  return Array.isArray(rows) ? rows : [];
};
export function rememberRecent(row: RecentRun, s = storages()): RecentRun[] {
  // Only the first 80 characters of a question are kept locally (V1 rule).
  const next = [{ ...row, question: String(row.question || "").slice(0, 80) }, ...loadRecent(s).filter((r) => r.runId !== row.runId)].slice(0, 5);
  try { s.local?.setItem(STORE.recent, JSON.stringify(next)); } catch { /* ignore */ }
  return next;
}
