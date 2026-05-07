/**
 * VerdictPill — collapses the ValidationReport's signals into one of three
 * human-readable verdicts: `Shippable / Needs review / Blocked`.
 *
 * Plan 1 (#3): the dual-score `Verified 92 · Est. 97` UI was confusing —
 * users had to learn what "Verified" vs "Est." meant before they could
 * decide what to do. The pill leads with the decision; numeric scores stay
 * available as secondary disclosure (SignalBreakdown, ScoreCard, the
 * Show-details panel).
 *
 * Audit r5 (P0-3, 2026-05-06): the `deriveVerdict` decision tree moved to
 * `lib/verdict.ts` so v3 ResultCard + RunDetailPage stop maintaining their
 * own copies. Re-exports here keep all v2 imports green.
 */

import {CheckCircle2, AlertTriangle, XCircle} from 'lucide-react';
import {
  deriveVerdict as canonicalDeriveVerdict,
  type Verdict as CanonicalVerdict,
  type VerdictResult as CanonicalVerdictResult,
  type VerdictSignals as CanonicalVerdictSignals,
} from '@/lib/verdict';

// Re-export so existing call sites in v2 don't break.
export type Verdict = CanonicalVerdict;
export type VerdictSignals = CanonicalVerdictSignals;
export type VerdictResult = CanonicalVerdictResult;
export const deriveVerdict = canonicalDeriveVerdict;

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
