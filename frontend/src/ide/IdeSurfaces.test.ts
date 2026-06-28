import {describe, expect, it} from 'vitest';

import {buildIdeComposeRunRequest} from './ideComposeRunRequest';

describe('buildIdeComposeRunRequest', () => {
  it('omits the benchmark-prior override when the guard is unchecked', () => {
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
      figmaUrl: '',
      tier: 'PRO',
      modelName: '',
    });

    expect(body.userRequest).toBe('fix the failing login test');
    expect(body.requireBenchmarkPriorForAutomaticContinuation).toBeUndefined();
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
      figmaUrl: 'https://figma.example/file/1',
      modelName: 'gpt-5.4',
    });
  });
});
