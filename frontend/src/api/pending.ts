// Idempotent creation records. A create whose outcome is unknown is persisted with
// the exact serialized body and key so it can only be replayed identically, and
// only by the same origin + identity + credential (V1 semantics).
import { sameScope, STORE, storages, type RequestScope } from "./identity";
import type { ExecutionMode } from "../domain/types";

export interface CreateBody { question: string; requestedTools?: string[]; sessionId?: string }

export interface PendingCreate {
  mode: ExecutionMode;
  key: string;
  body: CreateBody;
  /** Exact bytes sent on the first attempt; retries reuse them verbatim. */
  bodyJson: string;
  scope: RequestScope;
  createdAt: string;
}

export function makeIdempotencyKey(now = Date.now(), uuid = () => crypto.randomUUID()): string {
  return `ui-${now}-${uuid()}`;
}

export function newPending(mode: ExecutionMode, body: CreateBody, scope: RequestScope, key = makeIdempotencyKey()): PendingCreate {
  return { mode, key, body, bodyJson: JSON.stringify(body), scope, createdAt: new Date().toISOString() };
}

export type RetryCheck = { ok: true } | { ok: false; reason: string };

/** A pending create may only be replayed under the identical request scope. */
export function canSafelyRetry(pending: PendingCreate | null, current: RequestScope): RetryCheck {
  if (!pending) return { ok: false, reason: "没有待确认的创建请求。" };
  if (!sameScope(pending.scope, current)) {
    return { ok: false, reason: "身份或 API Origin 已变化，禁止向新的幂等作用域重放结果未知的创建请求。请恢复原连接。" };
  }
  return { ok: true };
}

export function loadPending(s = storages()): PendingCreate | null {
  try {
    const raw = s.session?.getItem(STORE.pending);
    const value = raw ? (JSON.parse(raw) as PendingCreate) : null;
    if (!value) return null;
    // Records written by V1 have the same fields; normalize missing bodyJson defensively.
    return { ...value, bodyJson: value.bodyJson || JSON.stringify(value.body) };
  } catch {
    return null;
  }
}

export function savePending(pending: PendingCreate | null, s = storages()) {
  try {
    if (pending) s.session?.setItem(STORE.pending, JSON.stringify(pending));
    else s.session?.removeItem(STORE.pending);
  } catch { /* ignore */ }
}
