import {describe, expect, it} from 'vitest';

import type {RunCostEvidence, RunDetail} from '@/lib/api';
import {costReconciliation, phaseCostRows} from './runCostLedger';

const evidence = {
  runId: 'r1',
  schemaVersion: 1,
  source: 'stored_total',
  costUsd: 0.5,
  inputTokens: 10,
  outputTokens: 5,
  cacheReadTokens: 3,
  cacheWriteTokens: 0,
  reasoningTokens: 2,
  calls: 2,
  phases: {classifier: 0.01, outline: 0.02, expansion: 0.03, coherence: 0.04},
  stepCalls: [],
  attributedCostUsd: 0.45,
  unattributedCostUsd: 0.05,
  reconciled: false,
} satisfies RunCostEvidence;

describe('run cost evidence', () => {
  it('includes coherence in the phase ledger', () => {
    expect(phaseCostRows(null, evidence)).toContainEqual(['Coherence', 0.04]);
  });

  it('shows an explicit remainder instead of claiming partial calls reconcile', () => {
    expect(costReconciliation(evidence)).toEqual({
      total: 0.5,
      attributed: 0.45,
      remainder: 0.05,
      reconciled: false,
    });
  });

  it('falls back to persisted phase costs when live evidence is unavailable', () => {
    expect(phaseCostRows({coherenceCostUsd: 0.07} as RunDetail, null)).toContainEqual([
      'Coherence',
      0.07,
    ]);
  });
});
