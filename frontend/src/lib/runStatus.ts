/**
 * The single run-status vocabulary for the whole frontend.
 *
 * RR-1463 / RR-1521 / RR-1536 — before this module there were four independent
 * hardcoded status lists (`ide/planUtils.ts`, `ide/moreUtils.ts`, `ide/agentState.ts`,
 * `lib/utils.ts`) plus an inline one in `ide/IdeProject.tsx`. Every one of them
 * omitted `APPROVAL_RESUMING`, which the backend genuinely broadcasts between plan
 * approval and the first worker turn, so an approved run fell through to "not
 * executing" and the project landing page reported `Active 0` while the run spent.
 *
 * Source of truth, both in `src/main/java/com/navneet/executespec`:
 *   - terminal set — `integration/RunPipelineSupport.isTerminal`
 *     (`COMPLETE | FAILED | CANCELLED | PARTIAL`)
 *   - in-flight set — `integration/RunLifecycleService.NON_TERMINAL_STATUSES`
 *     (`QUEUED | PLANNING | AWAITING_APPROVAL | APPROVAL_RESUMING | WORKING | REVIEWING`)
 *     plus `PENDING`, `PAUSED` and `STUCK`, which that halt-all list deliberately excludes.
 *
 * The `LEGACY_*` arrays hold statuses the current backend no longer writes but which
 * persisted rows and older event replays still carry. They are kept so historical runs
 * keep classifying the way they always have, and are named so nobody mistakes them for
 * part of the live contract.
 */

/** Terminal per `RunPipelineSupport.isTerminal`. */
export const TERMINAL_RUN_STATUSES = ['COMPLETE', 'FAILED', 'CANCELLED', 'PARTIAL'] as const;

/** Historical terminal spellings retained for rows written before the current vocabulary. */
export const LEGACY_TERMINAL_RUN_STATUSES = ['COMPLETED', 'ERROR'] as const;

/** Accepted and queued, before any plan exists. */
export const QUEUED_RUN_STATUSES = ['QUEUED', 'PENDING'] as const;

/** Classification + planning, before the plan gate. */
export const PLANNING_RUN_STATUSES = ['PLANNING'] as const;

/** Historical/alias planning spellings. `CLASSIFYING` predates the merged planning phase. */
export const LEGACY_PLANNING_RUN_STATUSES = ['SUBMITTED', 'CLASSIFYING'] as const;

/** The plan gate itself — in flight, but waiting on a human, not on the pipeline. */
export const AWAITING_APPROVAL_RUN_STATUS = 'AWAITING_APPROVAL';

/**
 * Post-gate execution. `APPROVAL_RESUMING` is the state a run enters the instant the plan
 * is approved (`RunLifecycleService:283,288`) and is what every previous list omitted.
 */
export const EXECUTING_RUN_STATUSES = ['APPROVAL_RESUMING', 'WORKING', 'REVIEWING'] as const;

/**
 * Execution spellings the current backend does not set on a pipeline run but which
 * older rows, batch runs (retired, HTTP 410) and non-run surfaces still use.
 */
export const LEGACY_EXECUTING_RUN_STATUSES = [
  'EXECUTING',
  'RUNNING',
  'IN_PROGRESS',
  'IN_PROGRESS_BATCH',
  'CODING',
  'BUILDING',
  'COMPILING',
  'VALIDATING',
  'TESTING',
] as const;

/**
 * Frontend-only transition tokens. No backend `setStatus` writes these, but the live
 * surfaces have always listed them, so they stay live-visible without being classified
 * as pipeline execution.
 */
export const LEGACY_LIVE_ONLY_RUN_STATUSES = ['PROVIDER_SWITCHING'] as const;

/** Non-terminal but not progressing on its own; both need a deliberate human action. */
export const HALTED_RUN_STATUSES = ['PAUSED', 'STUCK'] as const;

const TERMINAL = new Set<string>([...TERMINAL_RUN_STATUSES, ...LEGACY_TERMINAL_RUN_STATUSES]);
const QUEUED = new Set<string>(QUEUED_RUN_STATUSES);
const PLANNING = new Set<string>([...PLANNING_RUN_STATUSES, ...LEGACY_PLANNING_RUN_STATUSES]);
const EXECUTING = new Set<string>([...EXECUTING_RUN_STATUSES, ...LEGACY_EXECUTING_RUN_STATUSES]);
const HALTED = new Set<string>(HALTED_RUN_STATUSES);
const LIVE_ONLY = new Set<string>(LEGACY_LIVE_ONLY_RUN_STATUSES);

export function normalizeRunStatus(status?: string | null): string {
  return (status || '').toUpperCase();
}

/** The run has settled; nothing further will happen without a new submission. */
export function isTerminalRunStatus(status?: string | null): boolean {
  return TERMINAL.has(normalizeRunStatus(status));
}

/** Accepted but not yet planning. */
export function isQueuedRunStatus(status?: string | null): boolean {
  return QUEUED.has(normalizeRunStatus(status));
}

/** Classifying or planning, before the plan gate opens. */
export function isPlanningRunStatus(status?: string | null): boolean {
  return PLANNING.has(normalizeRunStatus(status));
}

/** Parked at the plan gate, waiting on a human decision. */
export function isAwaitingApprovalRunStatus(status?: string | null): boolean {
  return normalizeRunStatus(status) === AWAITING_APPROVAL_RUN_STATUS;
}

/**
 * The pipeline is doing post-gate work — including the `APPROVAL_RESUMING` hand-off.
 * This is the predicate that decides whether to route to the live surface.
 */
export function isExecutingRunStatus(status?: string | null): boolean {
  return EXECUTING.has(normalizeRunStatus(status));
}

/** Non-terminal but stalled: `PAUSED` or `STUCK`. */
export function isHaltedRunStatus(status?: string | null): boolean {
  return HALTED.has(normalizeRunStatus(status));
}

/**
 * Work is in flight — queued, planning, at the gate, executing, or halted mid-run.
 * Everything that is not terminal. This is what a project "Active" count means.
 */
export function isActiveRunStatus(status?: string | null): boolean {
  const s = normalizeRunStatus(status);
  if (!s) return false;
  return !TERMINAL.has(s);
}

/**
 * Belongs on a live/monitoring surface: actively progressing, at the gate, or halted.
 * Deliberately excludes the pre-plan queue, which has nothing to show yet.
 */
export function isLiveRunStatus(status?: string | null): boolean {
  const s = normalizeRunStatus(status);
  return (
    PLANNING.has(s) ||
    EXECUTING.has(s) ||
    HALTED.has(s) ||
    LIVE_ONLY.has(s) ||
    s === AWAITING_APPROVAL_RUN_STATUS
  );
}
