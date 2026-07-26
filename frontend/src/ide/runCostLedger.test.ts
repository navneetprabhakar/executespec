import {describe, expect, it} from 'vitest';

import type {RunCostEvidence, RunDetail} from '@/lib/api';
import {
  attemptSummary,
  costReconciliation,
  knownCallCost,
  phaseCostRows,
  stepCallsView,
} from './runCostLedger';

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

const stepCall = (
  overrides: Partial<RunCostEvidence['stepCalls'][number]>,
): RunCostEvidence['stepCalls'][number] => ({
  id: 'call-1',
  taskId: 'task-1',
  agentRole: 'worker',
  stepId: 'step-1',
  retryOf: null,
  provider: 'deepseek',
  model: 'deepseek-v4-pro-native',
  inputTokens: 10,
  outputTokens: 5,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
  reasoningTokens: 0,
  costUsd: 0,
  callIndex: 1,
  createdAt: '2026-07-26T00:00:00Z',
  ...overrides,
});

describe('run cost evidence', () => {
  it('includes coherence in the phase ledger', () => {
    expect(phaseCostRows(null, evidence)).toContainEqual(['Coherence', 0.04]);
  });

  it('does not present an unknown negative sentinel as free provider spend', () => {
    const call = stepCall({
      costUsd: -1,
      knownCostUsd: null,
      costStatus: 'UNKNOWN_PROVIDER_NOT_REPORTED',
    });
    expect(knownCallCost(call)).toBeNull();
  });

  it('keeps a genuine known zero-cost call visible as zero', () => {
    const call = stepCall({
      costUsd: 0,
      knownCostUsd: 0,
      costStatus: 'KNOWN',
    });
    expect(knownCallCost(call)).toBe(0);
  });

  it('labels physical retry causality and attempt ordinal', () => {
    const call = stepCall({
      attemptKind: 'SAME_PROVIDER_RETRY',
      attemptNumber: 3,
      costUsd: 0,
    });
    expect(attemptSummary(call)).toBe('SAME_PROVIDER_RETRY #3');
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

// RR-1552 — the per-attempt ledger is now restricted to own-org ORG_ADMIN / SUPER_ADMIN, and
// the withheld case must not be presented as "this run made no provider calls".
describe('per-call ledger visibility', () => {
  it('reports a withheld ledger as restricted, not as an empty run', () => {
    const view = stepCallsView({
      ...evidence,
      stepCalls: [],
      stepCallsIncluded: false,
      stepCallsRestrictedReason: 'ORG_ADMIN_OR_SUPER_ADMIN_REQUIRED',
      stepCallsTotal: 42,
    });
    expect(view).toEqual({
      kind: 'restricted',
      total: 42,
      reason: 'ORG_ADMIN_OR_SUPER_ADMIN_REQUIRED',
    });
  });

  it('still reports a genuinely empty ledger as empty', () => {
    expect(stepCallsView({...evidence, stepCalls: [], stepCallsIncluded: true})).toEqual({
      kind: 'empty',
    });
  });

  it('treats a response without the gate field as an included ledger', () => {
    // A server predating RR-1552 always attached the ledger; absence of the flag must
    // never be read as a withheld ledger.
    expect(stepCallsView(evidence)).toEqual({kind: 'empty'});
  });

  it('surfaces paging so a truncated page does not read as the whole run', () => {
    const call = stepCall({id: 'c1'});
    expect(
      stepCallsView({
        ...evidence,
        stepCalls: [call],
        stepCallsIncluded: true,
        stepCallsTotal: 900,
        stepCallsHasMore: true,
      }),
    ).toEqual({kind: 'rows', rows: [call], total: 900, hasMore: true});
  });

  it('has no view at all before evidence loads', () => {
    expect(stepCallsView(null)).toEqual({kind: 'unavailable'});
  });
});
