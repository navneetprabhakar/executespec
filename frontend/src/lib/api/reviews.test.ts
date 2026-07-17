import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {listPrReviewsForPr, submitCodeReview, submitPrReview} from './reviews';

describe('review API repository admission contract', () => {
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

  it('submits PR reviews with the linked repository id', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({id: 'review-1'}), {status: 202}));
    vi.stubGlobal('fetch', fetchMock);

    await submitPrReview({
      projectId: 'project-1',
      repoId: 'repo-1',
      repoFullName: 'org/repo',
      prNumber: 42,
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/pr-reviews',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          projectId: 'project-1',
          repoId: 'repo-1',
          repoFullName: 'org/repo',
          prNumber: 42,
        }),
      }),
    );
  });

  it('scopes the by-PR history query to project and linked repository id', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify([]), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await listPrReviewsForPr('project/a', 'repo/1', 'org/repo name', 42);

    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/pr-reviews?projectId=project%2Fa&repoId=repo%2F1&repo=org%2Frepo%20name&number=42',
    );
  });

  it('submits code reviews with the linked repository id', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({id: 'scan-1'}), {status: 202}));
    vi.stubGlobal('fetch', fetchMock);

    await submitCodeReview({projectId: 'project-1', repoId: 'repo-1', repoFullName: 'org/repo'});

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/code-reviews',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          projectId: 'project-1',
          repoId: 'repo-1',
          repoFullName: 'org/repo',
        }),
      }),
    );
  });
});
