import {afterEach, describe, expect, it, vi} from 'vitest';
import {uiBound} from './projectMoreUtils';

describe('system-map request bounds', () => {
  afterEach(() => vi.useRealTimers());

  it('settles a hanging auxiliary request at the UI deadline', async () => {
    vi.useFakeTimers();
    const bounded = uiBound(new Promise<string>(() => {}), 100);
    const assertion = expect(bounded).rejects.toThrow('Request timed out.');

    await vi.advanceTimersByTimeAsync(100);
    await assertion;
  });

  it('returns primary architecture data without waiting for the deadline', async () => {
    await expect(uiBound(Promise.resolve('architecture'), 100)).resolves.toBe('architecture');
  });
});
