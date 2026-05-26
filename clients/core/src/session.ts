import type { ExecuteSpecApi } from './api.js';
import { streamRunEvents } from './sse.js';
import { RunEventType, type RunEvent, type ToolCallFrame } from './types.js';
import type { ToolRuntime } from './tools/runtime.js';

export interface LocalSessionOptions {
  signal?: AbortSignal;
  /** Forwarded for non-tool events (status, plan-gate, cost, logs) so the UI can render them. */
  onEvent?: (ev: RunEvent) => void;
}

/**
 * Drives the local-hands loop. Subscribes to the run's event stream; for each `TOOL_CALL` it runs
 * the tool locally via {@link ToolRuntime} and POSTs the result back to the server-side agent. All
 * other events are forwarded to `onEvent`. The brain (planning, prompts, model routing, scoring)
 * stays on the server — this class is purely the hands plus a thin pump.
 *
 * Returns when the run reaches a terminal state.
 */
export class LocalExecutionSession {
  constructor(
    private readonly api: ExecuteSpecApi,
    private readonly runtime: ToolRuntime,
    private readonly runId: string,
  ) {}

  async run(opts: LocalSessionOptions = {}): Promise<void> {
    for await (const ev of streamRunEvents(this.api, this.runId, { signal: opts.signal })) {
      if (ev.type === RunEventType.TOOL_CALL) {
        await this.handleToolCall(ev);
        continue;
      }
      opts.onEvent?.(ev);
      if (isTerminal(ev)) return;
    }
  }

  private async handleToolCall(ev: RunEvent): Promise<void> {
    if (!ev.payload) return;
    let frame: ToolCallFrame;
    try {
      frame = JSON.parse(ev.payload) as ToolCallFrame;
    } catch {
      return; // malformed frame — the server will time out and surface it
    }
    const result = await this.runtime.execute(frame);
    await this.api.postToolResult(this.runId, { callId: frame.callId, result });
  }
}

const TERMINAL_STATUSES = new Set(['COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED']);

function isTerminal(ev: RunEvent): boolean {
  if (ev.type === RunEventType.COMPLETE) return true;
  if (ev.type === RunEventType.RUN_STATUS && ev.payload) {
    return TERMINAL_STATUSES.has(ev.payload.split('|')[0] ?? ev.payload);
  }
  return false;
}
