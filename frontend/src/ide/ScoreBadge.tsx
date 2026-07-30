import {IdeBadge} from './IdeKit';

type ScoreKind = 'ok' | 'warn' | 'bad';

export type ScoreBadgeProps = {
  score: number;
  /**
   * Run-list DTOs do not include the verification ledger. An omitted ledger is
   * therefore deliberately treated as unverified rather than silently shown
   * as a green quality claim. Run-detail callers can pass the fetched ledger
   * once it is available.
   */
  verificationLedger?:
    | readonly {
        id?: string;
        source?: string;
        confidence?: string;
        summary?: string;
        artifact?: string;
      }[]
    | null;
  compact?: boolean;
};

function scoreKind(score: number): ScoreKind {
  return score >= 85 ? 'ok' : score >= 70 ? 'warn' : 'bad';
}

export function scoreTrustLabel(
  verificationLedger?: readonly {source?: string; confidence?: string}[] | null,
): 'unverified' | 'review evidence' {
  if (!verificationLedger || verificationLedger.length === 0) return 'unverified';
  return 'review evidence';
}

export function scoreZeroCause(
  score: number,
  verificationLedger?: readonly {id?: string; summary?: string; artifact?: string}[] | null,
): string | null {
  if (score !== 0) return null;
  const cause = verificationLedger?.find((item) => item.id === 'score-zero-cause');
  return cause?.summary?.trim() || null;
}

/**
 * Shared score rendering for compact run lists. A score without its ledger is
 * an internal summary, not independently verified correctness; keep the
 * numeric value but make that limitation visible and use the warning tone.
 */
export function ScoreBadge({score, verificationLedger, compact = false}: ScoreBadgeProps) {
  const hasLedger = !!verificationLedger?.length;
  const trust = scoreTrustLabel(verificationLedger);
  const zeroCause = scoreZeroCause(score, verificationLedger);
  const label = hasLedger
    ? zeroCause && !compact
      ? `${score} / 100 · ${zeroCause}`
      : `${score} / 100`
    : compact
      ? `${score} · unverified`
      : `${score} / 100 · unverified`;
  const title = zeroCause
    ? `Internal score is zero because: ${zeroCause}`
    : hasLedger
      ? 'Internal score; open the result Evidence tab to inspect the verification ledger.'
      : 'Internal score only. Verification evidence is not loaded in this run list.';
  return (
    <span title={title} aria-label={`${score} out of 100, ${trust}`}>
      <IdeBadge kind={hasLedger ? scoreKind(score) : 'warn'}>{label}</IdeBadge>
    </span>
  );
}
