/**
 * Tab-scoped persistence for the unsent Web Agent composer draft.
 *
 * An ordinary browser reload used to lose a typed-but-unsent composer draft
 * (enterprise-litmus P2 UX finding): the "Create project" handoff preserved the
 * draft once, but a later plain reload restored the Project/session link while
 * dropping the still-unsent text. This module keeps the draft in
 * `sessionStorage` (tab-scoped, never the server), keyed by the agent route
 * scope, so a reload restores exactly what the operator had typed.
 *
 * Contract:
 * - Text only. Attachments are never persisted — they are session-bound server
 *   objects and their ids would be stale after reload.
 * - Size-bounded at {@link AGENT_COMPOSER_DRAFT_MAX_CHARS} characters.
 * - Fail open: any storage error (quota, disabled storage, privacy mode) is
 *   swallowed so persistence can never break the composer itself.
 * - An empty/whitespace draft clears the stored value, so sending a message
 *   (which empties the composer) or manually clearing it removes the draft.
 */

export const AGENT_COMPOSER_DRAFT_MAX_CHARS = 20_000;

const KEY_PREFIX = 'es:agent-draft:v1:';

/** Route scope of the agent surface — mirrors IdeAgent's `{projectId, runId}`. */
export interface AgentDraftScope {
  projectId?: string | null;
  runId?: string | null;
}

/** Stable storage key for a scope: run beats project beats the global surface. */
export function agentComposerDraftKey(scope: AgentDraftScope): string {
  if (scope.runId) return `${KEY_PREFIX}run:${scope.runId}`;
  if (scope.projectId) return `${KEY_PREFIX}project:${scope.projectId}`;
  return `${KEY_PREFIX}global`;
}

function storage(): Storage | null {
  try {
    // Accessing sessionStorage itself can throw (e.g. storage disabled).
    return window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * Persist the current draft for a scope. Empty/whitespace input clears the
 * stored draft instead of storing noise. Never throws.
 */
export function saveAgentComposerDraft(scope: AgentDraftScope, text: string): void {
  try {
    const store = storage();
    if (!store) return;
    const key = agentComposerDraftKey(scope);
    if (!text || text.trim().length === 0) {
      store.removeItem(key);
      return;
    }
    store.setItem(key, text.slice(0, AGENT_COMPOSER_DRAFT_MAX_CHARS));
  } catch {
    // Fail open — a persistence failure must never break the composer.
  }
}

/**
 * Load the stored draft for a scope, or null when none exists. The returned
 * value is re-bounded so an oversized value written by an older tab cannot
 * exceed the contract. Never throws.
 */
export function loadAgentComposerDraft(scope: AgentDraftScope): string | null {
  try {
    const store = storage();
    if (!store) return null;
    const value = store.getItem(agentComposerDraftKey(scope));
    if (value == null || value.trim().length === 0) return null;
    return value.slice(0, AGENT_COMPOSER_DRAFT_MAX_CHARS);
  } catch {
    return null;
  }
}

/** Remove the stored draft for a scope. Never throws. */
export function clearAgentComposerDraft(scope: AgentDraftScope): void {
  try {
    storage()?.removeItem(agentComposerDraftKey(scope));
  } catch {
    // Fail open.
  }
}
