/**
 * R-01 (UX audit, 2026-05-03) — single source of truth for run-status →
 * design-system colour-token mapping. Pre-fix this map was duplicated
 * inline in three places (RunDetail.tsx, InlineRunDetail.tsx, status
 * badge call sites) with subtle per-surface drift — e.g. PARTIAL was
 * `text-amber-500` in one place, `text-status-partial` (the token) in
 * another, and the failed-run colour was `text-red-500` vs.
 * `text-status-error` depending on which surface you opened. Hoisting
 * to one module collapses the drift class.
 *
 * Keys are uppercase status strings emitted by the backend (PipelineRun
 * statuses). Values are Tailwind class strings using the 4-band semantic
 * tokens defined in `tokens.css` — `success / partial / error /
 * executing / thinking / waiting`.
 *
 * Use {@link runStatusTextClass} for a `text-*` class, or
 * {@link runStatusBgClass} for a soft `bg-*` chip background.
 */

export const RUN_STATUS_TEXT_COLOR: Record<string, string> = {
  COMPLETE: 'text-status-success',
  PARTIAL: 'text-status-partial',
  FAILED: 'text-status-error',
  CANCELLED: 'text-status-error',
  ABORTED: 'text-status-error',
  RUNNING: 'text-status-executing',
  EXECUTING: 'text-status-executing',
  PLANNING: 'text-status-thinking',
  WORKING: 'text-status-executing',
  REVIEWING: 'text-status-thinking',
  AWAITING_APPROVAL: 'text-status-waiting',
  AWAITING_MERGE: 'text-status-waiting',
  PAUSED: 'text-status-waiting',
};

export const RUN_STATUS_BG_COLOR: Record<string, string> = {
  COMPLETE: 'bg-status-success/10',
  PARTIAL: 'bg-status-partial/10',
  FAILED: 'bg-status-error/10',
  CANCELLED: 'bg-status-error/10',
  ABORTED: 'bg-status-error/10',
  RUNNING: 'bg-status-executing/10',
  EXECUTING: 'bg-status-executing/10',
  PLANNING: 'bg-status-thinking/10',
  WORKING: 'bg-status-executing/10',
  REVIEWING: 'bg-status-thinking/10',
  AWAITING_APPROVAL: 'bg-status-waiting/10',
  AWAITING_MERGE: 'bg-status-waiting/10',
  PAUSED: 'bg-status-waiting/10',
};

/** True for statuses where the run is finished and won't change without user action. */
export const TERMINAL_RUN_STATUSES: ReadonlySet<string> = new Set([
  'COMPLETE',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'ABORTED',
]);

/** True for statuses where pause/cancel are still meaningful actions. */
export const NON_TERMINAL_RUN_STATUSES: ReadonlySet<string> = new Set([
  'PLANNING',
  'WORKING',
  'REVIEWING',
  'AWAITING_APPROVAL',
  'AWAITING_MERGE',
  'RUNNING',
  'EXECUTING',
  'PAUSED',
]);

/** Tailwind text class for a given status. Falls back to muted on unknown values. */
export function runStatusTextClass(status: string | null | undefined): string {
  if (!status) return 'text-muted';
  return RUN_STATUS_TEXT_COLOR[status] ?? 'text-muted';
}

/** Tailwind bg class for a given status (soft chip background). */
export function runStatusBgClass(status: string | null | undefined): string {
  if (!status) return 'bg-[var(--gray-200)]';
  return RUN_STATUS_BG_COLOR[status] ?? 'bg-[var(--gray-200)]';
}

/** Convenience predicate — true when run is in a terminal state. */
export function isTerminalRunStatus(status: string | null | undefined): boolean {
  return !!status && TERMINAL_RUN_STATUSES.has(status);
}

/** Convenience predicate — true when run is still running and might respond to pause/cancel. */
export function isNonTerminalRunStatus(status: string | null | undefined): boolean {
  return !!status && NON_TERMINAL_RUN_STATUSES.has(status);
}
