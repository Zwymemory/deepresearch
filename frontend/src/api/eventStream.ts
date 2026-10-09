// Durable SSE consumer for one run (V1 startEventLoop semantics):
// - always resumes from the latest applied cursor via Last-Event-ID;
// - a closed stream is not completion: a fresh snapshot decides whether the run is terminal;
// - 401/403/404/409 stop automatic recovery; other failures back off 1s, 2s, 4s … max 8s;
// - stopping the stream never cancels the run on the server.
import { ApiError } from "./http";
import { openEventStream, parseEventData, type ApiContext } from "./endpoints";
import { readSse } from "./sse";
import type { RunEvent } from "../domain/types";

export interface StreamCallbacks {
  getCursor: () => string;
  onEvent: (event: RunEvent, cursorId: string) => void;
  onConnected: () => void;
  onReconnecting: (attempt: number, delayMs: number) => void;
  onFatal: (error: ApiError) => void;
  onParseError?: () => void;
  /** Fetch an authoritative snapshot; resolves to whether the run is terminal. */
  refresh: () => Promise<boolean>;
}

export const FATAL_STATUSES = [401, 403, 404, 409];

export function backoffDelay(attempt: number): number {
  return Math.min(8000, Math.pow(2, Math.max(0, attempt - 1)) * 1000);
}

function sleep(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve) => {
    const id = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => { clearTimeout(id); resolve(); }, { once: true });
  });
}

export function startEventStream(ctx: ApiContext, runId: string, cb: StreamCallbacks,
  options: { sleep?: (ms: number, signal: AbortSignal) => Promise<void> } = {}): { stop: () => void; done: Promise<void> } {
  const controller = new AbortController();
  const wait = options.sleep ?? sleep;
  const done = (async () => {
    let attempt = 0;
    while (!controller.signal.aborted) {
      try {
        const body = await openEventStream(ctx, runId, cb.getCursor(), controller.signal);
        cb.onConnected();
        attempt = 0;
        await readSse(body, (message) => {
          const event = parseEventData(message.data);
          if (event) cb.onEvent(event, message.id || event.id || "");
          else cb.onParseError?.();
        });
        if (controller.signal.aborted) return;
        if (await cb.refresh()) return;
        throw new ApiError("SSE 连接已关闭", null);
      } catch (error) {
        if (controller.signal.aborted) return;
        const status = (error as ApiError).status ?? null;
        if (status != null && FATAL_STATUSES.includes(status)) { cb.onFatal(error as ApiError); return; }
        attempt += 1;
        const delay = backoffDelay(attempt);
        cb.onReconnecting(attempt, delay);
        await wait(delay, controller.signal);
      }
    }
  })();
  return { stop: () => controller.abort(), done };
}
