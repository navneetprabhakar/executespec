import {describe, expect, it, vi} from 'vitest';

import {retryRunSameRoute, retryRunWithProvider} from './providerRetry';

describe('retryRunSameRoute', () => {
  it('forks without an override so the persisted exact provider/model route is retained', async () => {
    const forkRun = vi.fn().mockResolvedValue({runId: 'fork-1'});

    await retryRunSameRoute(forkRun, 'source-1', 'retry-key');

    expect(forkRun).toHaveBeenCalledWith('source-1', undefined, 'retry-key');
  });
});

describe('retryRunWithProvider', () => {
  it('forks the persisted source run with only a provider override', async () => {
    const forkRun = vi.fn().mockResolvedValue({runId: 'fork-1'});

    await retryRunWithProvider(forkRun, 'source-1', 'OPENAI', 'retry-key');

    expect(forkRun).toHaveBeenCalledWith('source-1', {aiProvider: 'OPENAI'}, 'retry-key');
  });
});
