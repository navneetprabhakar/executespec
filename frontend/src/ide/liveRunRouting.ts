import {isTerminalRunStatus} from '@/lib/runStatus';

/** A settled run has no live work left to monitor; every terminal outcome belongs on Results. */
export function liveRunResultPath(runId: string, status?: string | null): string | null {
  return runId && isTerminalRunStatus(status) ? `/result/${runId}` : null;
}

/**
 * Where a run currently being watched on `/live` actually belongs, or null to stay put.
 *
 * `/live` used to consider only the terminal case, so a run that reached the plan gate simply
 * stopped emitting: the page kept showing an activity log with no plan, no steps and no approve
 * button, and the run waited for an approval the watcher was never offered (founder-reported on
 * dev run 350fd2fc). The gate is a hard stop that REQUIRES the human, so it is a navigation
 * destination exactly like the terminal case.
 */
export function liveRunNavigationPath(runId: string, status?: string | null): string | null {
  if (!runId) return null;
  const terminal = liveRunResultPath(runId, status);
  if (terminal) return terminal;
  return String(status ?? '')
    .trim()
    .toUpperCase() === 'AWAITING_APPROVAL'
    ? `/plan/${runId}`
    : null;
}
