import type {RunCostEvidence, RunDetail, RunStepCostCall} from '@/lib/api';

export function phaseCostRows(run: RunDetail | null, evidence: RunCostEvidence | null) {
  const phases = evidence?.phases;
  return [
    ['Classifier', phases?.classifier ?? run?.classifierCostUsd ?? 0],
    ['Outline', phases?.outline ?? run?.outlineCostUsd ?? 0],
    ['Expansion', phases?.expansion ?? run?.expansionCostUsd ?? 0],
    ['Coherence', phases?.coherence ?? run?.coherenceCostUsd ?? 0],
  ] as const;
}

export function costReconciliation(evidence: RunCostEvidence | null) {
  if (!evidence) return null;
  return {
    total: evidence.costUsd,
    attributed: evidence.attributedCostUsd,
    remainder: evidence.unattributedCostUsd,
    reconciled: evidence.reconciled,
  };
}

/** Never turn an unpriced or failed physical attempt into a fabricated $0.0000. */
export function knownCallCost(call: RunStepCostCall): number | null {
  if (call.knownCostUsd === null || call.costStatus?.startsWith('UNKNOWN')) return null;
  const value = call.knownCostUsd ?? call.costUsd;
  return Number.isFinite(value) && value >= 0 ? value : null;
}

/** Compact physical-attempt causality for the operator ledger. */
export function attemptSummary(call: RunStepCostCall): string {
  const kind = call.attemptKind || (call.retryOf ? 'RETRY' : 'PRIMARY');
  const ordinal = call.attemptNumber && call.attemptNumber > 0 ? ` #${call.attemptNumber}` : '';
  return `${kind}${ordinal}`;
}

/**
 * RR-1552 — which of the four genuinely different per-call-ledger states this response is in.
 *
 * The run cost endpoint now restricts the per-attempt ledger to own-org ORG_ADMIN and
 * SUPER_ADMIN and returns `stepCalls: []` plus `stepCallsIncluded: false` to everyone else.
 * The run-result page previously rendered that identically to a run that made no provider
 * calls at all ("No per-call attribution is available for this run"), which is an affirmative
 * false statement about the run rather than about the reader's authority.
 *
 * `stepCallsIncluded` is optional so a response from a server predating the gate — where the
 * ledger was always attached — still reads as `rows`, never as restricted.
 */
export type StepCallsView =
  | {kind: 'unavailable'}
  | {kind: 'restricted'; total: number; reason: string | null}
  | {kind: 'empty'}
  | {kind: 'rows'; rows: RunCostEvidence['stepCalls']; total: number; hasMore: boolean};

export function stepCallsView(evidence: RunCostEvidence | null): StepCallsView {
  if (!evidence) return {kind: 'unavailable'};
  if (evidence.stepCallsIncluded === false) {
    return {
      kind: 'restricted',
      total: evidence.stepCallsTotal ?? 0,
      reason: evidence.stepCallsRestrictedReason ?? null,
    };
  }
  const rows = evidence.stepCalls ?? [];
  if (rows.length === 0) return {kind: 'empty'};
  return {
    kind: 'rows',
    rows,
    total: evidence.stepCallsTotal ?? rows.length,
    hasMore: evidence.stepCallsHasMore === true,
  };
}
