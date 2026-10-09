import * as Tooltip from "@radix-ui/react-tooltip";
import { AnimatePresence, motion, MotionConfig } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { normalizeCitations } from "../domain/citations";
import { parseMarkdown, statementsFor } from "../domain/markdown";
import { parsePublication } from "../domain/publication";
import { applyEvent, emptyRun, isTerminal, type RunState } from "../domain/runState";
import { followUpRequest } from "../domain/tools";
import type { ExecutionMode } from "../domain/types";
import { DEMO_EVIDENCE_VIEW, DEMO_QUESTION, DEMO_TOOLS } from "../demo/fixtures";
import { snapshotRun, useDemoRun, type DemoOutcome } from "../demo/useDemoRun";
import { EntryView, type StartRequest } from "../features/composer/EntryView";
import { CompareView } from "../features/evidence/CompareView";
import { RecordedDisagreementView } from "../features/evidence/RecordedDisagreementView";
import { recordedDisagreements } from "../domain/evidenceView";
import { Inspector } from "../features/evidence/Inspector";
import { SourcesDialog } from "../features/evidence/SourcesDialog";
import { LearningNotesSection } from "../features/memory/LearningNotes";
import { ReportView } from "../features/report/ReportView";
import { RunningView } from "../features/running/RunningView";
import { IdentityDialog, RecentDialog, type RecentItem } from "../features/shell/Dialogs";
import { ArchiveView } from "../features/archive/ArchiveView";
import { PREVIEW_SNAPSHOTS, previewResume, previewSnapshotFromRun, simulateSave } from "../demo/memoryPreview";
import { recordKey, type ProgressSnapshot } from "../domain/progressMemory";
import { useNotebook, type LoadState, type SaveState } from "../live/useNotebook";
import { TopBar, type AppMode } from "../features/shell/TopBar";
import { STAGE_LABELS } from "../domain/eventText";
import { useLiveResearch, type Notify } from "../live/useLiveResearch";
import { Icon } from "../ui/Icon";
import { MemoryNote } from "../features/memory/MemoryNote";
import { SummaryDisclosure, type SummaryRead } from "../features/memory/SummaryDisclosure";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AutoSaveStatus, type AutoSaveRead } from "../features/memory/AutoSaveStatus";
import { getMemoryRecall, getProgressSave } from "../api/progress";
import { RecallDisclosure, type RecallRead } from "../features/memory/RecallDisclosure";
import type { RecallView } from "../domain/memoryRecall";
import type { ProgressSaveView } from "../domain/progressMemory";
import { getContextSummary } from "../api/endpoints";
import { friendlyError, type ApiError } from "../api/http";
import { parseContextSummary, type ContextSummary } from "../domain/contextSummary";
import type { Continuation } from "../domain/researchMemory";
import { useTheme } from "./theme";

type View = "entry" | "running" | "report" | "compare" | "disagreement" | "archive";
interface CompareState { a: number; b: number }
interface Toast { id: number; message: string; tone: "info" | "success" | "warning" | "error" }

const MODE_LABELS: Record<ExecutionMode, string> = { workflow: "Durable Workflow", agent: "自主研究（候选）", legacy: "Single Agent 基线" };

/** Live by default. Demo mode is explicit (`?demo`, or `?state=` deep links for review) and never touches the network. */
function initialState(): { appMode: AppMode; view: View; run: RunState; inspect: number | null; compare: CompareState | null; disagreement?: number; openRecord?: boolean } {
  const params = new URLSearchParams(window.location.search);
  const state = params.get("state");
  const demoMode = params.has("demo") || !!state;
  const done = () => snapshotRun("success", Infinity);
  const base = { appMode: (demoMode ? "demo" : "live") as AppMode, inspect: null, compare: null };
  switch (state) {
    case "running": return { ...base, view: "running", run: snapshotRun("success", 4500) };
    case "report": return { ...base, view: "report", run: done() };
    case "partial": return { ...base, view: "report", run: snapshotRun("partial", Infinity) };
    case "cancelled": {
      const run = snapshotRun("success", 4500);
      return { ...base, view: "report", run: applyEvent(run, { type: "CANCELLED", id: `${run.runId}:cancel` }, `${run.runId}:cancel`) };
    }
    case "inspect": return { ...base, view: "report", run: done(), inspect: 3 };
    case "compare": return { ...base, view: "compare", run: done(), compare: { a: 3, b: 2 } };
    case "compare-recorded": return { ...base, view: "disagreement", run: done(), disagreement: 0 };
    case "archive": return { ...base, view: "archive", run: emptyRun() };
    case "record": return { ...base, view: "archive", run: emptyRun(), openRecord: true };
    default: return { ...base, view: "entry", run: emptyRun() };
  }
}

function setUrlMode(mode: AppMode) {
  const url = new URL(window.location.href);
  url.searchParams.delete("state");
  if (mode === "demo") url.searchParams.set("demo", ""); else url.searchParams.delete("demo");
  window.history.replaceState(null, "", url.pathname + (url.searchParams.toString() ? "?" + url.searchParams.toString().replace(/=(&|$)/g, "$1") : "") + url.hash);
}

export function App() {
  const { theme, toggle } = useTheme();
  const [init] = useState(initialState);
  const [appMode, setAppMode] = useState<AppMode>(init.appMode);
  const demoMode = appMode === "demo";

  const [toasts, setToasts] = useState<Toast[]>([]);
  const notify: Notify = useCallback((message, tone = "info") => {
    const id = Date.now() + Math.random();
    setToasts((items) => [...items.slice(-2), { id, message, tone }]);
    window.setTimeout(() => setToasts((items) => items.filter((t) => t.id !== id)), 4200);
  }, []);

  const demo = useDemoRun(init.run);
  const live = useLiveResearch(!demoMode, notify);
  const run: RunState | null = demoMode ? demo.run : live.run;

  const [view, setView] = useState<View>(init.view);
  const [inspect, setInspect] = useState<number | null>(init.inspect);
  const [compare, setCompare] = useState<CompareState | null>(init.compare);
  const [inspecting, setInspecting] = useState(false);
  const [reportReady, setReportReady] = useState(false);
  const [dialog, setDialog] = useState<"sources" | "identity" | "recent" | null>(null);
  // Research archive (saved research progress). Demo mode keeps the explicitly selected synthetic
  // preview, separate from live data; live reads the progress-memory contract.
  const [previewItems, setPreviewItems] = useState<ProgressSnapshot[]>(PREVIEW_SNAPSHOTS);
  const [previewLoad, setPreviewLoad] = useState<LoadState>({ state: "idle" });
  const [previewSave, setPreviewSave] = useState<{ runId: string; save: SaveState }>({ runId: "", save: { state: "idle" } });
  // Live archive: list only while the archive is open; save eligibility from project discovery for the current run.
  const notebook = useNotebook({
    enabled: !demoMode, ctx: live.ctx, scope: live.scope, notebookOpen: view === "archive",
    run: live.run && live.run.runId ? { runId: live.run.runId, mode: live.run.mode } : null,
  });
  // Continue research: the project/session pair from an explicit load, carried to an explicit submit.
  const [continuation, setContinuation] = useState<Continuation | null>(null);
  const activeLoad: LoadState = demoMode ? previewLoad : notebook.load;
  const pairScope = demoMode ? "demo" : live.scope;
  // The pair stays valid only while the same load is current, in the same identity scope, with no deleted source.
  const activeContinuation = continuation && continuation.scope === pairScope && activeLoad.state === "loaded"
    && !activeLoad.sourceDeleted && !activeLoad.sourceCorrected && activeLoad.context.projectId === continuation.projectId
    && activeLoad.context.targetSessionId === continuation.sessionId ? continuation : null;
  const continueProject = (projectId: string) => {
    if (activeLoad.state !== "loaded" || activeLoad.sourceDeleted || activeLoad.sourceCorrected || activeLoad.context.projectId !== projectId) return;
    const { context } = activeLoad;
    setContinuation({
      scope: pairScope, projectId: context.projectId, sessionId: context.targetSessionId, preview: demoMode,
      goals: [...new Set(context.progress.map((p) => p.originalGoal))],
      unresolved: context.progress.flatMap((p) => p.unresolvedQuestions.map((u) => ({ goal: u.goal, gaps: u.gaps, criteria: u.criteria.map((c) => c.text) }))),
      nextSteps: [...new Set(context.progress.flatMap((p) => p.nextSteps))],
      corrections: context.progress.map((p) => p.userCorrection).filter(Boolean),
    });
    live.clearRejection();
    setInspect(null); setView("entry"); window.scrollTo({ top: 0 });
    window.setTimeout(() => document.getElementById("question")?.focus({ preventScroll: true }), 260);
  };
  const [demoRecent, setDemoRecent] = useState<Array<RecentItem & { run: RunState }>>([]);
  const [disagreementIndex, setDisagreementIndex] = useState(init.disagreement ?? 0);
  const [prefill, setPrefill] = useState<{ key: number; question: string }>({ key: 0, question: "" });
  const trigger = useRef<string | null>(null);
  // Where the question sat in the composer at submit time; consumed once by the running view.
  const [stageOrigin, setStageOrigin] = useState<{ left: number; top: number } | null>(null);
  const captureOrigin = () => {
    const rect = document.getElementById("question")?.getBoundingClientRect();
    setStageOrigin(rect ? { left: rect.left + 16, top: rect.top + 14 } : null);
  };
  const restore = useRef<{ scrollY: number; focusId: string | null; reopen: number | null } | null>(null);

  const response = run?.finalResponse ?? null;
  const citations = useMemo(() => normalizeCitations(response?.citations, response?.citationContract, response?.citationDetails), [response]);
  // The evidence publisher's known plain-text format is re-laid out per claim; anything else
  // (or any ambiguity) renders through the unchanged Markdown path.
  const blocks = useMemo(() => parsePublication(response?.answer ?? "", response?.citationContract, citations.length,
    { sourceUrls: citations.map((c) => c.url?.href ?? null), structuredClaims: response?.claims })
    ?? parseMarkdown(response?.answer ?? "", response?.citationContract, citations.length), [response, citations]);
  const terminal = !!run && isTerminal(run.status);
  const hasRun = !!run && (!!run.runId || run.mode === "legacy" || run.status !== "READY");

  // Live: a newly created or recovered run decides the view; viewing never creates a run.
  const shownRun = useRef<string>("");
  useEffect(() => {
    if (demoMode) return;
    const id = live.run ? (live.run.runId || "legacy:" + live.run.question) : "";
    if (id === shownRun.current) return;
    shownRun.current = id;
    // oxlint-disable-next-line react/set-state-in-effect
    if (!id) { if (view === "running" || view === "report" || view === "compare") setView("entry"); return; }
    setInspect(null); setCompare(null);
    setView(live.run && isTerminal(live.run.status) ? "report" : "running");
  }, [demoMode, live.run, view]);

  // Completion moves to the report unless the reader is inspecting the process record.
  useEffect(() => {
    if (view !== "running" || !terminal || !run) return;
    if (demoMode) {
      // oxlint-disable-next-line react/set-state-in-effect
      setDemoRecent((items) => items.some((i) => i.runId === run.runId) ? items
        : [{ runId: run.runId, question: run.question, status: STAGE_LABELS[run.status] ?? run.status, at: Date.now(), run }, ...items].slice(0, 5));
    }
    // oxlint-disable-next-line react/set-state-in-effect
    if (!inspecting) { setView("report"); window.scrollTo({ top: 0 }); }
  }, [view, terminal, inspecting, run, demoMode]);

  // Returning from comparison restores reading position, focus and the open source.
  useLayoutEffect(() => {
    if (!reportReady || !restore.current) return;
    const { scrollY, focusId, reopen } = restore.current;
    restore.current = null;
    window.scrollTo({ top: scrollY });
    if (reopen) { trigger.current = focusId; setInspect(reopen); }
    else if (focusId) document.getElementById(focusId)?.focus({ preventScroll: true });
  }, [reportReady]);

  const enterDemo = useCallback((outcome: DemoOutcome) => {
    captureOrigin();
    setUrlMode("demo");
    setAppMode("demo");
    setInspect(null); setCompare(null);
    demo.start(DEMO_QUESTION, DEMO_TOOLS, outcome);
    setView("running");
    window.scrollTo({ top: 0 });
  }, [demo]);

  const exitDemo = useCallback(() => {
    setUrlMode("live");
    demo.reset(emptyRun());
    setInspect(null); setCompare(null);
    shownRun.current = "";
    setAppMode("live");
    setView("entry");
  }, [demo]);

  const start = useCallback(async (request: StartRequest): Promise<string | null> => {
    setInspect(null); setCompare(null);
    captureOrigin();
    if (demoMode) {
      demo.start(request.question, request.tools, request.outcome);
      if (request.researchProjectId) setContinuation(null);   // preview only: nothing is sent
      setView("running");
      window.scrollTo({ top: 0 });
      return null;
    }
    if (!live.identity.token) { setDialog("identity"); return "请先在“连接与身份”中设置 Bearer Token。"; }
    const result = await live.start(request.mode, request.question, request.tools, request.sessionId, request.researchProjectId ?? "", request.memoryRecall ?? null);
    if (!result.ok && result.reason === "needs-identity") { setDialog("identity"); return "请先连接身份。"; }
    // Once a run exists the pair has been used; an unresolved create keeps it (the pending request holds it verbatim).
    if (request.researchProjectId && "accepted" in result && result.accepted) setContinuation(null);
    return result.ok ? null : result.reason;
  }, [demoMode, demo, live]);

  const [anchorTop, setAnchorTop] = useState<number | null>(null);
  const openCitation = useCallback((number: number, el: HTMLElement | null) => {
    const target = el ?? document.querySelector<HTMLElement>(`[data-cite="${number}"]`);
    trigger.current = target?.id ?? null;
    setAnchorTop(target ? target.getBoundingClientRect().top : null);
    setInspect(number);
  }, []);
  const closeInspector = useCallback(() => {
    setInspect(null);
    const id = trigger.current;
    window.requestAnimationFrame(() => { if (id) document.getElementById(id)?.focus({ preventScroll: true }); });
  }, []);
  const navigateInspector = useCallback((number: number) => {
    const el = document.querySelector<HTMLElement>(`[data-cite="${number}"]`);
    if (el) trigger.current = el.id;
    setInspect(number);
  }, []);
  const beginCompare = useCallback((other: number) => {
    if (inspect == null) return;
    restore.current = { scrollY: window.scrollY, focusId: trigger.current, reopen: inspect };
    setCompare({ a: inspect, b: other });
    setInspect(null);
    setView("compare");
  }, [inspect]);
  const endCompare = useCallback(() => {
    if (!restore.current && compare) restore.current = { scrollY: 0, focusId: null, reopen: compare.a };
    setView("report");
  }, [compare]);

  // Evidence record: live autonomous runs read the public evidence API; demo shows a labelled fixture.
  const evidence = demoMode ? { result: { state: "ok" as const, view: DEMO_EVIDENCE_VIEW }, loading: false } : live.evidence;
  const evidenceResult = evidence?.result ?? null;
  const disagreements = useMemo(() => evidenceResult?.state === "ok" ? recordedDisagreements(evidenceResult.view) : [], [evidenceResult]);
  const openDisagreement = useCallback((index: number) => {
    restore.current = { scrollY: window.scrollY, focusId: (document.activeElement as HTMLElement | null)?.id || null, reopen: null };
    setInspect(null);
    setDisagreementIndex(index);
    setView("disagreement");
  }, []);
  const endDisagreement = useCallback(() => {
    if (!restore.current) restore.current = { scrollY: 0, focusId: null, reopen: null };
    setView("report");
  }, []);

  const savePreview = () => {
    if (!run) return;
    const runId = run.runId;
    setPreviewSave({ runId, save: { state: "saving" } });
    const fail = new URLSearchParams(window.location.search).has("memoryFail");
    // Success is shown only after the (simulated) confirmation resolves.
    simulateSave(previewSnapshotFromRun(run), fail)
      .then((snapshot) => {
        setPreviewItems((rows) => [snapshot, ...rows.filter((r) => recordKey(r.projectId, r.sourceRunId) !== recordKey(snapshot.projectId, snapshot.sourceRunId))]);
        setPreviewSave({ runId, save: { state: "saved", snapshot } });
      })
      .catch(() => setPreviewSave({ runId, save: { state: "failed", message: "模拟保存失败，没有任何内容被保存。" } }));
  };

  const reportSave = () => {
    if (!run || !(run.status === "SUCCEEDED" || run.status === "INSUFFICIENT_EVIDENCE" || isTerminal(run.status))) return undefined;
    if (demoMode) {
      const save = previewSave.runId === run.runId ? previewSave.save : { state: "idle" as const };
      return { eligibility: "eligible" as const, reason: "", state: save.state, message: save.state === "failed" ? save.message : undefined,
        preview: true, onSave: savePreview, onOpenNotebook: openArchive };
    }
    const e = notebook.eligibility;
    return { eligibility: e.state, reason: e.reason, state: notebook.save.state,
      message: notebook.save.state === "failed" ? notebook.save.message : undefined, preview: false,
      onSave: () => { void notebook.onSave().then(() => queryClient.invalidateQueries({ queryKey: ["progress-save", live.scope] })); }, onOpenNotebook: openArchive };
  };

  // The archive is a full view; it remembers which research view it was opened from.
  const [archiveFrom, setArchiveFrom] = useState<View | null>(null);
  const openArchive = () => {
    if (view === "archive") return;
    setArchiveFrom(view === "entry" ? null : view);
    setInspect(null);
    setView("archive");
    window.scrollTo({ top: 0 });
  };
  const archiveBack = archiveFrom && hasRun && run ? {
    label: archiveFrom === "running" && !terminal ? "返回进行中的研究" : "返回报告",
    onClick: () => { setView(archiveFrom === "running" && !terminal ? "running" : "report"); window.scrollTo({ top: 0 }); },
  } : null;

  const goHome = () => {
    if (demoMode) demo.reset(emptyRun()); else live.closeRun();
    shownRun.current = "";
    setInspect(null); setCompare(null); setView("entry"); window.scrollTo({ top: 0 });
  };

  const retryQuestion = () => {
    const question = run?.question ?? "";
    goHome();
    setPrefill((p) => ({ key: p.key + 1, question }));
  };

  const followUp = (question: string) => {
    if (!run) return;
    if (demoMode) { void start({ question, tools: run.tools, mode: run.mode, outcome: "success", sessionId: "" }); return; }
    // Same mode, session and public tools as the report; never a default or an internal capability.
    const request = followUpRequest(run);
    if (!request.ok) { notify(request.reason, "warning"); return; }
    void live.start(request.mode, question, request.tools, request.sessionId).then((r) => { if (!r.ok && r.reason) notify(r.reason === "needs-identity" ? "请先连接身份。" : r.reason, "warning"); });
  };

  const recentItems: RecentItem[] = demoMode ? demoRecent
    : live.recent.map((r) => ({ runId: r.runId, question: r.question || r.runId, status: STAGE_LABELS[r.status ?? ""] ?? r.status ?? "未知", at: r.updatedAt }));

  const memoryRequest = !demoMode && run?.runId ? live.memoryRequestFor(run.runId) : null;
  const memoryNote = memoryRequest && run ? <MemoryNote request={memoryRequest} runId={run.runId} errorCode={run.errorCode} onReselect={openArchive} /> : null;

  // Memory M2: owned, read-only summary of the project context this agent run used. Read when the run
  // is shown, again once it is terminal and on each summary event; one retry; never creates anything.
  const summaryEvents = run?.events.filter((e) => e.type === "PROJECT_CONTEXT_SUMMARY").length ?? 0;
  const summaryQuery = useQuery<ContextSummary>({
    queryKey: ["context-summary", live.scope, run?.runId ?? "", terminal, summaryEvents],
    enabled: !demoMode && !!run?.runId && run.mode === "agent" && !!live.identity.token,
    retry: (count, error) => count < 1 && ((error as ApiError).status == null || (error as ApiError).status! >= 500),
    staleTime: Infinity, refetchOnWindowFocus: false,
    // Keep the last summary while re-reading the same run under the same identity only (no cross-scope leak).
    placeholderData: (previous, previousQuery) => previousQuery?.queryKey[1] === live.scope && previousQuery?.queryKey[2] === run?.runId ? previous : undefined,
    queryFn: async ({ signal }) => parseContextSummary(await getContextSummary(live.ctx, run!.runId, signal)),
  });
  const summaryRead: SummaryRead | null = demoMode || !run?.runId || run.mode !== "agent" ? null
    : summaryQuery.data ? { state: "ok", view: summaryQuery.data }
    : summaryQuery.error ? ((summaryQuery.error as ApiError).status === 404 ? { state: "absent" }
      : (summaryQuery.error as Error).name === "SummaryContractError" ? { state: "invalid", message: (summaryQuery.error as Error).message }
      : { state: "error", message: friendlyError(summaryQuery.error, "status") })
    : { state: "loading" };
  const summaryNode = summaryRead ? <SummaryDisclosure read={summaryRead} /> : null;

  // Memory M4: cross-question recall (read-only). Polled while the run is active (bounded), re-read
  // when it becomes terminal, and keyed on the notebook's memory epoch so that after a correction or
  // deletion old recalled content is never redisplayed from cache. A failed read counts as unavailable.
  const recallEpoch = notebook.memoryEpoch;
  const recallQuery = useQuery<RecallView>({
    queryKey: ["memory-recall", live.scope, run?.runId ?? "", terminal, recallEpoch],
    enabled: !demoMode && !!run?.runId && run.mode === "agent" && !!live.identity.token,
    retry: (count, error) => count < 1 && ((error as ApiError).status == null || (error as ApiError).status! >= 500),
    staleTime: Infinity, refetchOnWindowFocus: false,
    placeholderData: (previous, previousQuery) => previousQuery?.queryKey[1] === live.scope && previousQuery?.queryKey[2] === run?.runId
      && previousQuery?.queryKey[4] === recallEpoch ? previous : undefined,
    refetchInterval: (query) => !terminal && query.state.dataUpdateCount < 60 ? 3000 : false,
    queryFn: ({ signal }) => getMemoryRecall(live.ctx, run!.runId, signal),
  });
  const recallRead: RecallRead | null = demoMode || !run?.runId || run.mode !== "agent" ? null
    : recallQuery.error ? ((recallQuery.error as ApiError).status === 404 ? { state: "absent" }
      : { state: "unavailable", message: "无法读取（" + ((recallQuery.error as Error).name === "RecallContractError" ? (recallQuery.error as Error).message : friendlyError(recallQuery.error, "status")) + "），视为不可用。" })
    : recallQuery.data ? { state: "ok", view: recallQuery.data }
    : { state: "loading" };
  const summary = summaryNode || recallRead ? <>{summaryNode}{recallRead ? <RecallDisclosure read={recallRead} /> : null}</> : null;

  // Memory M3: automatic progress-save status (read-only; the page never starts an automatic save).
  // Polled every 2 s only while a terminal run is PENDING, at most 30 reads; re-read on each
  // RESEARCH_PROGRESS_AUTO_SAVE event. Scoped by identity like every other run read.
  const queryClient = useQueryClient();
  const autoSaveEvents = run?.events.filter((e) => e.type === "RESEARCH_PROGRESS_AUTO_SAVE").length ?? 0;
  const autoSaveKey = ["progress-save", live.scope, run?.runId ?? "", terminal, autoSaveEvents] as const;
  const autoSaveQuery = useQuery<ProgressSaveView>({
    queryKey: autoSaveKey,
    enabled: !demoMode && !!run?.runId && run.mode === "agent" && !!live.identity.token && view === "report",
    retry: (count, error) => count < 1 && ((error as ApiError).status == null || (error as ApiError).status! >= 500),
    staleTime: Infinity, refetchOnWindowFocus: false,
    placeholderData: (previous, previousQuery) => previousQuery?.queryKey[1] === live.scope && previousQuery?.queryKey[2] === run?.runId ? previous : undefined,
    refetchInterval: (query) => terminal && query.state.data?.status === "PENDING" && query.state.dataUpdateCount < 30 ? 2000 : false,
    queryFn: ({ signal }) => getProgressSave(live.ctx, run!.runId, signal),
  });
  const savedNow = autoSaveQuery.data?.status === "SAVED";
  useEffect(() => { if (savedNow) void queryClient.invalidateQueries({ queryKey: ["progress-list", live.scope] }); }, [savedNow, queryClient, live.scope]);
  const autoSaveRead: AutoSaveRead | null = demoMode || !run?.runId || run.mode !== "agent" ? null
    : autoSaveQuery.data ? { state: "ok", view: autoSaveQuery.data, exhausted: autoSaveQuery.data.status === "PENDING" && (queryClient.getQueryState(autoSaveKey)?.dataUpdateCount ?? 0) >= 30 }
    : autoSaveQuery.error ? ((autoSaveQuery.error as ApiError).status === 404 ? { state: "absent" } : { state: "error", message: friendlyError(autoSaveQuery.error, "status") })
    : { state: "loading" };
  const autoSave = autoSaveRead ? <AutoSaveStatus read={autoSaveRead} /> : null;

  const active = inspect != null ? citations[inspect - 1] ?? null : null;
  const compareA = compare ? citations[compare.a - 1] : null;
  const compareB = compare ? citations[compare.b - 1] : null;
  const modeLabel = MODE_LABELS[run?.mode ?? "workflow"];
  const identityLabel = live.identity.token ? `${live.identity.tenantId}:${live.identity.userId}` : null;

  // Live states that have no run to show yet.
  const liveBlocked = !demoMode && !!live.current && !live.run && view !== "archive";
  const unknown = !demoMode && live.unknownOutcome && live.pending ? {
    question: live.pending.body.question, idempotencyKey: live.pending.key,
    onRetry: () => { void live.safeRetry().then((reason) => { if (reason) { notify(reason, "error"); setDialog("identity"); } }); },
    onDiscard: live.discardPending,
  } : null;

  return (
    <MotionConfig reducedMotion="user">
      <Tooltip.Provider>
        <a className="skip-link" href="#main">跳到主要内容</a>
        <div className="app">
          <TopBar theme={theme} mode={appMode} connection={live.connection} identityLabel={identityLabel} onToggleTheme={toggle} onHome={goHome}
            onOpenRecent={() => setDialog("recent")} onOpenNotebook={openArchive} archiveActive={view === "archive"} onOpenIdentity={() => setDialog("identity")} onExitDemo={exitDemo} />
          <AnimatePresence mode="wait" initial={false}>
            <motion.main key={view + (liveBlocked ? "-blocked" : "")} id="main" className="page" tabIndex={-1}
              initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.2, ease: [0.2, 0.8, 0.2, 1] }} style={{ outline: "none" }}>
              {liveBlocked ? (
                <section className="run-wrap" aria-live="polite">
                  <div className="activity">
                    {!live.identity.token ? (
                      <p><strong>已恢复上次的运行记录。</strong>连接身份后才能读取状态并续传事件；页面不会为恢复显示而新建任务。</p>
                    ) : live.runError ? (
                      <p role="alert"><strong>无法读取该运行：</strong>{live.runError}</p>
                    ) : <p>正在读取运行状态…</p>}
                    <div className="flex flex-wrap gap-2">
                      {!live.identity.token ? <button type="button" className="btn btn-primary btn-sm" onClick={() => setDialog("identity")}>连接身份</button> : null}
                      <button type="button" className="btn btn-quiet btn-sm" onClick={goHome}>返回新研究</button>
                    </div>
                  </div>
                </section>
              ) : null}
              {!liveBlocked && (view === "entry" || (!hasRun && view !== "archive")) ? (
                <EntryView key={prefill.key} appMode={appMode} onStart={start} onExample={enterDemo} initialQuestion={prefill.question}
                  onOpenArchive={openArchive} onOpenRecent={() => setDialog("recent")} recentCount={recentItems.length}
                  continuation={activeContinuation} onClearContinuation={() => { setContinuation(null); live.clearRejection(); }}
                  rejection={demoMode ? null : live.rejection}
                  busy={!demoMode && live.submitting} webConfigured={demoMode ? null : live.webConfigured} unknown={unknown} blocking={demoMode ? null : live.blocking} />
              ) : null}
              {!liveBlocked && view === "running" && run && hasRun ? (
                <RunningView run={run} startedAt={demoMode ? demo.startedAt : null} modeLabel={modeLabel} completed={terminal} demo={demoMode}
                  onCancel={demoMode ? demo.cancel : () => { void live.cancel(); }} canCancel={run.mode !== "legacy"} cancelling={!demoMode && live.cancelling}
                  stream={demoMode ? undefined : live.stream} reconnects={live.reconnects}
                  onDisconnectDrill={demoMode ? undefined : live.disconnectDrill} onReconnectNow={demoMode ? undefined : live.reconnectNow}
                  onViewReport={() => { setView("report"); window.scrollTo({ top: 0 }); }} onInspectingChange={setInspecting} origin={stageOrigin}
                  memoryNote={memoryNote} summary={summary} />
              ) : null}
              {!liveBlocked && view === "report" && run && hasRun ? (
                <ReportView run={run} blocks={blocks} citations={citations} modeLabel={modeLabel} active={inspect} demo={demoMode}
                  lastEventId={run.lastEventId} onCite={openCitation} onOpenSources={() => setDialog("sources")} onNew={goHome}
                  onRetryQuestion={retryQuestion} onFollowUp={followUp} onReady={setReportReady}
                  evidence={evidence} onCompareDisagreement={openDisagreement}
                  save={reportSave()} memoryNote={memoryNote} summary={summary} autoSave={autoSave}
                  learning={demoMode || run.mode !== "agent" || !run.runId ? null
                    : <LearningNotesSection key={`${live.scope}\u0000${run.runId}`} ctx={live.ctx} scope={live.scope} runId={run.runId}
                        projectId={notebook.eligibility.state === "eligible" ? notebook.eligibility.projectId : null} />} />
              ) : null}
              {!liveBlocked && view === "compare" && compare && compareA && compareB ? (
                <CompareView statement={statementsFor(blocks, compare.a)[0] ?? null} a={compareA} b={compareB} all={citations} demo={demoMode}
                  onChangeB={(b) => setCompare({ ...compare, b })} onBack={endCompare} />
              ) : null}
              {view === "archive" ? (demoMode ? (
                <ArchiveView key="preview" mode="preview" needsIdentity={false} onOpenIdentity={() => setDialog("identity")}
                  items={previewItems} loading={false} error={null} candidateLimit={null} onRefresh={() => {}}
                  load={previewLoad} onLoad={(projectId) => setPreviewLoad({ state: "loaded", context: previewResume(projectId, previewItems), recovered: false })}
                  onClearLoaded={() => setPreviewLoad({ state: "idle" })}
                  onDelete={async (record) => {
                    const key = recordKey(record.projectId, record.sourceRunId);
                    setPreviewItems((rows) => rows.filter((r) => recordKey(r.projectId, r.sourceRunId) !== key));
                    setPreviewLoad((l) => l.state === "loaded" ? { ...l, context: { ...l.context, progress: l.context.progress.filter((p) => recordKey(p.projectId, p.sourceRunId) !== key) },
                      sourceDeleted: l.sourceDeleted || l.context.projectId === record.projectId } : l);
                    return true;
                  }} deletingKey={null} deleteError={null}
                  onOpenRecent={() => setDialog("recent")} back={archiveBack} initialOpen={init.openRecord} onContinue={continueProject}
                  onCorrect={async (record, note) => {
                    // Preview only: annotate the synthetic record in this page; a loaded copy becomes stale.
                    const key = recordKey(record.projectId, record.sourceRunId);
                    setPreviewItems((rows) => rows.map((r) => recordKey(r.projectId, r.sourceRunId) === key ? { ...r, userCorrection: note } : r));
                    setPreviewLoad((l) => l.state === "loaded" && l.context.projectId === record.projectId ? { ...l, sourceCorrected: true } : l);
                    return true;
                  }} />
              ) : (
                <ArchiveView key={"live:" + live.scope} mode="live" needsIdentity={!live.identity.token} onOpenIdentity={() => setDialog("identity")}
                  items={notebook.list.items} loading={notebook.list.loading} error={notebook.list.error} candidateLimit={notebook.list.candidateLimit}
                  onRefresh={notebook.list.refetch} load={notebook.load} onLoad={(projectId) => { void notebook.loadNewSession(projectId); }}
                  onClearLoaded={notebook.clearLoaded} onDelete={notebook.remove} deletingKey={notebook.deleting} deleteError={notebook.deleteError}
                  onOpenRecent={() => setDialog("recent")} back={archiveBack} onContinue={continueProject}
                  onCorrect={notebook.correct} correctingKey={notebook.correcting} correctError={notebook.correctError} />
              )) : null}
              {!liveBlocked && view === "disagreement" && disagreements[disagreementIndex] ? (
                <RecordedDisagreementView disagreement={disagreements[disagreementIndex]} demo={demoMode} onBack={endDisagreement} />
              ) : null}
            </motion.main>
          </AnimatePresence>

          <AnimatePresence>
            {view === "report" && reportReady && active ? (
              <Inspector key="inspector" citation={active} all={citations} statements={statementsFor(blocks, active.number)} demo={demoMode} anchorTop={anchorTop}
                onClose={closeInspector} onNavigate={navigateInspector} onCompare={beginCompare} />
            ) : null}
          </AnimatePresence>
        </div>

        <div className="toasts" aria-live="polite" aria-atomic="false">
          {toasts.map((t) => <div key={t.id} className="toast" data-tone={t.tone} role={t.tone === "error" ? "alert" : "status"}>
            <Icon name={t.tone === "error" ? "alert" : t.tone === "success" ? "check" : "spark"} size={16} />{t.message}</div>)}
        </div>

        <SourcesDialog demo={demoMode} open={dialog === "sources"} onOpenChange={(o) => setDialog(o ? "sources" : null)} citations={citations}
          onInspect={(n) => { setDialog(null); window.setTimeout(() => openCitation(n, null), 0); }} />
        <IdentityDialog open={dialog === "identity"} onOpenChange={(o) => setDialog(o ? "identity" : null)} mode={appMode}
          identity={live.identity} onApply={(next) => { const error = live.applyIdentity(next); if (!error) notify("身份已更新；已清除旧身份的缓存内容", "success"); return error; }}
          onDevToken={live.devToken} />
        <RecentDialog open={dialog === "recent"} onOpenChange={(o) => setDialog(o ? "recent" : null)} items={recentItems} demo={demoMode}
          onOpen={(item) => {
            setDialog(null);
            if (demoMode) { const found = demoRecent.find((r) => r.runId === item.runId); if (found) { demo.reset(found.run); setInspect(null); setView("report"); } return; }
            const row = live.recent.find((r) => r.runId === item.runId);
            if (row) live.openRun(row);
          }} />
      </Tooltip.Provider>
    </MotionConfig>
  );
}
