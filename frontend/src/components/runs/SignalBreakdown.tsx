/**
 * SignalBreakdown — v2.5.10 (verdict-leading sweep, Plan 1 #3).
 *
 * Compact horizontal chip summarising the THREE concrete signals that feed
 * the run's verdict:
 *
 *   Build ✓ · Tests 73/73 · Reviewer CLEAN
 *
 * The numeric Verified + Est segments were removed in the verdict-pill
 * sweep — they collapse into VerdictPill's Shippable/Needs review/Blocked
 * decision (still surfaced via ScoreCard + Show-details disclosure).
 * `verifiedScore` + `accuracyScore` props remain on the public API so
 * existing callers don't break, but are now ignored.
 *
 * Each segment is coloured independently so a green Build next to a red
 * Tests is legible at a glance.
 *
 * All fields are optional — pre-v2.5.10 validation reports just won't have
 * them, in which case the respective segment is hidden.
 */
interface SignalBreakdownProps {
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsTotal?: number | null;
  reviewerVerdict?: 'CLEAN' | 'FIXED_ISSUES' | 'UNRESOLVED_ISSUES' | 'REVIEW_FAILED' | null;
  /** Retained for callsite compatibility — collapsed into VerdictPill. */
  verifiedScore?: number | null;
  /** Retained for callsite compatibility — collapsed into VerdictPill. */
  accuracyScore?: number | null;
  className?: string;
}

type Tone = 'green' | 'amber' | 'red' | 'muted';

// v2.5.12 — token-aligned. Previously used raw `emerald/amber/red/zinc`
// Tailwind classes that sidestepped the design system. Now anchored on the
// platform status-* + gray-* CSS variables so colour is consistent with
// every other status surface.
const TONES: Record<Tone, string> = {
  green: 'text-status-success border-status-success/30 bg-status-success/10',
  amber: 'text-status-waiting border-status-waiting/30 bg-status-waiting/10',
  red:   'text-status-error   border-status-error/30   bg-status-error/10',
  muted: 'text-muted border-[var(--border-default)] bg-[var(--gray-200)]',
};

function Segment({ tone, label, title }: { tone: Tone; label: string; title?: string }) {
  return (
    <span
      className={`inline-flex items-center rounded-sm border px-1.5 py-0.5 text-[10px] font-medium tracking-wide ${TONES[tone]}`}
      title={title}
    >
      {label}
    </span>
  );
}

function verdictTone(v: SignalBreakdownProps['reviewerVerdict']): Tone {
  switch (v) {
    case 'CLEAN': return 'green';
    case 'FIXED_ISSUES': return 'green';
    case 'UNRESOLVED_ISSUES': return 'amber';
    case 'REVIEW_FAILED': return 'red';
    default: return 'muted';
  }
}

export default function SignalBreakdown({
  finalBuildPassed,
  testsPassed,
  testsTotal,
  reviewerVerdict,
  verifiedScore: _verifiedScore,
  accuracyScore: _accuracyScore,
  className,
}: SignalBreakdownProps) {
  // Hide the chip entirely on runs that have none of the (now 3) signals.
  const hasAny =
    finalBuildPassed != null ||
    testsTotal != null ||
    reviewerVerdict != null;
  if (!hasAny) return null;

  return (
    <div className={`flex flex-wrap items-center gap-1.5 ${className ?? ''}`}>
      {finalBuildPassed != null && (
        <Segment
          tone={finalBuildPassed ? 'green' : 'red'}
          label={`Build ${finalBuildPassed ? '✓' : '✗'}`}
          title={finalBuildPassed
            ? 'Final build passed (reviewer confirmed)'
            : 'Final build failed — code may not run'}
        />
      )}
      {testsTotal != null && testsTotal >= 0 && (
        <Segment
          tone={
            testsTotal === 0
              ? 'muted'
              : testsPassed === testsTotal
                ? 'green'
                : (testsPassed ?? 0) >= testsTotal * 0.8
                  ? 'amber'
                  : 'red'
          }
          label={
            testsTotal === 0
              ? 'No tests'
              : `Tests ${testsPassed ?? 0}/${testsTotal}`
          }
          title={testsTotal === 0
            ? 'Test generation did not run or produced no tests'
            : `${testsPassed ?? 0} of ${testsTotal} test cases passed in the async test-gen phase`}
        />
      )}
      {reviewerVerdict && (
        <Segment
          tone={verdictTone(reviewerVerdict)}
          label={`Reviewer ${reviewerVerdict.replace(/_/g, ' ')}`}
          title={`ReviewerAgentV3 verdict: ${reviewerVerdict}`}
        />
      )}
    </div>
  );
}
