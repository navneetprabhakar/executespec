import {beforeEach, describe, expect, it, vi} from 'vitest';

import {
  consumeAgentProjectHandoff,
  createAgentProjectHandoff,
  readAgentProjectHandoff,
} from './agentProjectHandoff';

describe('agent project handoff', () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal('sessionStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
      clear: () => values.clear(),
    });
  });

  it('preserves the source session, draft, and return route until consumed once', () => {
    const handoff = createAgentProjectHandoff({
      sourceSessionId: 'session-1',
      draft: 'Finish the repository audit',
      provider: 'OPENAI',
      model: 'gpt-5.4',
      returnPath: '/agent',
    });

    expect(readAgentProjectHandoff(handoff.id)).toMatchObject({
      sourceSessionId: 'session-1',
      draft: 'Finish the repository audit',
      provider: 'OPENAI',
      model: 'gpt-5.4',
      returnPath: '/agent',
    });
    expect(consumeAgentProjectHandoff(handoff.id)?.id).toBe(handoff.id);
    expect(readAgentProjectHandoff(handoff.id)).toBeNull();
  });
});
