import type { ExecuteSpecApi } from './api.js';
import type { RunEvent } from './types.js';

export interface StreamOptions {
  signal?: AbortSignal;
  /** Resume point for replay (server caches 500 events / 24h per run). */
  lastEventId?: string;
  /** Give up after this many consecutive connect/drop failures (default: keep trying). */
  maxReconnects?: number;
}

/**
 * Yields a run's live {@link RunEvent}s. Implemented over `fetch` streaming (no `EventSource`
 * dependency, so it runs in the CLI and the VS Code extension host alike).
 *
 * Auth: mints a fresh 5-min scoped SSE token per connection (`?token=`), because the run can
 * outlive one token (15-min SLO) — on drop/idle-timeout it reconnects with `Last-Event-ID` and a
 * new token. **Termination is the consumer's job**: break (or abort the signal) on a terminal
 * `COMPLETE`/`RUN_STATUS` event. The generator keeps resuming until then, so transient drops are
 * invisible to the caller.
 */
export async function* streamRunEvents(
  api: ExecuteSpecApi,
  runId: string,
  opts: StreamOptions = {},
): AsyncGenerator<RunEvent> {
  let lastEventId = opts.lastEventId;
  let failures = 0;
  const maxReconnects = opts.maxReconnects ?? Number.POSITIVE_INFINITY;

  while (!opts.signal?.aborted) {
    let body: ReadableStream<Uint8Array> | null = null;
    try {
      const { token } = await api.sseToken(runId);
      const url =
        `${api.http.apiBase}/runs/${encodeURIComponent(runId)}/events` +
        `?token=${encodeURIComponent(token)}`;
      const headers: Record<string, string> = { Accept: 'text/event-stream' };
      if (lastEventId) headers['Last-Event-ID'] = lastEventId;

      const res = await fetch(url, { headers, signal: opts.signal });
      if (!res.ok || !res.body) throw new Error(`SSE connect failed: HTTP ${res.status}`);
      body = res.body;
    } catch (e) {
      if (opts.signal?.aborted) return;
      if (++failures > maxReconnects) throw e instanceof Error ? e : new Error(String(e));
      await sleep(reconnectBackoffMs(failures));
      continue;
    }

    failures = 0;
    try {
      for await (const ev of parseSse(body, (id) => (lastEventId = id))) {
        yield ev;
      }
    } catch {
      // dropped mid-stream — fall through to reconnect
    }
    if (opts.signal?.aborted) return;
    // Clean end or drop: resume from lastEventId after a short backoff (covers the server's
    // ~5-min idle timeout). The consumer ends the loop by breaking on a terminal event.
    if (++failures > maxReconnects) return;
    await sleep(reconnectBackoffMs(failures));
  }
}

/** Parse an SSE byte stream into RunEvents, tracking the `id:` field for reconnect/replay. */
async function* parseSse(
  body: ReadableStream<Uint8Array>,
  setLastId: (id: string) => void,
): AsyncGenerator<RunEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      buf = (buf + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
      let sep: number;
      while ((sep = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, sep);
        buf = buf.slice(sep + 2);
        const ev = parseBlock(block, setLastId);
        if (ev) yield ev;
      }
    }
  } finally {
    try {
      await reader.cancel();
    } catch {
      // already closed
    }
  }
}

function parseBlock(block: string, setLastId: (id: string) => void): RunEvent | null {
  let data = '';
  let sawData = false;
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue; // blank or heartbeat comment
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let val = colon === -1 ? '' : line.slice(colon + 1);
    if (val.startsWith(' ')) val = val.slice(1);
    if (field === 'id') {
      setLastId(val);
    } else if (field === 'data') {
      data += (sawData ? '\n' : '') + val;
      sawData = true;
    }
    // `event:` is ignored — the discriminator lives inside the JSON payload (RunEvent.type).
  }
  if (!sawData) return null;
  try {
    return JSON.parse(data) as RunEvent;
  } catch {
    return { type: 'LOG', payload: data };
  }
}

function reconnectBackoffMs(failures: number): number {
  return Math.min(15000, 1000 * 2 ** (failures - 1)) + Math.floor(Math.random() * 250);
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
