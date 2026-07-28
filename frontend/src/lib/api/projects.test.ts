import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {
  generateProjectDoc,
  createRepo,
  createProjectExport,
  confirmArchitectureRefreshRetry,
  getLatestArchitectureRefreshJob,
  getProjectCodeIndex,
  listScmRepos,
  regenerateRepoArchitecture,
  refreshProjectCodeIndex,
  issueProjectChatConfirmation,
  consumeProjectChatConfirmation,
  updateProjectSettings,
} from './projects';

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

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('keeps project document generation alive past the shared 30 second deadline', async () => {
    vi.useFakeTimers();
    const captured: {signal: AbortSignal | null} = {signal: null};
    const fetchMock = vi.fn<typeof fetch>().mockImplementation((_url, init) => {
      captured.signal = init?.signal ?? null;
      return new Promise<Response>((_resolve, reject) => {
        captured.signal?.addEventListener(
          'abort',
          () => reject(new DOMException('The operation was aborted.', 'AbortError')),
          {once: true},
        );
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const request = generateProjectDoc('project-1', 'EXPLANATION').catch((error: unknown) => error);
    await vi.advanceTimersByTimeAsync(30_000);
    expect(captured.signal?.aborted).toBe(false);

    await vi.advanceTimersByTimeAsync(150_000);
    await expect(request).resolves.toMatchObject({
      message: 'The request timed out. Please check your connection and try again.',
      status: 0,
    });
    expect(fetchMock.mock.calls[0]?.[1]).not.toHaveProperty('timeoutMs');
  });

  it('uses durable job endpoints for latest state, per-repo admission, and confirmed retry', async () => {
    const job = {
      id: 'job-1',
      projectId: 'project-1',
      status: 'QUEUED',
      phase: 'PREPARING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      qualityStatus: null,
      qualityEvidence: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: null,
      completedAt: null,
    };
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(JSON.stringify(job), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await expect(getLatestArchitectureRefreshJob('project-1')).resolves.toEqual(job);
    await expect(regenerateRepoArchitecture('project-1', 'repo-1')).resolves.toEqual(job);
    await expect(confirmArchitectureRefreshRetry('project-1', 'job-1')).resolves.toEqual(job);

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/projects/project-1/architecture/jobs/latest',
      expect.not.objectContaining({method: 'POST'}),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/projects/project-1/repos/repo-1/regenerate-architecture',
      expect.objectContaining({method: 'POST'}),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      '/api/projects/project-1/architecture/jobs/job-1/confirm-retry',
      expect.objectContaining({method: 'POST'}),
    );
  });

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

  it('uses provider-neutral catalogue and immutable attach identity without credentials', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(JSON.stringify([]), {status: 200}));
    vi.stubGlobal('fetch', fetchMock);

    await listScmRepos('BITBUCKET', 'acme');
    await createRepo({
      scope: 'USER',
      scmProvider: 'BITBUCKET',
      repoFullName: 'acme/platform',
      scmRepositoryId: '{immutable-id}',
      attachAnyway: false,
    });

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/repos/scm?provider=BITBUCKET&owner=acme',
      expect.objectContaining({
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/repos',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          scope: 'USER',
          scmProvider: 'BITBUCKET',
          repoFullName: 'acme/platform',
          scmRepositoryId: '{immutable-id}',
          attachAnyway: false,
        }),
      }),
    );
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain('appPassword');
  });

  it('preserves an unavailable semantic-refresh result through project-scoped paths', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(new Response(JSON.stringify([]), {status: 200}))
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            repoId: 'repo-1',
            status: 'UNAVAILABLE',
            fallback: 'RIPGREP',
            relationshipGraphAvailable: false,
            reason: 'EMBEDDING_PROVIDER_NOT_AVAILABLE_FOR_PROJECT_ROUTE',
          }),
          {status: 202},
        ),
      );
    vi.stubGlobal('fetch', fetchMock);

    await getProjectCodeIndex('project-1');
    const refresh = await refreshProjectCodeIndex('project-1', 'repo-1');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/projects/project-1/code-index',
      expect.objectContaining({
        headers: expect.objectContaining({Authorization: 'Bearer test-token'}),
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/projects/project-1/code-index/repo-1/refresh',
      expect.objectContaining({method: 'POST'}),
    );
    expect(refresh).toEqual(
      expect.objectContaining({
        status: 'UNAVAILABLE',
        reason: 'EMBEDDING_PROVIDER_NOT_AVAILABLE_FOR_PROJECT_ROUTE',
      }),
    );
  });

  it('preserves the exact project-chat confirmation scope through issue and one-shot consume', async () => {
    const scope = {
      action: 'RUN_CODE_REVIEW' as const,
      provider: 'deepseek',
      model: 'deepseek-v4-pro-native',
      target: {repoId: '11111111-1111-1111-1111-111111111111'},
    };
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            confirmation: {
              confirmationId: 'confirmation-1',
              projectId: 'project-1',
              ...scope,
              capabilities: ['PROJECT_ANALYSE', 'SPEND_AI'],
              targetHash: 'hash',
              expiresAt: '2030-01-01T00:05:00Z',
              consumed: false,
            },
            confirmationToken: 'es_chat_confirm_secret',
          }),
          {status: 201},
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({confirmation: {consumed: true}, result: {status: 'QUEUED'}}), {
          status: 200,
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const issued = await issueProjectChatConfirmation('project-1', scope);
    await consumeProjectChatConfirmation(
      'project-1',
      issued.confirmation.confirmationId,
      issued.confirmationToken,
      scope,
    );

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/projects/project-1/chat/confirmations');
    expect(JSON.parse(String((fetchMock.mock.calls[0]?.[1] as RequestInit).body))).toEqual(scope);
    expect(fetchMock.mock.calls[1]?.[0]).toBe(
      '/api/projects/project-1/chat/confirmations/confirmation-1/consume',
    );
    expect(JSON.parse(String((fetchMock.mock.calls[1]?.[1] as RequestInit).body))).toEqual({
      confirmationToken: 'es_chat_confirm_secret',
      ...scope,
    });
    expect(JSON.stringify(localStorage)).not.toContain('es_chat_confirm_secret');
  });

  it('surfaces viewer denial without retaining a confirmation token', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(JSON.stringify({message: 'PROJECT_VIEWER cannot write project'}), {
          status: 403,
        }),
      ),
    );
    await expect(
      issueProjectChatConfirmation('project-1', {
        action: 'REGENERATE_ARCHITECTURE',
        provider: 'deepseek',
        model: 'deepseek-v4-pro-native',
        target: {branch: ''},
      }),
    ).rejects.toThrow(/viewer|write project/i);
    expect(JSON.stringify(localStorage)).not.toContain('es_chat_confirm_');
  });

  it('sends project-export idempotency without placing it in the URL or body', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({id: 'job-1'}), {status: 202}));
    vi.stubGlobal('fetch', fetchMock);

    await createProjectExport('project-1', 'request-key-123');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/projects/project-1/export',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          Authorization: 'Bearer test-token',
          'Idempotency-Key': 'request-key-123',
        }),
      }),
    );
  });
});
