// Regression (M1 native chain): a queued GET may already carry a blank finalResponse. After a fast
// terminal SSE event the page must still read the terminal GET, which holds the real unfinished goals.
import { describe, expect, it } from "vitest";
import { applyEvent, applyView, emptyRun, needsTerminalSnapshot } from "./runState";
import type { WorkflowView } from "./types";

const queued = { runId: "wf-1", sessionId: "s-1", status: "QUEUED", stage: "QUEUED", requestedTools: ["kb_search"],
  trace: [], finalResponse: {} } as unknown as WorkflowView;
const terminal = { ...queued, status: "INSUFFICIENT_EVIDENCE", stage: "TERMINAL", finalResponse: {
  answer: "", report_status: "insufficient",
  unfinished_goals: [{ task_id: "task-latency", criterion_id: "c-1", text: "在同数据集、同硬件下测量延迟；保留效果争议",
    reason: "Acceptance criterion remains uncovered", gaps: ["No scoped Claim is bound to this stored criterion"] }],
} } as unknown as WorkflowView;

describe("terminal snapshot hydration", () => {
  it("queued blank finalResponse → terminal SSE → rich terminal GET", () => {
    let run = applyView(emptyRun("接着做", ["kb_search"], "agent"), queued);
    expect(run.finalResponse).toEqual({});            // non-null but blank: must not count as final
    expect(needsTerminalSnapshot(run)).toBe(false);   // not terminal yet

    run = applyEvent(run, { id: "wf-1:9", eventId: 9, type: "INSUFFICIENT_EVIDENCE", role: "SYSTEM", payload: {} } as never, "wf-1:9");
    expect(run.status).toBe("INSUFFICIENT_EVIDENCE");
    expect(needsTerminalSnapshot(run)).toBe(true);    // the blank finalResponse no longer suppresses the GET

    run = applyView(run, terminal);
    expect(needsTerminalSnapshot(run)).toBe(false);   // one terminal GET settles it: no refetch loop
    expect(run.finalResponse?.unfinished_goals).toHaveLength(1);
  });

  it("keeps a terminal memory error code from the snapshot", () => {
    let run = applyView(emptyRun("q", ["kb_search"], "agent"), queued);
    run = applyEvent(run, { id: "wf-1:3", eventId: 3, type: "FAILED", role: "SYSTEM", payload: { errorCode: "RESEARCH_MEMORY_REVOKED" } } as never, "wf-1:3");
    expect(needsTerminalSnapshot(run)).toBe(true);
    run = applyView(run, { ...queued, status: "FAILED", stage: "TERMINAL", errorCode: "RESEARCH_MEMORY_REVOKED" } as unknown as WorkflowView);
    expect(run.errorCode).toBe("RESEARCH_MEMORY_REVOKED");
    expect(needsTerminalSnapshot(run)).toBe(false);
  });
});
