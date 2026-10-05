import * as Tooltip from "@radix-ui/react-tooltip";
import { AnimatePresence, motion, MotionConfig } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { normalizeCitations } from "../domain/citations";
import { parseMarkdown, statementsFor } from "../domain/markdown";
import { applyEvent, emptyRun, isTerminal, type RunState } from "../domain/runState";
import type { ExecutionMode, ToolName } from "../domain/types";
import { DEMO_EVIDENCE_VIEW, DEMO_QUESTION, DEMO_TOOLS } from "../demo/fixtures";
import { snapshotRun, useDemoRun, type DemoOutcome } from "../demo/useDemoRun";
import { EntryView, type StartRequest } from "../features/composer/EntryView";
import { CompareView } from "../features/evidence/CompareView";
import { RecordedDisagreementView } from "../features/evidence/RecordedDisagreementView";
import { recordedDisagreements } from "../domain/evidenceView";
import { Inspector } from "../features/evidence/Inspector";
import { SourcesDialog } from "../features/evidence/SourcesDialog";
import { ReportView } from "../features/report/ReportView";
import { RunningView } from "../features/running/RunningView";
import { IdentityDialog, RecentDialog, type RecentItem } from "../features/shell/Dialogs";
import { NotebookDialog } from "../features/memory/Notebook";
import { PREVIEW_SNAPSHOTS, previewResume, previewSnapshotFromRun, simulateSave } from "../demo/memoryPreview";
import { recordKey, type ProgressSnapshot } from "../domain/progressMemory";
import { useNotebook, type LoadState, type SaveState } from "../live/useNotebook";
import { TopBar, type AppMode } from "../features/shell/TopBar";
import { STAGE_LABELS } from "../domain/eventText";
import { useLiveResearch, type Notify } from "../live/useLiveResearch";
import { Icon } from "../ui/Icon";
import { useTheme } from "./theme";

type View = "entry" | "running" | "report" | "compare" | "disagreement";
interface CompareState { a: number; b: number }
interface Toast { id: number; message: string; tone: "info" | "success" | "warning" | "error" }

const MODE_LABELS: Record<ExecutionMode, string> = { workflow: "Durable Workflow", agent: "自主研究（候选）", legacy: "Single Agent 基线" };

/** Live by default. Demo mode is explicit (`?demo`, or `?state=` deep links for review) and never touches the network. */
function initialState(): { appMode: AppMode; view: View; run: RunState; inspect: number | null; compare: CompareState | null; disagreement?: number } {
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
  const [dialog, setDialog] = useState<"sources" | "identity" | "recent" | "notebook" | null>(null);
  // Research notebook: preview only (backend progress-memory contract not delivered).
  // Demo mode keeps the explicitly selected synthetic notebook preview, separate from live data.
  const [previewItems, setPreviewItems] = useState<ProgressSnapshot[]>(PREVIEW_SNAPSHOTS);
  const [previewLoad, setPreviewLoad] = useState<LoadState>({ state: "idle" });
  const [previewSave, setPreviewSave] = useState<{ runId: string; save: SaveState }>({ runId: "", save: { state: "idle" } });
  // Live notebook: list only when opened; save eligibility from project discovery for the current run.
  const notebook = useNotebook({
    enabled: !demoMode, ctx: live.ctx, scope: live.scope, notebookOpen: dialog === "notebook",
    run: live.run && live.run.runId ? { runId: live.run.runId, mode: live.run.mode } : null,
  });
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
  const blocks = useMemo(() => parseMarkdown(response?.answer ?? "", response?.citationContract, citations.length), [response, citations.length]);
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
      setView("running");
      window.scrollTo({ top: 0 });
      return null;
    }
    if (!live.identity.token) { setDialog("identity"); return "请先在“连接与身份”中设置 Bearer Token。"; }
    const result = await live.start(request.mode, request.question, request.tools, request.sessionId);
    if (!result.ok && result.reason === "needs-identity") { setDialog("identity"); return "请先连接身份。"; }
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
        preview: true, onSave: savePreview, onOpenNotebook: () => setDialog("notebook") };
    }
    const e = notebook.eligibility;
    return { eligibility: e.state, reason: e.reason, state: notebook.save.state,
      message: notebook.save.state === "failed" ? notebook.save.message : undefined, preview: false,
      onSave: () => { void notebook.onSave(); }, onOpenNotebook: () => setDialog("notebook") };
  };

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
    const tools: ToolName[] = run.tools.length ? run.tools : ["kb_search"];
    void live.start(run.mode, question, tools, run.sessionId).then((r) => { if (!r.ok && r.reason) notify(r.reason === "needs-identity" ? "请先连接身份。" : r.reason, "warning"); });
  };

  const recentItems: RecentItem[] = demoMode ? demoRecent
    : live.recent.map((r) => ({ runId: r.runId, question: r.question || r.runId, status: STAGE_LABELS[r.status ?? ""] ?? r.status ?? "未知", at: r.updatedAt }));

  const active = inspect != null ? citations[inspect - 1] ?? null : null;
  const compareA = compare ? citations[compare.a - 1] : null;
  const compareB = compare ? citations[compare.b - 1] : null;
  const modeLabel = MODE_LABELS[run?.mode ?? "workflow"];
  const identityLabel = live.identity.token ? `${live.identity.tenantId}:${live.identity.userId}` : null;

  // Live states that have no run to show yet.
  const liveBlocked = !demoMode && !!live.current && !live.run;
  const unknown = !demoMode && live.unknownOutcome && live.pending ? {
    question: live.pending.body.question, idempotencyKey: live.pending.key,
    onRetry: () => { void live.safeRetry().then((reason) => { if (reason) { notify(reason, "error"); setDialog("identity"); } }); },
    onDiscard: live.discardPending,
  } : null;

  return (
    <MotionConfig reducedMotion="user">
      <Tooltip.Provider>
        <a className="skip-link" href="#main">跳到主要内容</a>
        <div className="atmosphere" aria-hidden="true"><span className="a" /><span className="b" /><span className="c" /></div>
        <div className="app">
          <TopBar theme={theme} mode={appMode} connection={live.connection} identityLabel={identityLabel} onToggleTheme={toggle} onHome={goHome}
            onOpenRecent={() => setDialog("recent")} onOpenNotebook={() => setDialog("notebook")} onOpenIdentity={() => setDialog("identity")} onExitDemo={exitDemo} />
          <AnimatePresence mode="wait" initial={false}>
            <motion.main key={view + (liveBlocked ? "-blocked" : "")} id="main" className="page" tabIndex={-1}
              initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.24, ease: [0.2, 0.8, 0.2, 1] }} style={{ outline: "none" }}>
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
              {!liveBlocked && (view === "entry" || !hasRun) ? (
                <EntryView key={prefill.key} appMode={appMode} onStart={start} onExample={enterDemo} initialQuestion={prefill.question}
                  busy={!demoMode && live.submitting} webConfigured={demoMode ? null : live.webConfigured} unknown={unknown} blocking={demoMode ? null : live.blocking} />
              ) : null}
              {!liveBlocked && view === "running" && run && hasRun ? (
                <RunningView run={run} startedAt={demoMode ? demo.startedAt : null} modeLabel={modeLabel} completed={terminal} demo={demoMode}
                  onCancel={demoMode ? demo.cancel : () => { void live.cancel(); }} canCancel={run.mode !== "legacy"} cancelling={!demoMode && live.cancelling}
                  stream={demoMode ? undefined : live.stream} reconnects={live.reconnects}
                  onDisconnectDrill={demoMode ? undefined : live.disconnectDrill} onReconnectNow={demoMode ? undefined : live.reconnectNow}
                  onViewReport={() => { setView("report"); window.scrollTo({ top: 0 }); }} onInspectingChange={setInspecting} origin={stageOrigin} />
              ) : null}
              {!liveBlocked && view === "report" && run && hasRun ? (
                <ReportView run={run} blocks={blocks} citations={citations} modeLabel={modeLabel} active={inspect} demo={demoMode}
                  lastEventId={run.lastEventId} onCite={openCitation} onOpenSources={() => setDialog("sources")} onNew={goHome}
                  onRetryQuestion={retryQuestion} onFollowUp={followUp} onReady={setReportReady}
                  evidence={evidence} onCompareDisagreement={openDisagreement}
                  save={reportSave()} />
              ) : null}
              {!liveBlocked && view === "compare" && compare && compareA && compareB ? (
                <CompareView statement={statementsFor(blocks, compare.a)[0] ?? null} a={compareA} b={compareB} all={citations} demo={demoMode}
                  onChangeB={(b) => setCompare({ ...compare, b })} onBack={endCompare} />
              ) : null}
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

        <SourcesDialog open={dialog === "sources"} onOpenChange={(o) => setDialog(o ? "sources" : null)} citations={citations}
          onInspect={(n) => { setDialog(null); window.setTimeout(() => openCitation(n, null), 0); }} />
        <IdentityDialog open={dialog === "identity"} onOpenChange={(o) => setDialog(o ? "identity" : null)} mode={appMode}
          identity={live.identity} onApply={(next) => { const error = live.applyIdentity(next); if (!error) notify("身份已更新；已清除旧身份的缓存内容", "success"); return error; }}
          onDevToken={live.devToken} />
        {demoMode ? (
          <NotebookDialog key="preview" mode="preview" open={dialog === "notebook"} onOpenChange={(o) => setDialog(o ? "notebook" : null)}
            needsIdentity={false} onOpenIdentity={() => setDialog("identity")}
            items={previewItems} loading={false} error={null} candidateLimit={null} onRefresh={() => {}}
            load={previewLoad} onLoad={(projectId) => setPreviewLoad({ state: "loaded", context: previewResume(projectId, previewItems), recovered: false })}
            onClearLoaded={() => setPreviewLoad({ state: "idle" })}
            onDelete={async (record) => {
              const key = recordKey(record.projectId, record.sourceRunId);
              setPreviewItems((rows) => rows.filter((r) => recordKey(r.projectId, r.sourceRunId) !== key));
              setPreviewLoad((l) => l.state === "loaded" ? { ...l, context: { ...l.context, progress: l.context.progress.filter((p) => recordKey(p.projectId, p.sourceRunId) !== key) } } : l);
              return true;
            }} deletingKey={null} deleteError={null} />
        ) : (
          <NotebookDialog key={"live:" + live.scope} mode="live" open={dialog === "notebook"} onOpenChange={(o) => setDialog(o ? "notebook" : null)}
            needsIdentity={!live.identity.token} onOpenIdentity={() => setDialog("identity")}
            items={notebook.list.items} loading={notebook.list.loading} error={notebook.list.error} candidateLimit={notebook.list.candidateLimit}
            onRefresh={notebook.list.refetch} load={notebook.load} onLoad={(projectId) => { void notebook.loadNewSession(projectId); }}
            onClearLoaded={notebook.clearLoaded} onDelete={notebook.remove} deletingKey={notebook.deleting} deleteError={notebook.deleteError} />
        )}
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
