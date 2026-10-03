// Incremental text/event-stream parser (fetch-based, so the Bearer header can be
// sent — EventSource cannot). Line-ending handling matches the V1 page: a CR at
// the end of a chunk is kept until the next chunk shows whether LF follows.

export interface SseMessage { id: string; type: string; data: string }

function parseBlock(block: string): SseMessage | null {
  const message: SseMessage = { id: "", type: "message", data: "" };
  const data: string[] = [];
  for (const line of block.split("\n")) {
    if (!line || line.startsWith(":")) continue;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "id") message.id = value;
    else if (field === "event") message.type = value;
    else if (field === "data") data.push(value);
  }
  if (!data.length) return null;
  message.data = data.join("\n");
  return message;
}

function normalize(value: string, finalChunk: boolean): string {
  const text = value.replace(/\r\n/g, "\n");
  return finalChunk ? text.replace(/\r/g, "\n") : text.replace(/\r(?!$)/g, "\n");
}

export function createSseParser() {
  let buffer = "";
  return {
    push(chunk: string, finalChunk = false): SseMessage[] {
      buffer = normalize(buffer + chunk, finalChunk);
      const out: SseMessage[] = [];
      let boundary: number;
      while ((boundary = buffer.indexOf("\n\n")) >= 0) {
        const message = parseBlock(buffer.slice(0, boundary));
        if (message) out.push(message);
        buffer = buffer.slice(boundary + 2);
      }
      if (finalChunk && buffer.trim()) {
        const message = parseBlock(buffer);
        if (message) out.push(message);
        buffer = "";
      }
      return out;
    },
  };
}

/** Reads a response body to completion, delivering each complete SSE message. */
export async function readSse(body: ReadableStream<Uint8Array>, onMessage: (message: SseMessage) => void): Promise<void> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  const parser = createSseParser();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parser.push(decoder.decode(value, { stream: true })).forEach(onMessage);
  }
  parser.push(decoder.decode(), true).forEach(onMessage);
}
