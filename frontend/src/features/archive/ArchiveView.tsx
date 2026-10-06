// Research archive: saved research-progress records as a spatial folio array plus an accessible
// list. The list is the keyboard / screen-reader path; the stage mirrors it for pointer users.
//
// Contract limits respected here: records are identified by (project_id, source_run_id); the list
// holds at most `candidateLimit` candidates; there is no saved date, folder or pagination, so
// none is shown, and an empty list never claims that no older records exist.
//
// Motion: browse → hover (150ms) → select / lift (260ms) → open / extraction (520ms) → return
// (380ms). State changes happen immediately; animations only decorate them, retarget on rapid
// input, and are cancelled on unmount. Reduced motion removes all travel and keeps a short fade.
import { AnimatePresence, usePresence, useReducedMotion } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from "react";
import { recordKey, type ProgressSnapshot } from "../../domain/progressMemory";
import type { LoadState } from "../../live/useNotebook";
import { Icon } from "../../ui/Icon";
import { LoadedPanel, RecordActions, RecordSections } from "./RecordParts";
import { shortId, statusLabel, statusTone } from "./recordText";

export interface ArchiveProps {
  /** "live" shows only server data; "preview" is the explicitly selected synthetic demo. */
  mode: "live" | "preview";
  needsIdentity: boolean; onOpenIdentity: () => void;
  items: ProgressSnapshot[]; loading: boolean; error: string | null; candidateLimit: number | null; onRefresh: () => void;
  load: LoadState; onLoad: (projectId: string) => void; onClearLoaded: () => void;
  onDelete: (record: ProgressSnapshot) => Promise<boolean>; deletingKey: string | null; deleteError: { key: string; message: string } | null;
  onOpenRecent: () => void;
  /** Where the reader came from (e.g. a report), if anywhere. */
  back?: { label: string; onClick: () => void } | null;
  /** Review deep link: open the first record on arrival (no flight, nothing to fly from). */
  initialOpen?: boolean;
}

const pad = (n: number) => String(n).padStart(2, "0");
const keyOf = (r: ProgressSnapshot) => recordKey(r.projectId, r.sourceRunId);
const canvas = () => getComputedStyle(document.documentElement).getPropertyValue("--canvas").trim() || "transparent";
const COLS = 4;

function matches(record: ProgressSnapshot, query: string, status: string) {
  if (status !== "all" && record.runStatus !== status) return false;
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const text = [record.originalGoal, record.sourceRunId, record.projectId, ...record.nextSteps,
    ...record.completedWork.map((g) => g.goal), ...record.unresolvedQuestions.map((g) => g.goal)].join("\n").toLowerCase();
  return text.includes(q);
}

export function ArchiveView(props: ArchiveProps) {
  const { mode, needsIdentity, items, loading, error, candidateLimit, load } = props;
  const reduce = useReducedMotion();
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const [selected, setSelected] = useState<string | null>(null);
  const [hover, setHover] = useState<string | null>(null);
  const [open, setOpen] = useState<{ record: ProgressSnapshot; index: number; origin: DOMRect | null } | null>(null);
  const folios = useRef(new Map<string, HTMLElement>());
  const options = useRef(new Map<string, HTMLElement>());

  const statuses = useMemo(() => [...new Set(items.map((r) => r.runStatus))], [items]);
  const shown = useMemo(() => items.filter((r) => matches(r, query, status)), [items, query, status]);
  const shownKeys = useMemo(() => new Set(shown.map(keyOf)), [shown]);
  // Selection falls back to the first visible record; it is derived, so filtering never leaves it stale.
  const current = selected && shownKeys.has(selected) ? selected : shown[0] ? keyOf(shown[0]) : null;
  const currentIndex = current ? items.findIndex((r) => keyOf(r) === current) : -1;
  const currentRecord = currentIndex >= 0 ? items[currentIndex] : null;
  const slots = Math.max(candidateLimit ?? 20, items.length, COLS);

  const openRecord = useCallback((key: string, withFlight = true) => {
    const index = items.findIndex((r) => keyOf(r) === key);
    if (index < 0) return;
    setSelected(key);
    const el = folios.current.get(key);
    setOpen({ record: items[index], index, origin: withFlight && el ? el.getBoundingClientRect() : null });
  }, [items]);

  const opened = useRef(false);
  useEffect(() => {
    if (!props.initialOpen || opened.current || !items[0]) return;
    opened.current = true;
    // oxlint-disable-next-line react/set-state-in-effect
    openRecord(keyOf(items[0]), false);
  }, [props.initialOpen, items, openRecord]);

  const focusOption = (key: string | null) => window.requestAnimationFrame(() => {
    if (key && options.current.get(key)) options.current.get(key)?.focus({ preventScroll: true });
    else {
      // No record to return to (e.g. the last one was deleted): search if usable, else the archive heading.
      const search = document.getElementById("archive-search") as HTMLInputElement | null;
      (search && !search.disabled ? search : document.getElementById("archive-title"))?.focus({ preventScroll: true });
    }
  });
  const closeRecord = useCallback((nextKey?: string | null) => {
    const key = nextKey === undefined ? open && keyOf(open.record) : nextKey;
    setOpen(null);
    if (key) setSelected(key);
    focusOption(key ?? null);
  }, [open]);

  const select = (key: string) => setSelected(key);
  const activate = (key: string) => { if (key === current) openRecord(key); else select(key); };

  const onListKey = (event: KeyboardEvent<HTMLUListElement>) => {
    if (!shown.length) return;
    const at = Math.max(0, shown.findIndex((r) => keyOf(r) === current));
    let next = at;
    if (event.key === "ArrowDown" || event.key === "ArrowRight") next = Math.min(shown.length - 1, at + 1);
    else if (event.key === "ArrowUp" || event.key === "ArrowLeft") next = Math.max(0, at - 1);
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = shown.length - 1;
    else if (event.key === "Enter") { event.preventDefault(); if (current) openRecord(current); return; }
    else return;
    event.preventDefault();
    const key = keyOf(shown[next]);
    setSelected(key);
    options.current.get(key)?.focus();
  };

  const remove = async (record: ProgressSnapshot) => {
    const key = keyOf(record);
    const at = items.findIndex((r) => keyOf(r) === key);
    const neighbour = items[at + 1] ?? items[at - 1] ?? null;
    if (await props.onDelete(record)) closeRecord(neighbour ? keyOf(neighbour) : null);
  };

  const emptyText = mode === "live"
    ? `最近 ${candidateLimit ?? 20} 条候选中没有可访问的研究进度；这不代表没有更早的记录。`
    : "预览中没有保存的进度。";

  let listState: ReactNode = null;
  if (needsIdentity) listState = <div className="archive-state"><p>需要先连接身份才能读取研究档案。</p><button type="button" className="btn btn-quiet btn-sm" onClick={props.onOpenIdentity}>连接身份</button></div>;
  else if (loading) listState = <p className="archive-state note" role="status">正在读取…</p>;
  else if (error) listState = <div className="archive-state"><p className="missing" role="alert">{error}</p><button type="button" className="btn btn-quiet btn-sm" onClick={props.onRefresh}>重试</button></div>;
  else if (!items.length) listState = <div className="archive-state"><p>{emptyText}</p><p className="note">在自主研究的报告中选择“保存研究进度”后，记录会出现在这里。</p></div>;
  else if (!shown.length) listState = <p className="archive-state note" role="status">没有符合筛选条件的记录。</p>;

  return (
    <section className="archive" aria-labelledby="archive-title">
      <div className="archive-browse" inert={!!open}>
        <header className="archive-head">
          {props.back ? <button type="button" className="link-btn archive-back" onClick={props.back.onClick}><Icon name="arrowLeft" size={15} />{props.back.label}</button> : null}
          <p className="eyebrow">ARCHIVE · 研究档案</p>
          <h1 id="archive-title" className="archive-title" tabIndex={-1}>研究档案</h1>
          <p className="archive-intro">保存的研究进度是不可信的历史上下文，不是新核验的证据；载入不会自动开始研究，也尚未传入模型。</p>
          <div className="flex flex-wrap items-center gap-2" style={{ marginTop: 12 }}>
            {mode === "preview" ? <span className="chip chip-warn">预览 · 示例数据，不联网</span> : <span className="chip chip-accent">服务端保存的研究进度</span>}
            <span className="note">与本机的“最近运行”分开。</span>
            <button type="button" className="link-btn" onClick={props.onOpenRecent}>打开最近运行（本机）</button>
          </div>
        </header>
        {/* While a record is open its layer shows the loaded context; the hidden archive does not repeat it. */}
        {load.state !== "idle" && !open ? <div className="archive-loaded"><LoadedPanel load={load} onClear={props.onClearLoaded} onRetry={props.onLoad} /></div> : null}

        <div className="archive-grid">
          <div className="archive-controls">
            <label className="field-row" htmlFor="archive-search">
              <span>搜索原始问题、目标与下一步</span>
              <input id="archive-search" type="search" className="text-input" value={query} onChange={(e) => setQuery(e.target.value)}
                placeholder="输入关键词" autoComplete="off" disabled={needsIdentity || !items.length} />
            </label>
            {statuses.length > 1 ? (
              <div className="archive-filter" role="group" aria-label="按保存时的运行状态筛选">
                <button type="button" className="filter-tab" aria-pressed={status === "all"} onClick={() => setStatus("all")}>全部 <span>{items.length}</span></button>
                {statuses.map((s) => (
                  <button key={s} type="button" className="filter-tab" aria-pressed={status === s} onClick={() => setStatus(s)}>
                    {statusLabel(s)} <span>{items.filter((r) => r.runStatus === s).length}</span>
                  </button>
                ))}
              </div>
            ) : null}
          </div>

          <div className="archive-list-wrap">
            {listState ?? (
              <>
                <p id="archive-list-hint" className="note">↑ ↓ 选择 · Enter 打开 · 点击已选中的记录也会打开</p>
                <ul className="archive-list" role="listbox" aria-label="研究档案列表" aria-describedby="archive-list-hint" onKeyDown={onListKey}>
                  {shown.map((r) => {
                    const key = keyOf(r);
                    const index = items.indexOf(r);
                    const isCurrent = key === current;
                    return (
                      <li key={key} ref={(el) => { if (el) options.current.set(key, el); else options.current.delete(key); }}
                        role="option" aria-selected={isCurrent} tabIndex={isCurrent ? 0 : -1} className="archive-item"
                        data-tone={statusTone(r.runStatus)} onClick={() => activate(key)}
                        onMouseEnter={() => setHover(key)} onMouseLeave={() => setHover((h) => (h === key ? null : h))}>
                        <span className="archive-item-n" aria-hidden="true">{pad(index + 1)}</span>
                        <span className="archive-item-body">
                          <strong>{r.originalGoal}</strong>
                          <span>保存时：{statusLabel(r.runStatus)} · 未解决 {r.unresolvedQuestions.length} 项 · 已完成 {r.completedWork.length} 项</span>
                        </span>
                      </li>
                    );
                  })}
                </ul>
                {mode === "live" && candidateLimit ? <p className="note" style={{ marginTop: 10 }}>只显示最近 {candidateLimit} 条候选中仍可访问的记录。</p> : null}
              </>
            )}
          </div>

          <FolioStage items={items} slots={slots} shownKeys={shownKeys} current={current} hover={hover} reduce={!!reduce}
            busy={loading} register={(key, el) => { if (el) folios.current.set(key, el); else folios.current.delete(key); }}
            onHover={setHover} onActivate={activate} onOpen={(key) => openRecord(key)} preview={mode === "preview"} />

          <aside className="archive-hud" aria-label="选中的记录" data-empty={!currentRecord}>
            {currentRecord ? (
              <>
                <p className="hud-index"><span>No.</span>{pad(currentIndex + 1)}<span className="hud-of">/ {pad(slots)}</span></p>
                <p className="hud-title">{currentRecord.originalGoal}</p>
                <div className="hud-meta">
                  <span className="chip" data-tone={statusTone(currentRecord.runStatus)} title="保存时的运行状态，不是实时状态">保存时：{statusLabel(currentRecord.runStatus)}</span>
                  <span className="source-id">项目 {shortId(currentRecord.projectId)}<br />运行 {shortId(currentRecord.sourceRunId)}</span>
                </div>
                <dl className="hud-counts">
                  <div><dt>已完成</dt><dd>{currentRecord.completedWork.length}</dd></div>
                  <div><dt>未解决</dt><dd>{currentRecord.unresolvedQuestions.length}</dd></div>
                  <div><dt>下一步</dt><dd>{currentRecord.nextSteps.length}</dd></div>
                </dl>
                <button type="button" className="btn btn-outline" onClick={() => current && openRecord(current)}>
                  打开档案<Icon name="arrowUpRight" size={16} />
                </button>
              </>
            ) : <p className="note">没有选中的记录。</p>}
          </aside>
        </div>
      </div>

      <AnimatePresence>
        {open ? (
          <RecordLayer key={keyOf(open.record)} record={open.record} index={open.index} slots={slots} origin={open.origin} reduce={!!reduce}
            preview={mode === "preview"} getTarget={() => folios.current.get(keyOf(open.record))?.getBoundingClientRect() ?? null}
            onClose={() => closeRecord()} load={load} onLoad={props.onLoad}
            onDelete={() => { void remove(open.record); }}
            deleting={props.deletingKey === keyOf(open.record)}
            deleteError={props.deleteError?.key === keyOf(open.record) ? props.deleteError.message : null}
            onClearLoaded={props.onClearLoaded} />
        ) : null}
      </AnimatePresence>
    </section>
  );
}

/** Decorative spatial mirror of the list (aria-hidden). CSS-perspective prototype; a Three.js scene is planned. */
function FolioStage({ items, slots, shownKeys, current, hover, reduce, busy, register, onHover, onActivate, onOpen, preview }: {
  items: ProgressSnapshot[]; slots: number; shownKeys: Set<string>; current: string | null; hover: string | null; reduce: boolean; busy: boolean;
  register: (key: string, el: HTMLElement | null) => void; onHover: (key: string | null) => void;
  onActivate: (key: string) => void; onOpen: (key: string) => void; preview: boolean;
}) {
  const rows = Math.ceil(slots / COLS);
  return (
    <div className="archive-stage" data-reduce={reduce} aria-busy={busy}>
      <div className="stage-frame" aria-hidden="true">
        <span className="stage-corner tl">SPATIAL VIEW</span>
        <span className="stage-corner tr">{preview ? "示例数据" : "服务端记录"}</span>
        <span className="stage-corner bl">{items.length} / {slots} 候选位</span>
        <span className="stage-corner br">CSS 原型</span>
        <div className="stage-plane" style={{ gridTemplateRows: `repeat(${rows}, auto)` }}>
          {Array.from({ length: COLS }, (_, c) => <span key={"c" + c} className="plane-tick col" style={{ left: `calc(${c} * (var(--fw) + var(--fg)) + var(--fw) / 2)` }}>{String.fromCharCode(65 + c)}</span>)}
          {Array.from({ length: rows }, (_, r) => <span key={"r" + r} className="plane-tick row" style={{ top: `calc(${r} * (var(--fw) * .72 + var(--fg)) + var(--fw) * .36)` }}>{r + 1}</span>)}
          {Array.from({ length: slots }, (_, i) => {
            const r = items[i];
            if (!r) return <div key={"empty" + i} className="folio folio-empty" style={{ ["--i" as string]: i }} />;
            const key = recordKey(r.projectId, r.sourceRunId);
            return (
              <div key={key} ref={(el) => register(key, el)} className="folio" style={{ ["--i" as string]: i }}
                data-tone={statusTone(r.runStatus)} data-selected={key === current} data-hover={key === hover} data-match={shownKeys.has(key)}
                onClick={() => shownKeys.has(key) && onActivate(key)} onDoubleClick={() => shownKeys.has(key) && onOpen(key)}
                onMouseEnter={() => onHover(key)} onMouseLeave={() => onHover(null)}>
                <span className="folio-n">{pad(i + 1)}</span>
                <span className="folio-status">{statusLabel(r.runStatus)}</span>
                <span className="folio-goal">{r.originalGoal}</span>
              </div>
            );
          })}
        </div>
      </div>
      <p className="stage-caption">空间视图是 CSS 透视原型（Three.js 场景尚在计划中）。键盘与屏幕阅读器请使用档案列表；空位表示尚未使用的候选位。</p>
    </div>
  );
}

const EASE_LIFT = "cubic-bezier(.3,.7,.1,1)";
const EASE_OUT = "cubic-bezier(.2,.8,.2,1)";

/** Reading layer. The cover is extracted from the folio's on-screen rect and returned to it on close. */
function RecordLayer({ record, index, slots, origin, reduce, preview, getTarget, onClose, load, onLoad, onDelete, deleting, deleteError, onClearLoaded }: {
  record: ProgressSnapshot; index: number; slots: number; origin: DOMRect | null; reduce: boolean; preview: boolean;
  getTarget: () => DOMRect | null; onClose: () => void;
  load: LoadState; onLoad: (projectId: string) => void; onDelete: () => void; deleting: boolean; deleteError: string | null; onClearLoaded: () => void;
}) {
  const [isPresent, safeToRemove] = usePresence();
  const root = useRef<HTMLElement>(null);
  const cover = useRef<HTMLDivElement>(null);
  const body = useRef<HTMLElement>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const running = useRef<Animation[]>([]);
  const stop = () => { running.current.forEach((a) => a.cancel()); running.current = []; };
  const play = (el: Element | null, frames: Keyframe[], options: KeyframeAnimationOptions) => {
    if (!el || !el.animate) return null;
    const a = el.animate(frames, options);
    running.current.push(a);
    return a;
  };
  const flight = (from: DOMRect, to: DOMRect) =>
    `perspective(900px) translate(${from.left - to.left}px, ${from.top - to.top}px) scale(${from.width / to.width}, ${from.height / to.height}) rotateX(34deg)`;
  const rest = "perspective(900px) translate(0px, 0px) scale(1, 1) rotateX(0deg)";

  // Open: the layer is already in its final state; motion plays from the folio towards the reader.
  useLayoutEffect(() => {
    title.current?.focus({ preventScroll: true });
    if (reduce || !origin) {
      play(root.current, [{ opacity: 0 }, { opacity: 1 }], { duration: 200, easing: "ease-out" });
      return stop;
    }
    const to = cover.current?.getBoundingClientRect();
    play(root.current, [{ backgroundColor: "transparent" }, { backgroundColor: canvas() }], { duration: 260, easing: "ease-out" });
    if (to) play(cover.current, [{ transform: flight(origin, to), opacity: .7 }, { transform: rest, opacity: 1 }], { duration: 520, easing: EASE_LIFT });
    play(body.current, [{ opacity: 0, transform: "translateY(18px)" }, { opacity: 1, transform: "none" }], { duration: 380, delay: 160, easing: EASE_OUT, fill: "backwards" });
    return stop;
    // Runs once per mounted record; later prop changes must not replay the opening.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Close: state has already changed (background usable, focus returned); this only plays the return.
  useEffect(() => {
    if (isPresent) return;
    stop();
    const target = reduce ? null : getTarget();
    const from = cover.current?.getBoundingClientRect();
    const done: Array<Promise<unknown>> = [];
    const push = (a: Animation | null) => { if (a) done.push(a.finished); };
    if (target && from) {
      push(play(body.current, [{ opacity: 1 }, { opacity: 0 }], { duration: 140, easing: "ease-in", fill: "forwards" }));
      push(play(cover.current, [{ transform: rest }, { transform: flight(target, from), opacity: .85 }], { duration: 380, easing: EASE_OUT, fill: "forwards" }));
      push(play(root.current, [{ backgroundColor: canvas() }, { backgroundColor: "transparent" }], { duration: 380, easing: "ease-in", fill: "forwards" }));
      push(play(cover.current, [{ opacity: 1 }, { opacity: 0 }], { duration: 120, delay: 300, fill: "forwards" }));
    } else {
      push(play(root.current, [{ opacity: 1 }, { opacity: 0 }], { duration: reduce ? 160 : 300, easing: "ease-in", fill: "forwards" }));
    }
    let finished = false;
    const finish = () => { if (!finished) { finished = true; safeToRemove?.(); } };
    Promise.all(done).then(finish, finish);
    const fallback = window.setTimeout(finish, 700);
    return () => window.clearTimeout(fallback);
    // Only the presence change starts the return; the target is read at that moment.
    // oxlint-disable-next-line react-hooks/exhaustive-deps
  }, [isPresent]);

  useEffect(() => stop, []);

  const tone = statusTone(record.runStatus);
  return (
    <section ref={root} id="archive-record" className="rec-layer" aria-labelledby="rec-title" data-leaving={!isPresent}
      onKeyDown={(e) => { if (e.key === "Escape" && !e.defaultPrevented) { e.preventDefault(); onClose(); } }}>
      <div className="rec-grid">
        <div className="rec-top">
          <button type="button" className="btn btn-quiet btn-sm" onClick={onClose}><Icon name="arrowLeft" size={16} />返回档案</button>
          <span className="note">Esc 也可返回</span>
        </div>
        <div ref={cover} className="rec-cover" data-tone={tone}>
          <div className="rec-cover-face">
            <p className="hud-index"><span>No.</span>{pad(index + 1)}<span className="hud-of">/ {pad(slots)}</span></p>
            <p className="rec-cover-label">研究进度快照{preview ? " · 示例数据" : ""}</p>
            <span className="chip" data-tone={tone} title="保存时的运行状态，不是实时状态">保存时：{statusLabel(record.runStatus)}</span>
            <p className="source-id" style={{ marginTop: 10 }}>项目 {shortId(record.projectId)}<br />运行 {shortId(record.sourceRunId)}</p>
            <dl className="hud-counts">
              <div><dt>已完成</dt><dd>{record.completedWork.length}</dd></div>
              <div><dt>未解决</dt><dd>{record.unresolvedQuestions.length}</dd></div>
              <div><dt>下一步</dt><dd>{record.nextSteps.length}</dd></div>
            </dl>
          </div>
          <RecordActions onLoad={() => onLoad(record.projectId)} loadBusy={load.state === "loading"}
            loadedHere={load.state === "loaded" && load.context.projectId === record.projectId}
            onDelete={onDelete} deleting={deleting} deleteError={deleteError} />
        </div>
        <article ref={body} className="rec-read">
          <p className="eyebrow">保存时的记录 · 不是重新核验</p>
          <h1 id="rec-title" ref={title} className="rec-title" tabIndex={-1}>{record.originalGoal}</h1>
          {load.state !== "idle" ? <div style={{ marginTop: 18 }}><LoadedPanel load={load} onClear={onClearLoaded} onRetry={onLoad} /></div> : null}
          <RecordSections record={record} />
        </article>
      </div>
    </section>
  );
}
