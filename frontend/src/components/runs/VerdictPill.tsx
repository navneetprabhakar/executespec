/**
 * VerdictPill — collapses the ValidationReport's 4 underlying signals
 * (final build, test pass rate, reviewer verdict, verified score) into one
 * of three human-readable verdicts: `Shippable / Needs review / Blocked`.
 *
 * Plan 1 (this-week #3): the dual-score `Verified 92 · Est. 97` UI was
 * confusing — users had to know what "Verified" vs "Est." meant before they
 * could decide what to do. The pill leads with the decision; numeric scores
 * stay available as secondary disclosure (SignalBreakdown, ScoreCard's
 * dimension bars, the Show-details panel).
 */

import {CheckCircle2, AlertTriangle, XCircle} from 'lucide-react';

export type Verdict = 'shippable' | 'needs-review' | 'blocked';

export interface VerdictSignals {
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsTotal?: number | null;
  reviewerVerdict?: 'CLEAN' | 'FIXED_ISSUES' | 'UNRESOLVED_ISSUES' | 'REVIEW_FAILED' | null;
  verifiedScore?: number | null;
  /** Authoritative status reason from V2RunOrchestrator, if present. */
  statusReason?: string | null;
}

export interface VerdictResult {
  verdict: Verdict;
  /** One-line human-readable explanation surfaced via tooltip + a11y. */
  reason: string;
}

/**
 * Decision tree, evaluated top-down. First matching rule wins.
 *
 * Returns `null` only when there's no signal at all — pre-v2.5.10 runs and
 * runs that never reached the validation phase.
 */
export function deriveVerdict(s: VerdictSignals): VerdictResult | null {
  const hasAnySignal =
    s.finalBuildPassed != null ||
    s.testsTotal != null ||
    s.reviewerVerdict != null ||
    s.verifiedScore != null ||
    s.statusReason != null;
  if (!hasAnySignal) return null;

  // Hard blocks first.
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

  // Yellow-light: caller should look before merging.
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
        ? `Verified ${s.verifiedScore}/100, build green, reviewer ${s.reviewerVerdict ?? 'clean'}.`
        : 'Build green, reviewer clean.',
  };
}

const STYLES: Record<Verdict, {wrap: string; label: string; Icon: typeof CheckCircle2}> = {
  shippable: {
    wrap: 'border-status-success/30 bg-status-success/10 text-status-success',
    label: 'Shippable',
    Icon: CheckCircle2,
  },
  'needs-review': {
    wrap: 'border-status-waiting/30 bg-status-waiting/10 text-status-waiting',
    label: 'Needs review',
    Icon: AlertTriangle,
  },
  blocked: {
    wrap: 'border-status-error/30 bg-status-error/10 text-status-error',
    label: 'Blocked',
    Icon: XCircle,
  },
};

export interface VerdictPillProps extends VerdictSignals {
  className?: string;
  /** "lg" reads better in the report panel header; "sm" for inline use. */
  size?: 'sm' | 'lg';
}

export default function VerdictPill({className, size = 'sm', ...signals}: VerdictPillProps) {
  const result = deriveVerdict(signals);
  if (!result) return null;
  const {wrap, label, Icon} = STYLES[result.verdict];
  const sizeCls = size === 'lg' ? 'text-xs px-2.5 py-1 gap-1.5' : 'text-[10px] px-1.5 py-0.5 gap-1';
  const iconSize = size === 'lg' ? 14 : 11;
  return (
    <span
      className={`inline-flex items-center rounded-md border font-medium tracking-wide ${sizeCls} ${wrap} ${className ?? ''}`}
      title={result.reason}
      aria-label={`${label}: ${result.reason}`}
      role='status'
    >
      <Icon size={iconSize} aria-hidden />
      {label}
    </span>
  );
}
