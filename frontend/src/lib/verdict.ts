/**
 * Single source of truth for run verdicts.
 *
 * Background: before this module, four surfaces re-derived the verdict
 * locally and disagreed on the same run:
 *   - v2 RunsPage row    → showed "Shippable"
 *   - v2 ProjectDashboard → showed "Ready"
 *   - v3 ResultCard       → showed "Needs review"
 *   - v3 RunDetailPage    → showed "Needs review" + "What went wrong" panel
 * The new-user product audit (docs/audits/product-review-2026-05-06.md, P0-3)
 * called this out as a credibility-class bug. Since then every consumer
 * imports `deriveVerdict` from here.
 *
 * Public types:
 *   - `Verdict` (lowercase canonical):  shippable | needs-review | blocked
 *   - `VerdictSignals`: the inputs every UI feeds in
 *   - `VerdictResult`:  { verdict, reason } — `reason` is one human line
 *
 * Adapter helpers cover the v3 surfaces that still want the SCREAMING_SNAKE
 * `READY/NEEDS_REVIEW/FAILED` labels — see `toV3Verdict`.
 */

export type Verdict = 'shippable' | 'needs-review' | 'blocked';

/** v3 surfaces (ResultCard, RunDetailPage) historically used this casing. */
export type V3Verdict = 'READY' | 'NEEDS_REVIEW' | 'FAILED';

export interface VerdictSignals {
  /** Backend run status — needed so we don't claim shippable on an in-flight run. */
  status?:
    | 'PLANNING'
    | 'AWAITING_APPROVAL'
    | 'WORKING'
    | 'REVIEWING'
    | 'COMPLETE'
    | 'PARTIAL'
    | 'FAILED'
    | 'CANCELLED'
    | 'ABORTED'
    | string
    | null;
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsTotal?: number | null;
  reviewerVerdict?: 'CLEAN' | 'FIXED_ISSUES' | 'UNRESOLVED_ISSUES' | 'REVIEW_FAILED' | null;
  verifiedScore?: number | null;
  /** Authoritative status reason from V2 RunOrchestrator, if present. */
  statusReason?: string | null;
}

export interface VerdictResult {
  verdict: Verdict;
  /** One-line human-readable explanation surfaced via tooltip + a11y. */
  reason: string;
}

/** Statuses for which "verdict" is meaningless — the run is still moving. */
const IN_FLIGHT_STATUSES = new Set([
  'PLANNING',
  'AWAITING_APPROVAL',
  'WORKING',
  'REVIEWING',
  'QUEUED',
  'PAUSED',
]);

/**
 * Decision tree, evaluated top-down. First matching rule wins.
 *
 * Returns `null` when:
 *   - the run is still in-flight (any IN_FLIGHT_STATUSES), OR
 *   - there's no signal at all (pre-v2.5.10 runs that never reached
 *     validation).
 *
 * Callers should treat `null` as "don't render a verdict" — show the
 * status badge alone.
 */
export function deriveVerdict(s: VerdictSignals): VerdictResult | null {
  if (s.status && IN_FLIGHT_STATUSES.has(s.status)) return null;

  const hasAnySignal =
    s.finalBuildPassed != null ||
    s.testsTotal != null ||
    s.reviewerVerdict != null ||
    s.verifiedScore != null ||
    s.statusReason != null ||
    (s.status != null && (s.status === 'FAILED' || s.status === 'CANCELLED' || s.status === 'ABORTED'));
  if (!hasAnySignal) return null;

  // Terminal "didn't even finish" wins everything else.
  if (s.status === 'CANCELLED' || s.status === 'ABORTED') {
    return {verdict: 'blocked', reason: 'The run was cancelled before it finished.'};
  }

  // Hard blocks.
  if (s.statusReason === 'FINAL_BUILD_FAILED' || s.finalBuildPassed === false) {
    return {verdict: 'blocked', reason: 'Final build failed — code may not run as written.'};
  }
  if (s.statusReason === 'ALL_TASKS_FAILED') {
    return {verdict: 'blocked', reason: 'Every task failed; nothing was successfully implemented.'};
  }
  if (s.reviewerVerdict === 'REVIEW_FAILED' || s.statusReason === 'REVIEWER_FAILED') {
    return {
      verdict: 'blocked',
      reason: 'Reviewer could not complete its passes; treat the run as unverified.',
    };
  }
  if (s.verifiedScore != null && s.verifiedScore < 50) {
    return {
      verdict: 'blocked',
      reason: `Verified score ${s.verifiedScore}/100 is below the acceptance bar (50).`,
    };
  }

  // Yellow-light.
  if (s.reviewerVerdict === 'UNRESOLVED_ISSUES' || s.statusReason === 'REVIEWER_UNRESOLVED') {
    return {
      verdict: 'needs-review',
      reason: 'Reviewer flagged issues it could not auto-fix; inspect the report.',
    };
  }
  if (s.statusReason != null && s.statusReason.startsWith('TESTS_FAILED')) {
    return {
      verdict: 'needs-review',
      reason: 'One or more tests failed; review the test report before shipping.',
    };
  }
  if (
    s.testsTotal != null &&
    s.testsTotal > 0 &&
    s.testsPassed != null &&
    s.testsPassed < s.testsTotal
  ) {
    return {
      verdict: 'needs-review',
      reason: `${s.testsPassed}/${s.testsTotal} tests passed; review the failures before shipping.`,
    };
  }
  if (s.verifiedScore != null && s.verifiedScore < 85) {
    return {
      verdict: 'needs-review',
      reason: `Verified score ${s.verifiedScore}/100 is below the auto-ship threshold (85).`,
    };
  }

  return {
    verdict: 'shippable',
    reason:
      s.verifiedScore != null
        ? `Verified ${s.verifiedScore}/100, build green, reviewer ${s.reviewerVerdict?.toLowerCase() ?? 'clean'}.`
        : s.statusReason === 'CLEAN_COMPLETION'
          ? 'Clean completion: build green, reviewer clean.'
          : 'Build green, reviewer clean.',
  };
}

/** Adapter for v3 surfaces still using SCREAMING_SNAKE labels. */
export function toV3Verdict(r: VerdictResult | null): V3Verdict | null {
  if (!r) return null;
  if (r.verdict === 'shippable') return 'READY';
  if (r.verdict === 'needs-review') return 'NEEDS_REVIEW';
  return 'FAILED';
}

/** Tone helper for v3 Pill components (ok/warn/err/neutral). */
export function verdictTone(v: Verdict | V3Verdict | null): 'ok' | 'warn' | 'err' | 'neutral' {
  if (v === 'shippable' || v === 'READY') return 'ok';
  if (v === 'needs-review' || v === 'NEEDS_REVIEW') return 'warn';
  if (v === 'blocked' || v === 'FAILED') return 'err';
  return 'neutral';
}
