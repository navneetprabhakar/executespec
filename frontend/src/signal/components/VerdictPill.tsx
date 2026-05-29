import {StatusPill} from '@/signal/components/Primitives';

//
// One canonical mapping from (verifiedScore, run status) → a plain-English
// verdict, used by every Signal surface that shows a run outcome. This kills
// the threshold drift where some surfaces called ≥90 "Shippable" while the
// 4-band score palette (RunCard / ScoreRing / HomePage / ResultPage) treats
// ≥85 as the green band.
//
// Bands (aligned with the 4-band `--signal-*` score palette + IdeRunResult):
//   • Shippable    — verifiedScore ≥ 85          → green  (StatusPill `shipped`)
//   • Needs review — 70 ≤ verifiedScore < 85     → amber  (StatusPill `needs`)
//   • Failed       — verifiedScore < 70, OR the  → red    (StatusPill `failed`)
//                    run terminated FAILED/ERROR/CANCELLED
//   • In progress  — no score yet on a non-terminal run → muted default pill
//
// A terminal-failed status wins over the numeric score so a FAILED run can't
// read "Shippable" off a stale score, and a missing score on a live run reads
// "In progress" instead of falling through to "Failed".

export type VerdictKind = 'shipped' | 'needs' | 'failed' | 'progress';

export interface Verdict {
  label: string;
  /** Maps to the `StatusPill` kind / 4-band score palette. */
  kind: VerdictKind;
}

const TERMINAL_FAILED = new Set(['FAILED', 'ERROR', 'CANCELLED']);

/**
 * Canonical verdict for a run. Pass the run's `verifiedScore` (or null when
 * not yet scored) and its status string.
 */
export function verdictFor(score: number | null | undefined, status?: string | null): Verdict {
  const s = (status || '').toUpperCase();
  if (TERMINAL_FAILED.has(s)) return {label: 'Failed', kind: 'failed'};
  if (score == null) {
    return {
      label: s === 'COMPLETED' || s === 'COMPLETE' ? 'Complete' : 'In progress',
      kind: 'progress',
    };
  }
  if (score >= 85) return {label: 'Shippable', kind: 'shipped'};
  if (score >= 70) return {label: 'Needs review', kind: 'needs'};
  return {label: 'Failed', kind: 'failed'};
}

/**
 * Shared verdict pill. Renders the canonical verdict for `(score, status)`
 * using the band palette from `StatusPill` so the colour matches the score
 * chips everywhere else.
 */
export function VerdictPill({score, status}: {score?: number | null; status?: string | null}) {
  const v = verdictFor(score, status);
  // `StatusPill` only knows live | shipped | needs | failed; the "progress"
  // verdict has no score yet, so it falls through to the muted default pill.
  const pillKind = v.kind === 'progress' ? undefined : v.kind;
  return <StatusPill kind={pillKind}>{v.label}</StatusPill>;
}
