import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {downloadUserDataExport} from './misc';

describe('misc API', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    } satisfies Pick<Storage, 'clear' | 'getItem' | 'setItem' | 'removeItem'>);
    localStorage.setItem('access_token', 'test-token');
  });

  afterEach(() => vi.unstubAllGlobals());

  it('downloads the GDPR archive through the shared auth boundary', async () => {
    const archive = new Blob(['zip-bytes'], {type: 'application/zip'});
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(archive, {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await expect(downloadUserDataExport()).resolves.toBeInstanceOf(Blob);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/auth/me/data-export',
      expect.objectContaining({headers: {Authorization: 'Bearer test-token'}}),
    );
  });
});
