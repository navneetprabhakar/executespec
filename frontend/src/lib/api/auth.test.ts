import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {revokeAiKey, revokeOrgAiKey, updateAiKeySettings} from './auth';

describe('provider-key API contracts', () => {
  beforeEach(() => {
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    } satisfies Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('keeps patch fields explicit and uses a separate personal revoke route', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify({id: 'user-1'}), {status: 200}))
      .mockResolvedValueOnce(new Response(null, {status: 204}));
    vi.stubGlobal('fetch', fetchMock);

    await updateAiKeySettings('', 'sk-ant', '', '', '');
    await revokeAiKey('ANTHROPIC');

    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toMatchObject({
      openAiApiKey: '',
      anthropicApiKey: 'sk-ant',
      geminiApiKey: '',
    });
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/auth/settings/ai-keys/anthropic');
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({method: 'DELETE'});
  });

  it('uses the authenticated org-scoped revoke route', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({id: 'org-1'}), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await revokeOrgAiKey('openrouter');

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/org/me/settings/ai-keys/openrouter');
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({method: 'DELETE'});
  });
});
