import {describe, expect, it} from 'vitest';
import {ApiError} from '@/lib/api/transport';
import {
  executeRunMutation,
  RunMutationAttemptLedger,
  shouldRetainRunMutationAttempt,
} from '@/lib/runMutationAttempt';

class MemoryStorage implements Pick<Storage, 'getItem' | 'setItem' | 'removeItem'> {
  private readonly values = new Map<string, string>();
  getItem(key: string) {
    return this.values.get(key) ?? null;
  }
  setItem(key: string, value: string) {
    this.values.set(key, value);
  }
  removeItem(key: string) {
    this.values.delete(key);
  }
  serializedValues() {
    return [...this.values.values()].join('\n');
  }
}

describe('RunMutationAttemptLedger', () => {
  it('reuses one key for an ambiguous retry and a same-tab reload', async () => {
    const storage = new MemoryStorage();
    const first = new RunMutationAttemptLedger(storage, () => 'key-1');
    expect((await first.begin('create:project-1', {spec: 'build', provider: 'OPENAI'})).key).toBe(
      'key-1',
    );

    const afterReload = new RunMutationAttemptLedger(storage, () => 'key-2');
    expect(
      (await afterReload.begin('create:project-1', {provider: 'OPENAI', spec: 'build'})).key,
    ).toBe('key-1');
  });

  it('allocates a fresh key for changed or deliberately repeated work', async () => {
    const storage = new MemoryStorage();
    const keys = ['key-1', 'key-2', 'key-3'];
    const ledger = new RunMutationAttemptLedger(storage, () => {
      const key = keys.shift();
      if (!key) throw new Error('test key exhausted');
      return key;
    });

    const first = await ledger.begin('iterate:run-1', {userRequest: 'first'});
    expect((await ledger.begin('iterate:run-1', {userRequest: 'changed'})).key).toBe('key-2');
    ledger.complete('iterate:run-1', 'key-2');
    expect((await ledger.begin('iterate:run-1', {userRequest: 'changed'})).key).toBe('key-3');
    // Completing an obsolete attempt cannot clear the current action.
    ledger.complete('iterate:run-1', first.key);
    expect((await ledger.begin('iterate:run-1', {userRequest: 'changed'})).key).toBe('key-3');
  });

  it('keeps independent browser-tab actions independent', async () => {
    const left = new RunMutationAttemptLedger(new MemoryStorage(), () => 'left-key');
    const right = new RunMutationAttemptLedger(new MemoryStorage(), () => 'right-key');
    expect((await left.begin('create:standalone', {spec: 'same'})).key).toBe('left-key');
    expect((await right.begin('create:standalone', {spec: 'same'})).key).toBe('right-key');
  });

  it('stores only a stable SHA-256 digest and never the canonical spec body', async () => {
    const storage = new MemoryStorage();
    const ledger = new RunMutationAttemptLedger(
      storage,
      () => 'key-1',
      () => 1234,
    );
    const secret = 'sk-live-super-secret-spec-text';

    const first = await ledger.begin('create:secret', {
      spec: `Build with ${secret}`,
      nested: {provider: 'OPENAI', optional: undefined},
    });
    const equivalent = await ledger.begin('create:secret', {
      nested: {optional: undefined, provider: 'OPENAI'},
      spec: `Build with ${secret}`,
    });
    const changed = await ledger.begin('create:secret', {
      nested: {provider: 'OPENAI'},
      spec: `Build something else with ${secret}`,
    });

    expect(first.digest).toMatch(/^[a-f0-9]{64}$/);
    expect(equivalent.digest).toBe(first.digest);
    expect(equivalent.key).toBe(first.key);
    expect(changed.digest).not.toBe(first.digest);
    expect(storage.serializedValues()).not.toContain(secret);
    expect(storage.serializedValues()).not.toContain('Build something else');
  });
});

describe('shouldRetainRunMutationAttempt', () => {
  it('retains unknown/transient outcomes but clears definitive conflicts', () => {
    expect(shouldRetainRunMutationAttempt(new ApiError(0, 'timeout'))).toBe(true);
    expect(shouldRetainRunMutationAttempt(new ApiError(503, 'unavailable'))).toBe(true);
    expect(
      shouldRetainRunMutationAttempt(
        new ApiError(409, 'Submission is still in flight. Retry shortly.'),
      ),
    ).toBe(true);
    expect(shouldRetainRunMutationAttempt(new ApiError(409, 'Key permanently failed'))).toBe(false);
    expect(shouldRetainRunMutationAttempt(new ApiError(400, 'invalid'))).toBe(false);
  });
});

describe('executeRunMutation', () => {
  it('reuses the global pending key after an ambiguous failure', async () => {
    const seen: string[] = [];
    await expect(
      executeRunMutation('test:ambiguous', {spec: 'same'}, async (key) => {
        seen.push(key);
        throw new ApiError(0, 'timeout');
      }),
    ).rejects.toThrow('timeout');
    await executeRunMutation('test:ambiguous', {spec: 'same'}, async (key) => {
      seen.push(key);
      return 'ok';
    });
    expect(seen[1]).toBe(seen[0]);
  });
});
