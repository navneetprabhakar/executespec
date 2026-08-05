import {isTerminalRunStatus} from '@/lib/runStatus';

/** A settled run has no live work left to monitor; every terminal outcome belongs on Results. */
export function liveRunResultPath(runId: string, status?: string | null): string | null {
  return runId && isTerminalRunStatus(status) ? `/result/${runId}` : null;
}
