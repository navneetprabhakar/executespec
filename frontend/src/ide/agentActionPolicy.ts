import type * as api from '@/lib/api';

/**
 * Confirmability policy for agent action proposals.
 *
 * Phase 4 (item 27) of the pre-routing programme: rendering and confirmation
 * dispatch each carried their own `action.type === 'create_cloud_run'` check,
 * so the two could disagree and the UI could present a confirm button for a
 * proposal the server would refuse. This module is the single predicate both
 * sides consume.
 *
 * The rule, in order of authority:
 *  1. The action must actually be a cloud-run proposal that asks for confirmation.
 *  2. If the server sent a typed `actionContext`, it is authoritative — a
 *     blocked context is never confirmable, whatever the rest of the payload says.
 *  3. Independently of the context, the proposal must carry the project scope a
 *     run needs. Turns persisted before the typed contract existed replay with
 *     no context at all, and those must not become confirmable by omission.
 */
export const CREATE_CLOUD_RUN = 'create_cloud_run';

export function isCloudRunAction(action: api.AgentTypedAction | null | undefined): boolean {
  return !!action && action.type === CREATE_CLOUD_RUN;
}

export function cloudRunProjectId(action: api.AgentTypedAction | null | undefined): string | null {
  if (!isCloudRunAction(action) || !action) return null;
  const projectId = action.arguments?.projectId;
  return typeof projectId === 'string' && projectId.trim().length > 0 ? projectId.trim() : null;
}

function hasProjectScope(action: api.AgentTypedAction): boolean {
  return cloudRunProjectId(action) != null;
}

export function isConfirmableCloudRun(action: api.AgentTypedAction | null | undefined): boolean {
  if (!isCloudRunAction(action) || !action) return false;
  if (!action.requiresConfirmation) return false;
  const context = action.actionContext;
  if (context && !context.confirmable) return false;
  return hasProjectScope(action);
}

/**
 * Why a cloud-run proposal cannot be confirmed, for operator-facing copy.
 * Returns null when the action is confirmable or is not a cloud run at all.
 */
export function cloudRunBlockingReasons(
  action: api.AgentTypedAction | null | undefined,
): string[] | null {
  if (!isCloudRunAction(action) || !action) return null;
  if (isConfirmableCloudRun(action)) return null;
  const serverReasons = action.actionContext?.blockingReasons ?? [];
  if (serverReasons.length > 0) return serverReasons;
  if (!hasProjectScope(action)) return ['MISSING_PROJECT_CONTEXT'];
  return ['NOT_CONFIRMABLE'];
}

/** True when the operator must pick a project/repository before confirming. */
export function requiresProjectSelection(action: api.AgentTypedAction | null | undefined): boolean {
  if (!isCloudRunAction(action) || !action) return false;
  if (action.actionContext) return action.actionContext.projectSelectionRequired;
  return !hasProjectScope(action);
}

const BLOCKING_COPY: Record<string, string> = {
  MISSING_PROJECT_CONTEXT: 'Select a project or repository first.',
  MISSING_WRITE_SCOPE: 'You do not have permission to submit runs for this project.',
  NOT_CONFIRMABLE: 'This proposal is not ready to submit.',
};

/** Human-readable, non-jargon explanation for a blocked cloud-run proposal. */
export function cloudRunBlockingCopy(action: api.AgentTypedAction | null | undefined): string {
  const reasons = cloudRunBlockingReasons(action);
  if (!reasons || reasons.length === 0) return '';
  return reasons.map((r) => BLOCKING_COPY[r] ?? BLOCKING_COPY.NOT_CONFIRMABLE).join(' ');
}
