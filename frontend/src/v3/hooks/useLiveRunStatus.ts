import {useCallback, useEffect, useRef, useState} from 'react';
import {getRun} from '@/lib/api';

/**
 * Audit r5 (P0-1, 2026-05-06) — kept the v3 chat surface from getting stuck.
 *
 * Background: `useV3ProjectStore` seeds bubble.status once from /api/runs and
 * the optimistic Composer push shoves `status: 'PLANNING'` for every new
 * run. There is no SSE listener in v3 (R-130 was scoped but never landed),
 * so a bubble's status was frozen at submit time. The result was that
 * `PlanCard` (rendered only when bubble.status === 'AWAITING_APPROVAL')
 * never rendered — leaving every new user with no Approve button anywhere
 * on the default surface.
 *
 * This hook closes the gap with simple polling:
 *   - first call  → fetch /api/runs/{runId} once, cache the live status
 *   - non-terminal status → set up a 5s interval, refetch, restart the
 *     interval until terminal
 *   - terminal status (COMPLETE / PARTIAL / FAILED / CANCELLED / ABORTED)
 *     → freeze
 *
 * The 5s cadence matches the cost-polling loop in v2 RunDetailTabs and
 * stays well under the rate limiter's RUNS group quota (60/min).
 */

const TERMINAL_STATUSES = new Set([
  'COMPLETE',
  'PARTIAL',
  'FAILED',
  'CANCELLED',
  'ABORTED',
]);

export interface LiveRunStatus {
  status: string | null;
  loading: boolean;
  /** Force an immediate refetch — used right after a user action like
   *  approving a plan, so the UI flips from PlanCard to ResultCard
   *  without waiting up to 5s for the next poll tick. */
  refresh: () => void;
}

export function useLiveRunStatus(
  runId: string | null | undefined,
  initialStatus?: string | null,
): LiveRunStatus {
  const [status, setStatus] = useState<string | null>(initialStatus ?? null);
  const [loading, setLoading] = useState<boolean>(!initialStatus);
  // Bumping this forces the polling effect to restart from a fresh tick.
  const [rev, setRev] = useState(0);
  const refreshRef = useRef(() => setRev((r) => r + 1));

  useEffect(() => {
    if (!runId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    async function tick() {
      try {
        const d = await getRun(runId!);
        if (cancelled) return;
        setStatus(d.status);
        setLoading(false);
        if (!TERMINAL_STATUSES.has(d.status)) {
          timer = setTimeout(tick, 5000);
        }
      } catch {
        // Network / 4xx — keep last-known status, retry on next tick.
        // Don't crash the bubble; PlanCard / ResultCard each fetch their
        // own RunDetail and will surface a real error there.
        if (cancelled) return;
        setLoading(false);
        timer = setTimeout(tick, 5000);
      }
    }

    // If the optimistic seed says terminal AND we haven't been bumped
    // since mount, skip fetching — saves an unnecessary request.
    if (initialStatus && TERMINAL_STATUSES.has(initialStatus) && rev === 0) {
      setStatus(initialStatus);
      setLoading(false);
      return () => {
        cancelled = true;
      };
    }

    void tick();

    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, initialStatus, rev]);

  const refresh = useCallback(() => refreshRef.current(), []);
  return {status, loading, refresh};
}
