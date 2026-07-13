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
  verificationLedger?: readonly {source?: string; confidence?: string}[] | null;
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

/**
 * Shared score rendering for compact run lists. A score without its ledger is
 * an internal summary, not independently verified correctness; keep the
 * numeric value but make that limitation visible and use the warning tone.
 */
export function ScoreBadge({score, verificationLedger, compact = false}: ScoreBadgeProps) {
  const hasLedger = !!verificationLedger?.length;
  const trust = scoreTrustLabel(verificationLedger);
  const label = hasLedger ? `${score}` : compact ? `${score} · ?` : `${score} · unverified`;
  const title = hasLedger
    ? 'Internal score; open the result Evidence tab to inspect the verification ledger.'
    : 'Internal score only. Verification evidence is not loaded in this run list.';
  return (
    <span title={title} aria-label={`${score} out of 100, ${trust}`}>
      <IdeBadge kind={hasLedger ? scoreKind(score) : 'warn'}>{label}</IdeBadge>
    </span>
  );
}
