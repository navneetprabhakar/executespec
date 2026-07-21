import {describe, expect, it} from 'vitest';
import type {LlmUsageRollup} from '@/lib/api';
import {summarizeUsageRollups} from './LlmUsageAuditPanel';

function row(overrides: Partial<LlmUsageRollup>): LlmUsageRollup {
  return {
    userId: 'user-1',
    userEmail: 'developer@example.com',
    userRole: 'DEVELOPER',
    provider: 'deepseek',
    model: 'deepseek-chat',
    agentRole: 'WorkerAgent',
    feature: 'WORKER',
    projectId: 'project-1',
    projectName: 'Project',
    runId: 'run-1',
    sessionId: null,
    sourceType: 'PIPELINE_RUN',
    sourceId: 'run-1',
    keySource: 'ORG',
    attemptStatus: 'SUCCESS',
    attempts: 1,
    inputTokens: 10,
    outputTokens: 5,
    cacheReadTokens: 2,
    cacheWriteTokens: 0,
    reasoningTokens: 3,
    knownCostUsdLowerBound: 0.01,
    unknownCostAttempts: 0,
    ...overrides,
  };
}

describe('summarizeUsageRollups', () => {
  it('aggregates failures, tokens, known cost, and unknown-cost evidence by dimension', () => {
    const result = summarizeUsageRollups(
      [
        row({attempts: 2, knownCostUsdLowerBound: 0.02}),
        row({attemptStatus: 'FAILED', attempts: 1, unknownCostAttempts: 1}),
      ],
      'provider',
    );

    expect(result).toEqual([
      expect.objectContaining({
        label: 'deepseek',
        attempts: 3,
        failedAttempts: 1,
        tokens: 30,
        cost: 0.03,
        unknownCostAttempts: 1,
      }),
    ]);
  });

  it('keeps missing attribution visible instead of dropping the row', () => {
    expect(
      summarizeUsageRollups([row({projectId: null, projectName: null})], 'project')[0].label,
    ).toBe('Unattributed');
  });

  it('categorizes physical attempts by the human user role independently of agent role', () => {
    const result = summarizeUsageRollups(
      [
        row({userRole: 'DEVELOPER', agentRole: 'WorkerAgent'}),
        row({userRole: 'ORG_ADMIN', agentRole: 'WorkerAgent', attempts: 2}),
      ],
      'userRole',
    );

    expect(result.map(({label, attempts}) => ({label, attempts}))).toEqual([
      {label: 'ORG_ADMIN', attempts: 2},
      {label: 'DEVELOPER', attempts: 1},
    ]);
  });
});
