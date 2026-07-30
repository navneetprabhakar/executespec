import * as api from '@/lib/api';
import {webAgentSessionRequest, type AgentSessionScope} from '@/ide/agentSession';

/**
 * Canonical frontend agent-session creation (Phase 4, item 28).
 *
 * Before this module, "new sandbox" meant three different things: the Console
 * header button navigated to `/agent`, the command palette navigated to
 * `/agent`, and only `IdeAgent`'s own button actually created a session. Two of
 * the three claimed creation and performed navigation. Navigation is transport;
 * it is not creation and must never be counted as such.
 *
 * Every entry point now mints one `NewAgentSessionRequest` carrying a client
 * UUID that is used as BOTH the idempotency key and the session id. The backend
 * (`AgentSessionCreationService`) returns the existing owned session when the id
 * already exists, so a double-click, a rapid keyboard submit, or a retry after a
 * dropped response all converge on exactly one session.
 */
export type NewAgentSessionRequest = {
  /** Client UUID used as the session id and idempotency key. */
  requestId: string;
  scope: AgentSessionScope;
  conversationRoute?: {provider?: string | null; model?: string | null};
};

function mintId(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  // Deterministic-enough fallback for environments without WebCrypto. The id
  // only has to be unique per client request, not unguessable.
  return `agent-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export function newAgentSessionRequest(
  scope: AgentSessionScope = {},
  conversationRoute?: NewAgentSessionRequest['conversationRoute'],
): NewAgentSessionRequest {
  return {requestId: mintId(), scope, conversationRoute};
}

/**
 * Create (or idempotently re-fetch) the session for this request. Calling twice
 * with the same `requestId` yields the same server session.
 */
export function createCanonicalAgentSession(
  request: NewAgentSessionRequest,
): Promise<api.AgentSessionResponse> {
  return api.createAgentSession({
    ...webAgentSessionRequest(request.scope),
    id: request.requestId,
    defaultProvider: request.conversationRoute?.provider ?? null,
    defaultModel: request.conversationRoute?.model ?? null,
  });
}

type Listener = (request: NewAgentSessionRequest) => void;

const listeners = new Set<Listener>();
let queued: NewAgentSessionRequest | null = null;

/**
 * Ask for a new sandbox session. When the agent surface is mounted it handles
 * the request immediately; otherwise the request is queued so the surface can
 * consume it on mount after the caller navigates. The caller still navigates —
 * but navigation is now transport for a request that already exists, not a
 * substitute for creating one.
 */
export function requestNewAgentSession(scope: AgentSessionScope = {}): NewAgentSessionRequest {
  const request = newAgentSessionRequest(scope);
  if (listeners.size > 0) {
    listeners.forEach((listener) => listener(request));
  } else {
    queued = request;
  }
  return request;
}

export function subscribeNewAgentSession(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/** Consume a request queued while the agent surface was unmounted. */
export function takePendingNewAgentSession(): NewAgentSessionRequest | null {
  const pending = queued;
  queued = null;
  return pending;
}

/** Test-only reset so queued state does not leak between cases. */
export function resetPendingNewAgentSession(): void {
  queued = null;
}
