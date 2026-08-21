import {describe, expect, it} from 'vitest';

import {buildIdeComposeRunRequest} from './ideComposeRunRequest';
import {runPhaseLabel} from './runPhaseLabel';
import type {WorkspaceGenerationAttemptEvidence} from '../lib/api/workspaceGenerationEvidence';

function generationAttempt(
  overrides: Partial<WorkspaceGenerationAttemptEvidence>,
): WorkspaceGenerationAttemptEvidence {
  return {
    attemptId: 'attempt-1',
    taskId: 'task-1',
    taskIndex: 0,
    attemptNumber: 1,
    attemptKind: 'INITIAL',
    state: 'RECOVERY_FAILED',
    beforeGeneration: 'before',
    createdAt: '2026-08-13T09:00:00Z',
    ...overrides,
  };
}

describe('runPhaseLabel', () => {
  it('renders retrying and exhausted workspace recovery instead of generic running copy', () => {
    expect(
      runPhaseLabel({
        status: 'WORKING',
        workspaceGenerationAttempts: [generationAttempt({recoveryAttemptCount: 2})],
      }),
    ).toBe('Recovery retry scheduled');
    expect(
      runPhaseLabel({
        status: 'FAILED',
        workspaceGenerationAttempts: [
          generationAttempt({recoveryExhaustedAt: '2026-08-13T10:00:00Z'}),
        ],
      }),
    ).toBe('Workspace recovery failed');
  });

  it('keeps cancelled state truthful after recovery convergence', () => {
    expect(runPhaseLabel({status: 'CANCELLED', workspaceGenerationAttempts: []})).toBe('Cancelled');
  });
});

describe('buildIdeComposeRunRequest', () => {
  it('sends explicit run-level recovery authority when guards are unchecked', () => {
    const body = buildIdeComposeRunRequest({
      spec: ' fix the failing login test ',
      provider: 'ANTHROPIC',
      mode: 'PRODUCTION',
      projectId: null,
      selectedRepoIds: [],
      costCap: 0,
      tddMode: false,
      confirmModelUpgrades: false,
      requireBenchmarkedUpgrades: false,
      startLowTasksOnCheapTier: false,
      figmaUrl: '',
      tier: 'PRO',
      modelName: '',
    });

    expect(body.userRequest).toBe('fix the failing login test');
    expect(body.requireConfirmationBeforeExpensiveContinuation).toBe(false);
    expect(body.requireBenchmarkPriorForAutomaticContinuation).toBe(false);
    expect(body.lowComplexityWorkerStartTier).toBeUndefined();
  });

  it('sends an explicit benchmark-prior guard only when checked', () => {
    const body = buildIdeComposeRunRequest({
      spec: 'ship checkout retry',
      provider: 'OPENAI',
      mode: 'PRODUCTION',
      projectId: 'p1',
      selectedRepoIds: ['r1'],
      costCap: 3,
      tddMode: true,
      confirmModelUpgrades: true,
      requireBenchmarkedUpgrades: true,
      startLowTasksOnCheapTier: true,
      figmaUrl: 'https://figma.example/file/1',
      tier: 'ENTERPRISE',
      modelName: 'gpt-5.4',
    });

    expect(body).toMatchObject({
      userRequest: 'ship checkout retry',
      aiProvider: 'OPENAI',
      projectId: 'p1',
      selectedRepoIds: ['r1'],
      userCostCap: 3,
      tddMode: true,
      requireConfirmationBeforeExpensiveContinuation: true,
      requireBenchmarkPriorForAutomaticContinuation: true,
      lowComplexityWorkerStartTier: 'LIGHTWEIGHT',
      figmaUrl: 'https://figma.example/file/1',
      modelName: 'gpt-5.4',
    });
  });
});
