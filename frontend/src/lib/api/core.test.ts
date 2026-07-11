import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {ApiError, isRetryableError, parseErrorMessage, req} from './core';

describe('API transport contract', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    const values = new Map<string, string>();
    vi.stubGlobal('localStorage', {
      clear: () => values.clear(),
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => values.set(key, value),
      removeItem: (key: string) => values.delete(key),
    } satisfies Pick<Storage, 'clear' | 'getItem' | 'setItem' | 'removeItem'>);
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('refreshes a 401 once and retries with the new access token', async () => {
    localStorage.setItem('access_token', 'expired-token');
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response('', {status: 401}))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({accessToken: 'fresh-token'}), {status: 200}),
      )
      .mockResolvedValueOnce(new Response(JSON.stringify({id: 'run-1'}), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await expect(req<{id: string}>('/runs/run-1')).resolves.toEqual({id: 'run-1'});

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/runs/run-1');
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      credentials: 'include',
      headers: expect.objectContaining({Authorization: 'Bearer expired-token'}),
    });
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/auth/refresh');
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({method: 'POST', credentials: 'include'});
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({
      headers: expect.objectContaining({Authorization: 'Bearer fresh-token'}),
    });
    expect(localStorage.getItem('access_token')).toBe('fresh-token');
  });

  it('surfaces network failures as retryable ApiErrors', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')));

    const failure = await req('/runs/run-1').catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(ApiError);
    expect(failure).toMatchObject({status: 0});
    expect((failure as ApiError).message).toContain("Can't reach the server (offline)");
    expect(isRetryableError(failure)).toBe(true);
  });

  it('uses structured backend messages while keeping safe status fallbacks', () => {
    expect(parseErrorMessage(422, '{"message":"spec is invalid"}')).toBe('spec is invalid');
    expect(parseErrorMessage(403, '{"error":"Forbidden"}')).toBe(
      "You don't have permission to perform this action.",
    );
    expect(parseErrorMessage(503, '<html>temporary outage</html>')).toBe(
      'The service is temporarily unavailable. Please try again shortly.',
    );
  });

  it('returns undefined for successful empty-body responses', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(new Response(null, {status: 204})),
    );

    await expect(req('/runs/run-1')).resolves.toBeUndefined();
  });
});
