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
    vi.useRealTimers();
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

    await expect(
      req<{id: string}>('/runs', {
        method: 'POST',
        headers: {'Idempotency-Key': 'stable-attempt'},
        body: JSON.stringify({userRequest: 'build'}),
      }),
    ).resolves.toEqual({id: 'run-1'});

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/runs');
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({
      credentials: 'include',
      headers: expect.objectContaining({
        Authorization: 'Bearer expired-token',
        'Idempotency-Key': 'stable-attempt',
      }),
    });
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/auth/refresh');
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({method: 'POST', credentials: 'include'});
    expect(fetchMock.mock.calls[2]?.[1]).toMatchObject({
      headers: expect.objectContaining({
        Authorization: 'Bearer fresh-token',
        'Idempotency-Key': 'stable-attempt',
      }),
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

  it('bounds a stalled automatic token refresh and releases the shared refresh attempt', async () => {
    vi.useFakeTimers();
    localStorage.setItem('access_token', 'expired-token');
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValueOnce(new Response('', {status: 401}));
    fetchMock.mockImplementationOnce((_url, init) => {
      return new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted')));
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const request = req('/runs/run-1').catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    // The refresh-failure redirect is an existing browser responsibility and jsdom cannot perform
    // navigation. Removing the test token here keeps this assertion focused on deadline/lock release.
    localStorage.removeItem('access_token');
    await vi.advanceTimersByTimeAsync(30_000);

    await expect(request).resolves.toMatchObject({status: 401});
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/auth/refresh');
    expect(fetchMock.mock.calls[1]?.[1]?.signal?.aborted).toBe(true);

    localStorage.setItem('access_token', 'another-expired-token');
    fetchMock.mockResolvedValueOnce(new Response('', {status: 401}));
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({accessToken: 'fresh-token'}), {status: 200}),
    );
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({id: 'run-2'}), {status: 200}));
    await expect(req<{id: string}>('/runs/run-2')).resolves.toEqual({id: 'run-2'});
    expect(fetchMock).toHaveBeenCalledTimes(5);
  });

  it('uses structured backend messages while keeping safe status fallbacks', () => {
    expect(parseErrorMessage(422, '{"message":"spec is invalid"}')).toBe('spec is invalid');
    expect(
      parseErrorMessage(
        403,
        JSON.stringify({
          errorCode: 'GITHUB_REPOSITORY_ACCESS_DENIED',
          message:
            "ExecuteSpec's GitHub connection cannot access this repository. Grant the stored GitHub credential access to it, or reconnect GitHub.",
          action: 'UPDATE_GITHUB_ACCESS',
        }),
      ),
    ).toContain('reconnect GitHub');
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
