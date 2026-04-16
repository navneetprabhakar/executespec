/**
 * SignalBreakdown — v2.5.10.
 *
 * Compact horizontal chip summarising the 5 authoritative signals that feed
 * the run's verified score + status reason:
 *
 *   Build ✓ · Tests 73/73 · Reviewer CLEAN · Verified 92 · Est. 97
 *
 * Designed to sit next to or below the StatusBadge so users can see WHY a run
 * is labelled COMPLETE / PARTIAL without drilling into the full report.
 * Each segment is coloured independently so a green Build next to a red Tests
 * is legible at a glance.
 *
 * All fields are optional — pre-v2.5.10 validation reports just won't have
 * them, in which case the respective segment is hidden.
 */
interface SignalBreakdownProps {
  finalBuildPassed?: boolean | null;
  testsPassed?: number | null;
  testsTotal?: number | null;
  reviewerVerdict?: 'CLEAN' | 'FIXED_ISSUES' | 'UNRESOLVED_ISSUES' | 'REVIEW_FAILED' | null;
  verifiedScore?: number | null;
  /** LLM accuracy score — shown as secondary "Est." segment. */
  accuracyScore?: number | null;
  className?: string;
}

type Tone = 'green' | 'amber' | 'red' | 'muted';

const TONES: Record<Tone, string> = {
  green: 'text-emerald-400 border-emerald-500/30 bg-emerald-500/10',
  amber: 'text-amber-400 border-amber-500/30 bg-amber-500/10',
  red: 'text-red-400 border-red-500/30 bg-red-500/10',
  muted: 'text-zinc-400 border-zinc-600/40 bg-zinc-800/40',
};

function Segment({ tone, label, title }: { tone: Tone; label: string; title?: string }) {
  return (
    <span
      className={`inline-flex items-center rounded-md border px-1.5 py-0.5 text-[10px] font-medium tracking-wide ${TONES[tone]}`}
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

function scoreTone(score?: number | null): Tone {
  if (score == null) return 'muted';
  if (score >= 85) return 'green';
  if (score >= 70) return 'amber';
  return 'red';
}

export default function SignalBreakdown({
  finalBuildPassed,
  testsPassed,
  testsTotal,
  reviewerVerdict,
  verifiedScore,
  accuracyScore,
  className,
}: SignalBreakdownProps) {
  // Hide the chip entirely on runs that have none of the signals — otherwise
  // pre-v2.5.10 runs would show an empty ghost.
  const hasAny =
    finalBuildPassed != null ||
    testsTotal != null ||
    reviewerVerdict != null ||
    verifiedScore != null ||
    accuracyScore != null;
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
      {verifiedScore != null && (
        <Segment
          tone={scoreTone(verifiedScore)}
          label={`Verified ${verifiedScore}`}
          title="Deterministic score from build + tests + reviewer verdict (v2.5.10)"
        />
      )}
      {accuracyScore != null && (
        <Segment
          tone="muted"
          label={`Est. ${accuracyScore}`}
          title="LLM-estimated spec coverage from file tree + source snippets (secondary signal)"
        />
      )}
    </div>
  );
}
