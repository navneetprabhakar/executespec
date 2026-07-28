import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeCompose} from './IdeSurfaces';

const api = vi.hoisted(() => ({
  createRun: vi.fn(),
  getAvailableModels: vi.fn(),
  getManagedAiBalance: vi.fn(),
  getProjects: vi.fn(),
  getProjectRepos: vi.fn(),
  listOnboardingSamples: vi.fn(),
  updateProviderPreference: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/signal/components/SpecChat', () => ({SpecChat: () => null}));
vi.mock('@/ide/useProviderReadiness', () => ({
  useProviderReadiness: () => ({
    loading: false,
    error: false,
    supported: true,
    rows: [],
    byProvider: new Map([
      ['ANTHROPIC', {provider: 'ANTHROPIC', state: 'AVAILABLE_MANAGED', dispatchable: true}],
    ]),
  }),
}));
vi.mock('@/stores/useAuthStore', () => {
  const state = {
    permissions: {canCreateRun: true},
    tier: 'PRO',
    user: {preferredAiProvider: null, hasGithubPat: true},
  };
  const useAuthStore = Object.assign(
    (selector: (value: typeof state) => unknown) => selector(state),
    {setState: vi.fn()},
  );
  return {
    useAuthStore,
    selectNormalizedTier: (value: typeof state) => value.tier,
    selectIsSuperAdmin: () => false,
  };
});

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

let host: HTMLDivElement;
let root: Root;

function button(label: string): HTMLButtonElement | undefined {
  return Array.from(host.querySelectorAll('button')).find((candidate) =>
    (candidate.textContent || '').includes(label),
  );
}

beforeEach(() => {
  sessionStorage.clear();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getProjects.mockResolvedValue([
    {id: 'project-1', name: 'Project Alpha', createdAt: '2026-07-28T00:00:00Z', archivedAt: null},
  ]);
  api.getAvailableModels.mockResolvedValue({providers: []});
  api.getManagedAiBalance.mockResolvedValue(null);
  api.listOnboardingSamples.mockResolvedValue([]);
  api.createRun.mockResolvedValue({runId: 'run-1'});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('IdeCompose repository discovery', () => {
  it('fails closed on discovery error and recovers only after an explicit retry succeeds', async () => {
    api.getProjectRepos
      .mockRejectedValueOnce(new Error('repository service unavailable'))
      .mockResolvedValue([
        {
          repoId: 'repo-1',
          repo: {
            id: 'repo-1',
            githubRepoFullName: 'acme/orders',
            scmProvider: 'GITHUB',
            sourceCapabilities: ['CLOUD_RUN'],
          },
        },
      ]);

    await act(async () => {
      root.render(
        <MemoryRouter>
          <IdeCompose />
        </MemoryRouter>,
      );
    });

    await vi.waitFor(() => expect(button('Project Alpha')).toBeTruthy());
    await act(async () => button('Project Alpha')?.click());

    await vi.waitFor(() => {
      expect(host.textContent).toContain('repository service unavailable');
    });
    expect(button('Create plan')?.disabled).toBe(true);
    expect(api.createRun).not.toHaveBeenCalled();

    await act(async () => button('Retry repository discovery')?.click());

    await vi.waitFor(() => {
      expect(host.textContent).toContain('acme/orders');
      expect(button('Create plan')?.disabled).toBe(false);
    });
    expect(host.textContent).not.toContain('repository service unavailable');
    expect(api.getProjectRepos).toHaveBeenCalledTimes(2);
  });
});
