import { STAGE_LABELS } from "../../domain/eventText";

export const shortId = (id: string) => (id.length > 18 ? id.slice(0, 8) + "…" + id.slice(-6) : id);
export const statusLabel = (status: string) => STAGE_LABELS[status] ?? status;

/** Tone of the saved run status, used only for the folio's spine mark. */
export function statusTone(status: string): "ok" | "warn" | "error" | "neutral" {
  if (status === "SUCCEEDED") return "ok";
  if (status === "INSUFFICIENT_EVIDENCE") return "warn";
  if (status === "CANCELLED") return "neutral";
  return "error";
}
