import {ApiError, BASE, authHeader, parseErrorMessage} from './core';
import type {AgentClientCapabilities, AgentRepoContext, AgentTypedAction} from './agent';

export interface AgentConversationMessage {
  role: 'system' | 'user' | 'assistant' | 'tool' | string;
  content: string;
  [key: string]: unknown;
}

export interface StreamAgentTurnRequest {
  message: string;
  conversation?: AgentConversationMessage[] | null;
  repoContext?: AgentRepoContext | null;
  clientCapabilities?: AgentClientCapabilities | null;
  providerOverride?: string | null;
  modelOverride?: string | null;
  attachmentIds?: string[] | null;
}

export type AgentStreamEventName =
  | 'ack'
  | 'route_decision'
  | 'proposal'
  | 'token'
  | 'tool_call'
  | 'tool_result'
  | 'done'
  | 'error'
  | 'message';

export interface AgentStreamEvent<T = unknown> {
  event: AgentStreamEventName | string;
  data: T;
}

export interface AgentStreamAck {
  session_id: string;
  turn_id: string;
  sequence: number;
}

export interface AgentStreamRouteDecision {
  session_id: string;
  turn_id: string;
  route: string;
  confidence: number;
  reason_code: string;
  run_api_allowed: boolean;
  requires_confirmation: boolean;
}

export interface AgentStreamProposal {
  session_id: string;
  turn_id: string;
  route: string;
  action: AgentTypedAction;
}

export interface AgentStreamToken {
  delta: string;
}

export interface AgentStreamToolCall {
  tool: string;
  args: string;
  id: string;
}

export interface AgentStreamToolResult {
  id: string;
  result: string;
}

/** Explicit lifecycle marker; ordinary tool progress must not be inferred as a handoff. */
export interface AgentStreamToolEvent {
  name?: string;
  status?: string;
  detail?: string;
  type?: string;
  kind?: string;
  event_type?: string;
  action?: string;
  role?: string;
  agent_role?: string;
  provider?: string;
  provider_name?: string;
  model?: string;
  model_name?: string;
  state?: string;
  message?: string;
  reason?: string;
}

export interface AgentStreamDone {
  session_id: string;
  turn_id: string;
  turn_count: number;
  cost_usd?: number;
  input_tokens?: number;
  output_tokens?: number;
  reasoning_tokens?: number;
  note?: string;
}

export interface AgentStreamError {
  session_id?: string;
  turn_id?: string;
  message: string;
}

export interface StreamAgentTurnHandlers {
  onAck?: (data: AgentStreamAck) => void;
  onRouteDecision?: (data: AgentStreamRouteDecision) => void;
  onProposal?: (data: AgentStreamProposal) => void;
  onToken?: (data: AgentStreamToken) => void;
  onToolCall?: (data: AgentStreamToolCall) => void;
  onToolResult?: (data: AgentStreamToolResult) => void;
  onDone?: (data: AgentStreamDone) => void;
  onErrorEvent?: (data: AgentStreamError) => void;
  onEvent?: (event: AgentStreamEvent) => void;
}

export function parseAgentSseFrame(frame: string): AgentStreamEvent | null {
  let event = 'message';
  const data: string[] = [];
  for (const rawLine of frame.split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(':')) continue;
    const separator = rawLine.indexOf(':');
    const field = separator === -1 ? rawLine : rawLine.slice(0, separator);
    let value = separator === -1 ? '' : rawLine.slice(separator + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value || 'message';
    if (field === 'data') data.push(value);
  }
  if (!data.length && event === 'message') return null;
  const rawData = data.join('\n');
  let parsed: unknown = rawData;
  if (rawData) {
    try {
      parsed = JSON.parse(rawData);
    } catch {
      parsed = rawData;
    }
  }
  return {event, data: parsed};
}

export async function streamAgentTurn(
  sessionId: string,
  body: StreamAgentTurnRequest,
  handlers: StreamAgentTurnHandlers = {},
  signal?: AbortSignal,
): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${BASE}/agent/sessions/${encodeURIComponent(sessionId)}/turns/stream`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json', ...authHeader()},
      credentials: 'include',
      body: JSON.stringify(body),
      signal,
    });
  } catch (e) {
    const reason = e instanceof Error ? e.message : 'unknown';
    throw new ApiError(
      0,
      `Can't reach the server (${reason}). Check your connection and try again.`,
    );
  }

  if (!response.ok) {
    const raw = await response.text().catch(() => '');
    throw new ApiError(response.status, parseErrorMessage(response.status, raw));
  }
  if (!response.body) return;

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const emitFrame = (frame: string) => {
    const parsed = parseAgentSseFrame(frame);
    if (!parsed) return;
    handlers.onEvent?.(parsed);
    if (parsed.event === 'ack') handlers.onAck?.(parsed.data as AgentStreamAck);
    if (parsed.event === 'route_decision') {
      handlers.onRouteDecision?.(parsed.data as AgentStreamRouteDecision);
    }
    if (parsed.event === 'proposal') {
      handlers.onProposal?.(parsed.data as AgentStreamProposal);
    }
    if (parsed.event === 'token') handlers.onToken?.(parsed.data as AgentStreamToken);
    if (parsed.event === 'tool_call') {
      handlers.onToolCall?.(parsed.data as AgentStreamToolCall);
    }
    if (parsed.event === 'tool_result') {
      handlers.onToolResult?.(parsed.data as AgentStreamToolResult);
    }
    if (parsed.event === 'done') handlers.onDone?.(parsed.data as AgentStreamDone);
    if (parsed.event === 'error') handlers.onErrorEvent?.(parsed.data as AgentStreamError);
  };

  let reading = true;
  while (reading) {
    const {value, done} = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), {stream: !done});
    let split = buffer.search(/\r?\n\r?\n/);
    while (split >= 0) {
      const frame = buffer.slice(0, split);
      const delimiterLength = buffer.startsWith('\r\n\r\n', split) ? 4 : 2;
      buffer = buffer.slice(split + delimiterLength);
      emitFrame(frame);
      split = buffer.search(/\r?\n\r?\n/);
    }
    reading = !done;
  }
  if (buffer.trim()) emitFrame(buffer);
}
