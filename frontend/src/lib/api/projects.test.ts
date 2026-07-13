import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {updateProjectSettings} from './projects';

describe('project API', () => {
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

  it('uses the shared transport for the full project-settings patch contract', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({id: 'project-1'}), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await updateProjectSettings('project-1', {
      name: 'Updated name',
      description: 'Updated description',
      targetBranch: 'develop',
      branchNamingPattern: 'work/{runId}',
      aiProvider: 'DEEPSEEK',
      githubOwner: 'forge',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/projects/project-1/settings',
      expect.objectContaining({
        method: 'PATCH',
        credentials: 'include',
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
        body: JSON.stringify({
          name: 'Updated name',
          description: 'Updated description',
          targetBranch: 'develop',
          branchNamingPattern: 'work/{runId}',
          aiProvider: 'DEEPSEEK',
          githubOwner: 'forge',
        }),
      }),
    );
  });
});
