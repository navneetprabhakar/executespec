import { ApiError, parseErrorBody } from './errors.js';
import type { HttpClient } from './http.js';

/**
 * Client for the agentic chat endpoint `POST /api/spec/chat` (the Phase-1 `SpecChatService` loop).
 *
 * Unlike the run-event stream (`sse.ts`: GET + a scoped `?token=`, with the discriminator inside the
 * JSON payload), the chat stream is a **POST with the conversation body**, **PAT-bearer** auth, and
 * the SSE **`event:` name is the discriminator** (`ack` / `token` / `tool_call` / `tool_result` /
 * `done`). It is stateless: the client sends the whole conversation each turn and keeps the only
 * copy of history (the server persists an audit row only).
 */

export type ChatRole = 'user' | 'assistant' | 'system';

export interface ChatMessage {
  role: ChatRole;
  content: string;
}

/** One decoded SSE event from a chat turn. `type` mirrors the server's `event:` name. */
export type ChatTurnEvent =
  | { type: 'ack'; auditId?: string }
  | { type: 'token'; delta: string }
  | { type: 'tool_call'; tool: string; args: string; id: string }
  | { type: 'tool_result'; id: string; result: string }
  | { type: 'done'; turnCount?: number; costUsd?: number; note?: string }
  | { type: 'error'; message: string };

export interface StreamChatOptions {
  sessionId: string;
  conversation: ChatMessage[];
  projectId?: string;
  providerOverride?: string;
  signal?: AbortSignal;
}

/**
 * Stream one chat turn. Yields {@link ChatTurnEvent}s as the server emits them (per-round, not
 * per-token — the server router is synchronous). Throws {@link ApiError} on a non-2xx connect (e.g.
 * 401 expired PAT, 403 no-org) so the caller can prompt re-auth.
 */
export async function* streamChat(
  http: HttpClient,
  opts: StreamChatOptions,
): AsyncGenerator<ChatTurnEvent> {
  const res = await http.stream(
    'POST',
    '/spec/chat',
    {
      sessionId: opts.sessionId,
      projectId: opts.projectId,
      conversation: opts.conversation,
      providerOverride: opts.providerOverride,
    },
    { signal: opts.signal },
  );
  if (!res.ok || !res.body) {
    const raw = await res.text().catch(() => '');
    throw new ApiError(res.status, parseErrorBody(res.status, raw).message);
  }
  yield* parseChatSse(res.body, opts.signal);
}

/** Parse a chat SSE byte stream into {@link ChatTurnEvent}s. Exported for unit tests. */
export async function* parseChatSse(
  body: ReadableStream<Uint8Array>,
  signal?: AbortSignal,
): AsyncGenerator<ChatTurnEvent> {
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      if (signal?.aborted) break;
      const { value, done } = await reader.read();
      if (done) break;
      buf = (buf + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
      let sep: number;
      while ((sep = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, sep);
        buf = buf.slice(sep + 2);
        const ev = parseChatBlock(block);
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

/** Decode one `event:`/`data:` SSE block. Exported for unit tests. Returns null for a heartbeat. */
export function parseChatBlock(block: string): ChatTurnEvent | null {
  let event = '';
  let data = '';
  let sawData = false;
  for (const line of block.split('\n')) {
    if (!line || line.startsWith(':')) continue; // blank or heartbeat comment
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let val = colon === -1 ? '' : line.slice(colon + 1);
    if (val.startsWith(' ')) val = val.slice(1);
    if (field === 'event') event = val;
    else if (field === 'data') {
      data += (sawData ? '\n' : '') + val;
      sawData = true;
    }
  }
  if (!event && !sawData) return null;

  let payload: Record<string, unknown> = {};
  if (sawData) {
    try {
      payload = JSON.parse(data) as Record<string, unknown>;
    } catch {
      payload = {};
    }
  }
  const str = (k: string): string => (payload[k] == null ? '' : String(payload[k]));
  const num = (k: string): number | undefined =>
    typeof payload[k] === 'number' ? (payload[k] as number) : undefined;

  switch (event) {
    case 'ack':
      return { type: 'ack', auditId: str('audit_id') || undefined };
    case 'token':
      return { type: 'token', delta: str('delta') };
    case 'tool_call':
      return { type: 'tool_call', tool: str('tool'), args: str('args'), id: str('id') };
    case 'tool_result':
      return { type: 'tool_result', id: str('id'), result: str('result') };
    case 'done':
      return {
        type: 'done',
        turnCount: num('turn_count'),
        costUsd: num('cost_usd'),
        note: str('note') || undefined,
      };
    default:
      return { type: 'error', message: `unexpected event '${event}'` };
  }
}

/**
 * Holds the local conversation for an interactive session and drives one turn at a time. The server
 * is stateless, so this is the authoritative history; only assistant *text* is replayed on the next
 * turn (intermediate tool calls are not reconstructable client-side — design N17).
 */
export class ChatSession {
  readonly sessionId: string;
  projectId?: string;
  provider?: string;
  private readonly messages: ChatMessage[] = [];

  constructor(sessionId: string, opts?: { projectId?: string; provider?: string }) {
    this.sessionId = sessionId;
    this.projectId = opts?.projectId;
    this.provider = opts?.provider;
  }

  history(): ChatMessage[] {
    return [...this.messages];
  }

  setProvider(provider: string | undefined): void {
    this.provider = provider;
  }

  /** Drop the conversation history (start fresh in the same session). */
  clear(): void {
    this.messages.length = 0;
  }

  /**
   * Append a user message, stream the turn, and accumulate the assistant narration into history.
   * Yields every {@link ChatTurnEvent} so the renderer can show tokens/tool-calls live.
   */
  async *send(
    http: HttpClient,
    content: string,
    signal?: AbortSignal,
  ): AsyncGenerator<ChatTurnEvent> {
    this.messages.push({ role: 'user', content });
    let assistant = '';
    for await (const ev of streamChat(http, {
      sessionId: this.sessionId,
      conversation: this.messages,
      projectId: this.projectId,
      providerOverride: this.provider,
      signal,
    })) {
      if (ev.type === 'token' && ev.delta) {
        assistant += (assistant ? '\n' : '') + ev.delta;
      }
      yield ev;
    }
    if (assistant) {
      this.messages.push({ role: 'assistant', content: assistant });
    }
  }
}
