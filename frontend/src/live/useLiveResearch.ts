// Live controller for the existing public API.
//
// Invariants
// - Runs are created only from explicit user actions (start / safeRetry), never from
//   effects, so remounts, view changes and reconnects cannot create duplicate runs.
// - One authoritative run state: the TanStack Query entry ["run", scopeKey, runId].
//   Snapshots and SSE events both pass through the pure reducer into that entry.
// - The cache is partitioned by identity scope (origin, tenant, user, credential
//   fingerprint). Changing identity clears the cache and detaches every stream.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { startEventStream } from "../api/eventStream";
import {
  cancelRun, createRun, getEvidenceView, getRun, issueDevToken, ping, runLegacy, webSearchConfigured,
  type ApiContext, type PingResult,
} from "../api/endpoints";
import { apiOrigin, friendlyError, isUnknownCreateOutcome, validatedBase, type ApiError } from "../api/http";
import {
  loadIdentity, loadSavedRun, loadRecent, rememberRecent, requestScope, saveIdentity, saveRun, scopeKey,
  type Identity, type RecentRun,
} from "../api/identity";
import { canSafelyRetry, loadPending, newPending, savePending, type CreateBody, type PendingCreate } from "../api/pending";
import { applyEvent, applyView, emptyRun, isTerminal, mapLegacy, type RunState } from "../domain/runState";
import type { ExecutionMode, ToolName } from "../domain/types";
import type { EvidenceViewResult } from "../domain/evidenceView";

export type Connection = { state: "checking" } | { state: "online"; kind: PingResult["kind"] } | { state: "offline" };
export type StreamStatus =
  | { state: "idle" } | { state: "connected" } | { state: "done" }
  | { state: "reconnecting"; attempt: number; delayMs: number }
  | { state: "paused"; message: string } | { state: "fatal"; message: string };

interface Current { runId: string; mode: Exclude<ExecutionMode, "legacy">; question: string; tools: ToolName[] }
export type Notify = (message: string, tone?: "info" | "success" | "warning" | "error") => void;

const CREATE_TIMEOUT_MS = 20_000;
const LEGACY_TIMEOUT_MS = 45_000;

export function useLiveResearch(enabled: boolean, notify: Notify) {
  const queryClient = useQueryClient();
  const [{ identity: initialIdentity, rejectedCrossOrigin }] = useState(() => loadIdentity());
  const [identity, setIdentity] = useState<Identity>(initialIdentity);
  const origin = useMemo(() => { try { return apiOrigin(identity.baseUrl); } catch { return window.location.origin; } }, [identity.baseUrl]);
  const scope = useMemo(() => scopeKey(identity, origin), [identity, origin]);
  const ctx: ApiContext = useMemo(() => ({ origin, token: identity.token }), [origin, identity.token]);

  const [connection, setConnection] = useState<Connection>({ state: "checking" });
  const [webConfigured, setWebConfigured] = useState<boolean | null>(null);
  const [current, setCurrent] = useState<Current | null>(() => {
    const saved = loadSavedRun();
    return saved?.runId ? { runId: saved.runId, mode: saved.mode === "agent" ? "agent" : "workflow", question: saved.question || "", tools: [] } : null;
  });
  const [legacyRun, setLegacyRun] = useState<RunState | null>(null);
  const [pending, setPending] = useState<PendingCreate | null>(() => loadPending());
  const [unknownOutcome, setUnknownOutcome] = useState(() => !!loadPending());
  const [submitting, setSubmitting] = useState(false);
  const [stream, setStream] = useState<StreamStatus>({ state: "idle" });
  const [reconnects, setReconnects] = useState(0);
  const [paused, setPaused] = useState(false);
  const [recent, setRecent] = useState<RecentRun[]>(() => loadRecent());
  const [blocking, setBlocking] = useState<string | null>(null);
  const cursorRef = useRef("");

  useEffect(() => {
    if (enabled && rejectedCrossOrigin) notify("已清除旧版跨源 API Base 与会话 Token；请从目标服务页面重新连接。", "warning");
  }, [enabled, rejectedCrossOrigin, notify]);

  // ---- connection + capability (read-only, no cost) ----
  useEffect(() => {
    if (!enabled) return;
    const controller = new AbortController();
    setConnection({ state: "checking" });
    ping(ctx, controller.signal)
      .then((result) => { setConnection({ state: "online", kind: result.kind }); return webSearchConfigured(ctx, controller.signal); })
      .then((configured) => setWebConfigured(configured ?? null))
      .catch(() => { if (!controller.signal.aborted) setConnection({ state: "offline" }); });
    return () => controller.abort();
  }, [enabled, ctx]);

  // ---- the authoritative run entry ----
  const runKey = useMemo(() => ["run", scope, current?.runId ?? ""] as const, [scope, current?.runId]);
  const base = useCallback((): RunState => ({
    ...emptyRun(current?.question ?? "", current?.tools ?? [], current?.mode ?? "workflow"), runId: current?.runId ?? "", status: "QUEUED", stage: "QUEUED",
  }), [current]);

  const query = useQuery({
    queryKey: runKey,
    enabled: enabled && !!current?.runId && !!identity.token,
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    queryFn: async ({ signal }) => {
      const view = await getRun(ctx, current!.runId, signal);
      const previous = queryClient.getQueryData<RunState>(runKey) ?? base();
      return applyView(previous, view);
    },
  });
  const run: RunState | null = legacyRun ?? query.data ?? null;
  const runError = query.error ? friendlyError(query.error, "status") : null;
  cursorRef.current = query.data?.lastEventId ?? cursorRef.current;

  // ---- persistence of the resumable run (same format as V1) ----
  useEffect(() => {
    if (!enabled || !current) return;
    const data = query.data;
    saveRun({ mode: current.mode, runId: current.runId, sessionId: data?.sessionId, status: data?.status, stage: data?.stage,
      lastEventId: data?.lastEventId, question: current.question });
    if (data) {
      setRecent(rememberRecent({ mode: current.mode, runId: current.runId, sessionId: data.sessionId, status: data.status, question: current.question, updatedAt: Date.now() }));
    }
  }, [enabled, current, query.data]);

  // ---- event stream: attaches to the current run under the current identity ----
  const terminal = !!query.data && isTerminal(query.data.status);
  const refetchTimer = useRef<number | null>(null);
  useEffect(() => {
    if (!enabled || !current?.runId || !identity.token || !query.data || terminal || paused) {
      if (terminal) setStream({ state: "done" });
      return;
    }
    const handle = startEventStream(ctx, current.runId, {
      getCursor: () => cursorRef.current,
      onConnected: () => setStream({ state: "connected" }),
      onEvent: (event, cursorId) => {
        queryClient.setQueryData<RunState>(runKey, (prev) => applyEvent(prev ?? base(), event, cursorId || undefined));
        if (refetchTimer.current) window.clearTimeout(refetchTimer.current);
        refetchTimer.current = window.setTimeout(() => { void queryClient.refetchQueries({ queryKey: runKey, exact: true }); }, 300);
      },
      onParseError: () => notify("收到无法解析的事件数据", "error"),
      onReconnecting: (attempt, delayMs) => { setReconnects((n) => n + 1); setStream({ state: "reconnecting", attempt, delayMs }); },
      onFatal: (error) => { setStream({ state: "fatal", message: "无法自动恢复：" + friendlyError(error, "stream") }); },
      refresh: async () => {
        const result = await queryClient.fetchQuery({ queryKey: runKey, queryFn: async ({ signal }) => {
          const view = await getRun(ctx, current.runId, signal);
          return applyView(queryClient.getQueryData<RunState>(runKey) ?? base(), view);
        }, staleTime: 0 });
        return isTerminal(result.status);
      },
    });
    return () => { handle.stop(); if (refetchTimer.current) window.clearTimeout(refetchTimer.current); };
    // query.data is only a readiness gate here; re-attaching on every event would reset the stream.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [enabled, ctx, current?.runId, identity.token, !!query.data, terminal, paused, runKey]);

  // A terminal event can arrive before the snapshot that carries finalResponse. Tearing down the
  // stream cancels its debounced refetch, so fetch the authoritative snapshot explicitly here.
  const needsFinalSnapshot = terminal && !query.data?.finalResponse && !query.isFetching && !query.error;
  useEffect(() => {
    if (enabled && needsFinalSnapshot) void queryClient.refetchQueries({ queryKey: runKey, exact: true });
  }, [enabled, needsFinalSnapshot, queryClient, runKey]);

  // ---- evidence read API: autonomous runs only, partitioned by identity like the run itself ----
  const evidenceQuery = useQuery({
    queryKey: ["evidence", scope, current?.runId ?? ""],
    enabled: enabled && current?.mode === "agent" && !!identity.token && terminal,
    retry: false, staleTime: Infinity, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => getEvidenceView(ctx, current!.runId, signal),
  });
  const evidence: { result: EvidenceViewResult | null; loading: boolean } | null = current?.mode === "agent" ? {
    result: evidenceQuery.data ?? (evidenceQuery.error ? { state: "error", message: "无法读取证据记录：" + friendlyError(evidenceQuery.error, "status") } : null),
    loading: evidenceQuery.isFetching,
  } : null;

  // ---- identity ----
  const applyIdentity = useCallback((next: Identity): string | null => {
    let baseUrl: string;
    try { baseUrl = validatedBase(next.baseUrl); } catch (error) { return (error as Error).message; }
    const normalized: Identity = { ...next, baseUrl, tenantId: next.tenantId.trim() || "demo-tenant", userId: next.userId.trim() || "demo-user",
      token: next.token.trim().replace(/^Bearer\s+/i, "") };
    // A different identity must never see the previous identity's cached runs or streams.
    queryClient.clear();
    setStream({ state: "idle" });
    setIdentity(normalized);
    saveIdentity(normalized);
    return null;
  }, [queryClient]);

  const devToken = useCallback(async (tenantId: string, userId: string) => {
    try {
      const base = validatedBase(identity.baseUrl);
      return { token: await issueDevToken({ origin: base || window.location.origin }, tenantId, userId), error: null };
    } catch (error) {
      return { token: "", error: friendlyError(error, "dev-token") };
    }
  }, [identity.baseUrl]);

  // ---- creation ----
  const createWithPending = useCallback(async (record: PendingCreate, question: string, tools: ToolName[]) => {
    setSubmitting(true);
    setBlocking(null);
    const controller = new AbortController();
    const timer = window.setTimeout(() => controller.abort(), record.mode === "legacy" ? LEGACY_TIMEOUT_MS : CREATE_TIMEOUT_MS);
    try {
      if (record.mode === "legacy") {
        const data = await runLegacy(ctx, record.bodyJson, record.key, controller.signal);
        savePending(null); setPending(null); setUnknownOutcome(false);
        setCurrent(null); saveRun(null);
        setLegacyRun(mapLegacy(question, data));
        notify(data.finished === false ? "Single Agent 未完成" : "Single Agent 基线完成", data.finished === false ? "warning" : "success");
      } else {
        const accepted = await createRun(ctx, record.mode, record.bodyJson, record.key, controller.signal);
        savePending(null); setPending(null); setUnknownOutcome(false);
        setLegacyRun(null);
        setCurrent({ runId: accepted.runId, mode: record.mode, question, tools });
        queryClient.setQueryData<RunState>(["run", scope, accepted.runId], {
          ...emptyRun(question, tools, record.mode), runId: accepted.runId, sessionId: accepted.sessionId ?? "",
          status: accepted.status || "QUEUED", stage: accepted.stage || accepted.status || "QUEUED",
        });
        notify(accepted.replayed ? "已安全重放原创建结果，没有新建任务" : "研究任务已创建", "success");
      }
      return true;
    } catch (error) {
      if (isUnknownCreateOutcome(error, record.mode)) {
        setUnknownOutcome(true);
        notify(friendlyError(error, record.mode === "legacy" ? "legacy" : "workflow") + " 创建结果未知，请复用原请求安全重试。", "warning");
      } else {
        savePending(null); setPending(null); setUnknownOutcome(false);
        const message = friendlyError(error, "workflow");
        if ((error as ApiError).status === 503) setBlocking(message);
        notify(message, "error");
      }
      return false;
    } finally {
      window.clearTimeout(timer);
      setSubmitting(false);
    }
  }, [ctx, notify, queryClient, scope]);

  const start = useCallback(async (mode: ExecutionMode, question: string, tools: ToolName[], sessionId = "") => {
    if (submitting || pending) return { ok: false, reason: "已有待确认的创建请求。" };
    if (!identity.token) return { ok: false, reason: "needs-identity" };
    if (mode !== "legacy" && !tools.length) return { ok: false, reason: "请至少允许一个只读工具。" };
    if (mode !== "legacy" && tools.includes("web_search")) {
      const before = scope;
      const configured = await webSearchConfigured(ctx);
      setWebConfigured(configured);
      if (before !== scope) return { ok: false, reason: "连接或身份已变化，请重新提交。" };
      if (configured === false) return { ok: false, reason: "网页搜索尚未配置 Tavily 凭据。请配置后重试，或选择知识库检索。" };
    }
    const body: CreateBody = mode === "legacy" ? { question } : { question, requestedTools: tools };
    if (sessionId.trim()) body.sessionId = sessionId.trim();
    const record = newPending(mode, body, requestScope(identity, origin));
    // Persist before sending so a reload during an unknown outcome can only replay this exact request.
    savePending(record); setPending(record);
    setLegacyRun(null);
    if (mode !== "legacy") { saveRun(null); setCurrent(null); }
    if (mode === "legacy") setLegacyRun({ ...emptyRun(question, [], "legacy"), status: "WORKING", stage: "WORKING" });
    const ok = await createWithPending(record, question, tools);
    if (!ok && mode === "legacy") setLegacyRun(null);
    return { ok: true, reason: "" };
  }, [submitting, pending, identity, scope, ctx, origin, createWithPending]);

  const safeRetry = useCallback(async () => {
    const check = canSafelyRetry(pending, requestScope(identity, origin));
    if (!check.ok) return check.reason;
    await createWithPending(pending!, pending!.body.question, (pending!.body.requestedTools ?? []) as ToolName[]);
    return null;
  }, [pending, identity, origin, createWithPending]);

  const discardPending = useCallback(() => {
    savePending(null); setPending(null); setUnknownOutcome(false);
    notify("仅清除本地记录；原任务仍可能在服务端执行", "warning");
  }, [notify]);

  // ---- viewing existing runs (never creates a run) ----
  const openRun = useCallback((row: { runId: string; mode?: string; question?: string }) => {
    setLegacyRun(null);
    cursorRef.current = "";
    setCurrent({ runId: row.runId, mode: row.mode === "agent" ? "agent" : "workflow", question: row.question || "", tools: [] });
  }, []);

  const closeRun = useCallback(() => { setCurrent(null); setLegacyRun(null); saveRun(null); setStream({ state: "idle" }); }, []);

  // ---- cancellation is a server request; the stream keeps running until the server reports CANCELLED ----
  const [cancelling, setCancelling] = useState(false);
  const cancel = useCallback(async () => {
    if (!current?.runId) return;
    setCancelling(true);
    try {
      await cancelRun(ctx, current.runId);
      await queryClient.refetchQueries({ queryKey: runKey, exact: true });
      notify("取消请求已提交", "success");
    } catch (error) {
      notify(friendlyError(error, "cancel"), "error");
    } finally {
      setCancelling(false);
    }
  }, [current?.runId, ctx, queryClient, runKey, notify]);

  /** Disconnect drill: only the event connection is closed; resumes from the cursor after 1s. */
  const disconnectDrill = useCallback(() => {
    if (!current?.runId || terminal) return;
    setPaused(true);
    setStream({ state: "paused", message: "事件流已断开；任务仍在服务端运行，1 秒后携带 Last-Event-ID 自动恢复。" });
    window.setTimeout(() => setPaused(false), 1000);
  }, [current?.runId, terminal]);

  const reconnectNow = useCallback(() => { setPaused(true); window.setTimeout(() => setPaused(false), 0); }, []);

  return {
    identity, origin, ctx, scope, connection, webConfigured, applyIdentity, devToken,
    run, runError, loading: query.isFetching && !query.data, current, terminal,
    pending, unknownOutcome, submitting, blocking, start, safeRetry, discardPending,
    stream, reconnects, cancel, cancelling, disconnectDrill, reconnectNow, evidence,
    openRun, closeRun, recent,
  };
}

export type LiveResearch = ReturnType<typeof useLiveResearch>;
