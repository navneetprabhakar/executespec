import type {RunCostEvidence, RunDetail} from '@/lib/api';

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
