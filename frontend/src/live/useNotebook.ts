// Live research-notebook controller (research progress memory).
//
// - Everything is partitioned by identity scope; an identity change hides and clears it.
// - The list is fetched only when the notebook is opened; save eligibility comes from project
//   discovery for the current autonomous run (404 = unsupported, no client-side project).
// - Success is shown only after the server confirms. PUT may be retried by the user.
// - The new-session POST runs only on an explicit click, is guarded against double clicks and
//   is never retried automatically. A received session id is kept for GET-based recovery.
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ApiContext } from "../api/endpoints";
import { createResumeContext, deleteProgress, discoverProject, getResumeContext, listProgress, progressErrorText, saveProgress } from "../api/progress";
import { recordKey, type ProgressSnapshot, type ResumeContext } from "../domain/progressMemory";

const LOADED_STORE = "deepresearch.react.loadedProgressContext";

export type SaveState = { state: "idle" } | { state: "saving" } | { state: "saved"; snapshot: ProgressSnapshot } | { state: "failed"; message: string };
export type LoadState =
  | { state: "idle" }
  | { state: "loading"; projectId: string }
  | { state: "loaded"; context: ResumeContext; recovered: boolean;
      /** A saved snapshot of this project was deleted after loading: the pair must be reloaded before Continue. */
      sourceDeleted?: boolean }
  | { state: "failed"; projectId: string; message: string; maybeCreated: boolean };

interface StoredLoad { scope: string; projectId: string; targetSessionId: string }

function readStored(): StoredLoad | null {
  try { const raw = sessionStorage.getItem(LOADED_STORE); return raw ? (JSON.parse(raw) as StoredLoad) : null; } catch { return null; }
}
function writeStored(value: StoredLoad | null) {
  try { if (value) sessionStorage.setItem(LOADED_STORE, JSON.stringify(value)); else sessionStorage.removeItem(LOADED_STORE); } catch { /* ignore */ }
}

export function useNotebook({ enabled, ctx, scope, notebookOpen, run }: {
  enabled: boolean; ctx: ApiContext; scope: string; notebookOpen: boolean;
  run: { runId: string; mode: string } | null;
}) {
  const queryClient = useQueryClient();
  const hasToken = !!ctx.token;
  const listKey = ["progress-list", scope] as const;

  const list = useQuery({
    queryKey: listKey, enabled: enabled && hasToken && notebookOpen,
    retry: false, staleTime: 30_000, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => listProgress(ctx, signal),
  });

  const agentRun = run && run.mode === "agent" && run.runId ? run.runId : null;
  const discovery = useQuery({
    queryKey: ["progress-project", scope, agentRun ?? ""], enabled: enabled && hasToken && !!agentRun,
    retry: false, staleTime: Infinity, refetchOnWindowFocus: false,
    queryFn: ({ signal }) => discoverProject(ctx, agentRun!, signal),
  });

  // ---- save (per identity scope and run) ----
  const [saves, setSaves] = useState<Record<string, SaveState>>({});
  const saveKey = agentRun ? `${scope}\u0000${agentRun}` : "";
  const save = useCallback(async () => {
    if (!agentRun || !discovery.data) return;
    const key = `${scope}\u0000${agentRun}`;
    setSaves((s) => ({ ...s, [key]: { state: "saving" } }));
    try {
      const snapshot = await saveProgress(ctx, discovery.data.projectId, agentRun);
      setSaves((s) => ({ ...s, [key]: { state: "saved", snapshot } }));
      void queryClient.invalidateQueries({ queryKey: ["progress-list", scope] });
    } catch (error) {
      setSaves((s) => ({ ...s, [key]: { state: "failed", message: progressErrorText(error, "save") } }));
    }
  }, [agentRun, discovery.data, scope, ctx, queryClient]);

  // ---- load project context into a new session ----
  const [load, setLoad] = useState<LoadState & { scope?: string }>({ state: "idle" });
  const inFlight = useRef(false);
  const loadNewSession = useCallback(async (projectId: string) => {
    if (inFlight.current) return;               // duplicate clicks never create extra sessions
    inFlight.current = true;
    setLoad({ state: "loading", projectId, scope });
    try {
      const context = await createResumeContext(ctx, projectId);
      writeStored({ scope, projectId: context.projectId, targetSessionId: context.targetSessionId });
      setLoad({ state: "loaded", context, recovered: false, scope });
    } catch (error) {
      const status = (error as { status?: number | null })?.status ?? null;
      setLoad({ state: "failed", projectId, scope, message: progressErrorText(error, "load"), maybeCreated: status == null || status >= 500 });
    } finally {
      inFlight.current = false;
    }
  }, [ctx, scope]);

  // Recovery after reload uses GET with the stored session id — never POST. The effect re-runs only
  // when the identity scope or API context changes; an aborted run (unmount, StrictMode) simply retries.
  useEffect(() => {
    if (!enabled || !hasToken) return;
    const stored = readStored();
    if (!stored || stored.scope !== scope) return;
    const controller = new AbortController();
    getResumeContext(ctx, stored.projectId, stored.targetSessionId, controller.signal)
      .then((context) => setLoad({ state: "loaded", context, recovered: true, scope }))
      .catch((error) => { if (!controller.signal.aborted) { writeStored(null); setLoad({ state: "failed", projectId: stored.projectId, scope, message: progressErrorText(error, "load"), maybeCreated: false }); } });
    return () => controller.abort();
  }, [enabled, hasToken, scope, ctx]);

  const clearLoaded = useCallback(() => { writeStored(null); setLoad({ state: "idle" }); }, []);

  // ---- delete ----
  const [deleting, setDeleting] = useState<string | null>(null);
  const [deleteError, setDeleteError] = useState<{ key: string; message: string } | null>(null);
  const remove = useCallback(async (record: ProgressSnapshot) => {
    const key = recordKey(record.projectId, record.sourceRunId);
    setDeleting(key);
    setDeleteError(null);
    try {
      await deleteProgress(ctx, record.projectId, record.sourceRunId);   // deleted:false also means "gone"
      queryClient.setQueryData<{ items: ProgressSnapshot[]; candidateLimit: number | null }>(["progress-list", scope], (old) =>
        old ? { ...old, items: old.items.filter((i) => recordKey(i.projectId, i.sourceRunId) !== key) } : old);
      // Drop any loaded browser copy that references the deleted snapshot.
      setLoad((current) => current.state === "loaded"
        ? { ...current, context: { ...current.context, progress: current.context.progress.filter((p) => recordKey(p.projectId, p.sourceRunId) !== key) },
            sourceDeleted: current.sourceDeleted || current.context.projectId === record.projectId }
        : current);
      return true;
    } catch (error) {
      setDeleteError({ key, message: progressErrorText(error, "delete") });
      return false;
    } finally {
      setDeleting(null);
    }
  }, [ctx, queryClient, scope]);

  // Anything loaded under another identity is never shown.
  const visibleLoad: LoadState = load.scope && load.scope !== scope ? { state: "idle" } : load;

  return {
    list: { items: list.data?.items ?? [], candidateLimit: list.data?.candidateLimit ?? null, loading: list.isFetching && !list.data,
      error: list.error ? progressErrorText(list.error, "list") : null, refetch: () => void list.refetch() },
    eligibility: !agentRun ? { state: "unsupported" as const, reason: "只有自主研究运行支持保存研究进度；工作流与 Single Agent 结果暂不支持。" }
      : discovery.isFetching && !discovery.data ? { state: "checking" as const, reason: "正在确认该运行能否保存…" }
      : discovery.data ? { state: "eligible" as const, projectId: discovery.data.projectId, reason: "" }
      : { state: "unsupported" as const, reason: discovery.error ? progressErrorText(discovery.error, "discover") : "无法确认该运行能否保存。" },
    save: (saveKey && saves[saveKey]) || { state: "idle" as const }, onSave: save,
    load: visibleLoad, loadNewSession, clearLoaded,
    deleting, deleteError, remove,
  };
}

export type Notebook = ReturnType<typeof useNotebook>;
