// The public tool boundary for creating a run. The server's run view reports the expanded
// internal capability list (e.g. read_source / check_claims for autonomous research); only the
// three public tools may ever be sent back as requestedTools.
import type { ExecutionMode, ToolName } from "./types";

export const PUBLIC_TOOLS: readonly ToolName[] = ["kb_search", "web_search", "calculator"];

/** Public tools in first-seen order, deduplicated; internal or unknown names are dropped. */
export function publicTools(list: readonly unknown[] | null | undefined): ToolName[] {
  const out: ToolName[] = [];
  for (const raw of list ?? []) {
    const name = typeof raw === "string" ? raw.trim().toLowerCase() : "";
    if ((PUBLIC_TOOLS as readonly string[]).includes(name) && !out.includes(name as ToolName)) out.push(name as ToolName);
  }
  return out;
}

export type ToolSelection = { ok: true; tools: ToolName[] } | { ok: false; reason: string };

/**
 * The validated public selection for a NEW create request. Legacy sends no tools. Nothing is
 * added: a web-only selection stays web-only, and an empty selection is refused, not defaulted.
 */
export function creationTools(mode: ExecutionMode, tools: readonly unknown[]): ToolSelection {
  if (mode === "legacy") return { ok: true, tools: [] };
  const selected = publicTools(tools);
  return selected.length ? { ok: true, tools: selected } : { ok: false, reason: "请至少允许一个只读工具。" };
}

/** A follow-up keeps the original run's mode, session and public tool selection. */
export function followUpRequest(run: { mode: ExecutionMode; sessionId: string; tools: readonly unknown[] }): ToolSelection & { mode: ExecutionMode; sessionId: string } {
  const selection = creationTools(run.mode, run.tools);
  return { ...(selection.ok ? selection : { ok: false as const, reason: "无法确认原报告使用的工具，请回到新研究页面重新选择。" }), mode: run.mode, sessionId: run.sessionId };
}
