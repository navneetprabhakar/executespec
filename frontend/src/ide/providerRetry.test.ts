import {describe, expect, it, vi} from 'vitest';

import {retryRunWithProvider} from './providerRetry';

describe('retryRunWithProvider', () => {
  it('forks the persisted source run with only a provider override', async () => {
    const forkRun = vi.fn().mockResolvedValue({runId: 'fork-1'});

    await retryRunWithProvider(forkRun, 'source-1', 'OPENAI');

    expect(forkRun).toHaveBeenCalledWith('source-1', {aiProvider: 'OPENAI'});
  });
});
