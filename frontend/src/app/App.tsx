import * as Tooltip from "@radix-ui/react-tooltip";
import { AnimatePresence, motion, MotionConfig } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { normalizeCitations } from "../domain/citations";
import { parseMarkdown, statementsFor } from "../domain/markdown";
import { applyEvent, emptyRun, isTerminal, type RunState } from "../domain/runState";
import type { ExecutionMode } from "../domain/types";
import { snapshotRun, useDemoRun } from "../demo/useDemoRun";
import { EntryView, type StartRequest } from "../features/composer/EntryView";
import { CompareView } from "../features/evidence/CompareView";
import { Inspector } from "../features/evidence/Inspector";
import { SourcesDialog } from "../features/evidence/SourcesDialog";
import { ReportView } from "../features/report/ReportView";
import { RunningView } from "../features/running/RunningView";
import { IdentityDialog, RecentDialog, type RecentItem } from "../features/shell/Dialogs";
import { TopBar } from "../features/shell/TopBar";
import { useTheme } from "./theme";

type View = "entry" | "running" | "report" | "compare";
interface CompareState { a: number; b: number; recorded: boolean }

const MODE_LABELS: Record<ExecutionMode, string> = { workflow: "Durable Workflow", agent: "自主研究（候选）", legacy: "Single Agent 基线" };

/** Deterministic deep links for review and screenshots: ?state=… */
function initialState(): { view: View; run: RunState; inspect: number | null; compare: CompareState | null } {
  const state = new URLSearchParams(window.location.search).get("state");
  const done = () => snapshotRun("success", Infinity);
  switch (state) {
    case "running": return { view: "running", run: snapshotRun("success", 4500), inspect: null, compare: null };
    case "report": return { view: "report", run: done(), inspect: null, compare: null };
    case "partial": return { view: "report", run: snapshotRun("partial", Infinity), inspect: null, compare: null };
    case "cancelled": {
      const run = snapshotRun("success", 4500);
      return { view: "report", run: applyEvent(run, { type: "CANCELLED", id: `${run.runId}:cancel` }, `${run.runId}:cancel`), inspect: null, compare: null };
    }
    case "inspect": return { view: "report", run: done(), inspect: 3, compare: null };
    case "compare": return { view: "compare", run: done(), inspect: null, compare: { a: 3, b: 2, recorded: false } };
    case "compare-recorded": return { view: "compare", run: done(), inspect: null, compare: { a: 3, b: 2, recorded: true } };
    default: return { view: "entry", run: emptyRun(), inspect: null, compare: null };
  }
}

export function App() {
  const { theme, toggle } = useTheme();
  const [init] = useState(initialState);
  const demo = useDemoRun(init.run);
  const { run } = demo;
  const [view, setView] = useState<View>(init.view);
  const [mode, setMode] = useState<ExecutionMode>("workflow");
  const [inspect, setInspect] = useState<number | null>(init.inspect);
  const [compare, setCompare] = useState<CompareState | null>(init.compare);
  const [inspecting, setInspecting] = useState(false);
  const [reportReady, setReportReady] = useState(false);
  const [dialog, setDialog] = useState<"sources" | "identity" | "recent" | null>(null);
  const [recent, setRecent] = useState<Array<RecentItem & { run: RunState }>>([]);
  const trigger = useRef<string | null>(null);
  const restore = useRef<{ scrollY: number; focusId: string | null; reopen: number | null } | null>(null);

  const response = run.finalResponse;
  const citations = useMemo(() => normalizeCitations(response?.citations, response?.citationContract, response?.citationDetails), [response]);
  const blocks = useMemo(() => parseMarkdown(response?.answer ?? "", response?.citationContract, citations.length), [response, citations.length]);
  const terminal = isTerminal(run.status);

  // Completion moves to the report unless the reader is inspecting the process record.
  // This reacts to a timer-driven run transition (the live adapter's SSE in Milestone 2).
  useEffect(() => {
    if (view !== "running" || !terminal) return;
    // oxlint-disable-next-line react/set-state-in-effect
    setRecent((items) => items.some((i) => i.runId === run.runId) ? items
      : [{ runId: run.runId, question: run.question, status: run.status === "SUCCEEDED" ? "已完成" : run.status === "CANCELLED" ? "已取消" : "证据不足", at: Date.now(), run }, ...items].slice(0, 5));
    // oxlint-disable-next-line react/set-state-in-effect
    if (!inspecting) { setView("report"); window.scrollTo({ top: 0 }); }
  }, [view, terminal, inspecting, run]);

  // Returning from comparison restores the reading position, focus and the open source —
  // only after the report has actually mounted (view transitions finish the exit first).
  useLayoutEffect(() => {
    if (!reportReady || !restore.current) return;
    const { scrollY, focusId, reopen } = restore.current;
    restore.current = null;
    window.scrollTo({ top: scrollY });
    if (reopen) { trigger.current = focusId; setInspect(reopen); }
    else if (focusId) document.getElementById(focusId)?.focus({ preventScroll: true });
  }, [reportReady]);

  const start = (request: StartRequest) => {
    setMode(request.mode);
    setInspect(null);
    setCompare(null);
    demo.start(request.question, request.tools, request.outcome);
    setView("running");
    window.scrollTo({ top: 0 });
  };

  const openCitation = useCallback((number: number, el: HTMLElement | null) => {
    trigger.current = el?.id ?? document.querySelector<HTMLElement>(`[data-cite="${number}"]`)?.id ?? null;
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
    setCompare({ a: inspect, b: other, recorded: false });
    setInspect(null);
    setView("compare");
  }, [inspect]);

  const endCompare = useCallback(() => {
    if (!restore.current && compare) restore.current = { scrollY: 0, focusId: null, reopen: compare.a };
    setView("report");
  }, [compare]);

  const goHome = () => { demo.reset(emptyRun()); setInspect(null); setCompare(null); setView("entry"); window.scrollTo({ top: 0 }); };

  const active = inspect != null ? citations[inspect - 1] ?? null : null;
  const compareA = compare ? citations[compare.a - 1] : null;
  const compareB = compare ? citations[compare.b - 1] : null;

  return (
    <MotionConfig reducedMotion="user">
      <Tooltip.Provider>
        <a className="skip-link" href="#main">跳到主要内容</a>
        <div className="atmosphere" aria-hidden="true"><span className="a" /><span className="b" /><span className="c" /></div>
        <div className="app">
          <TopBar theme={theme} onToggleTheme={toggle} onHome={goHome}
            onOpenRecent={() => setDialog("recent")} onOpenIdentity={() => setDialog("identity")} />
          <AnimatePresence mode="wait" initial={false}>
            <motion.main key={view} id="main" className="page" tabIndex={-1}
              initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}
              transition={{ duration: 0.24, ease: [0.2, 0.8, 0.2, 1] }} style={{ outline: "none" }}>
              {view === "entry" ? <EntryView onStart={start} /> : null}
              {view === "running" ? (
                <RunningView run={run} startedAt={demo.startedAt} modeLabel={MODE_LABELS[mode]} completed={terminal}
                  onCancel={demo.cancel} onViewReport={() => { setView("report"); window.scrollTo({ top: 0 }); }} onInspectingChange={setInspecting} />
              ) : null}
              {view === "report" ? (
                <ReportView run={run} blocks={blocks} citations={citations} modeLabel={MODE_LABELS[mode]} active={inspect}
                  onCite={openCitation} onOpenSources={() => setDialog("sources")} onNew={goHome}
                  onFollowUp={(question) => start({ question, tools: run.tools, mode, outcome: "success" })} onReady={setReportReady} />
              ) : null}
              {view === "compare" && compare && compareA && compareB ? (
                <CompareView statement={statementsFor(blocks, compare.a)[0] ?? null} a={compareA} b={compareB} all={citations}
                  recorded={compare.recorded} onChangeB={(b) => setCompare({ ...compare, b })} onBack={endCompare} />
              ) : null}
            </motion.main>
          </AnimatePresence>

          <AnimatePresence>
            {view === "report" && reportReady && active ? (
              <Inspector key="inspector" citation={active} all={citations} statements={statementsFor(blocks, active.number)}
                onClose={closeInspector} onNavigate={navigateInspector} onCompare={beginCompare} />
            ) : null}
          </AnimatePresence>
        </div>

        <SourcesDialog open={dialog === "sources"} onOpenChange={(o) => setDialog(o ? "sources" : null)} citations={citations}
          onInspect={(n) => { setDialog(null); window.setTimeout(() => openCitation(n, null), 0); }} />
        <IdentityDialog open={dialog === "identity"} onOpenChange={(o) => setDialog(o ? "identity" : null)} />
        <RecentDialog open={dialog === "recent"} onOpenChange={(o) => setDialog(o ? "recent" : null)} items={recent}
          onOpen={(item) => { const found = recent.find((r) => r.runId === item.runId); setDialog(null); if (found) { demo.reset(found.run); setInspect(null); setView("report"); } }} />
      </Tooltip.Provider>
    </MotionConfig>
  );
}
