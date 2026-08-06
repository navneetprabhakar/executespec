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
const readiness = vi.hoisted(() => ({
  loading: false,
  error: false,
  supported: true,
  rows: [] as Array<Record<string, unknown>>,
  byProvider: new Map<string, Record<string, unknown>>(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/signal/components/SpecChat', () => ({SpecChat: () => null}));
vi.mock('@/ide/useProviderReadiness', () => ({
  useProviderReadiness: () => readiness,
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
  readiness.loading = false;
  readiness.error = false;
  readiness.supported = true;
  readiness.rows = [];
  readiness.byProvider = new Map([
    ['ANTHROPIC', {provider: 'ANTHROPIC', state: 'AVAILABLE_MANAGED', dispatchable: true}],
  ]);
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
  it('restores project identity and repository scope from a Plan Review handoff', async () => {
    sessionStorage.setItem(
      'signal:pending-spec',
      JSON.stringify({
        schemaVersion: 1,
        handoffId: 'revision-1',
        spec: 'Preserve the exact /system/health endpoint.',
        projectId: 'project-1',
        repoId: null,
        scope: 'PROJECT',
        source: 'plan-revision',
        createdAt: '2026-07-30T00:00:00.000Z',
      }),
    );
    api.getProjectRepos.mockResolvedValue([
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

    await vi.waitFor(() => {
      expect(api.getProjectRepos).toHaveBeenCalledWith('project-1');
      expect(host.textContent).toContain('acme/orders');
    });
    expect(host.querySelector<HTMLTextAreaElement>('textarea')?.value).toBe(
      'Preserve the exact /system/health endpoint.',
    );
    expect(sessionStorage.getItem('signal:pending-spec')).toBeNull();
  });

  it('keeps the phone footer outside the scroll region and the commit action inside Review', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <IdeCompose />
        </MemoryRouter>,
      );
    });

    const scroll = host.querySelector('.rl-compose-scroll');
    const footer = host.querySelector('.rl-compose-actions');
    expect(scroll).toBeTruthy();
    expect(footer).toBeTruthy();
    // The overlap defect was a fixed footer sharing pixels with the scrolling
    // content. It is only impossible while the footer is a SIBLING of the scroll
    // region, so that is the thing worth pinning — not a CSS value.
    expect(scroll?.contains(footer as Node)).toBe(false);
    expect(footer?.parentElement?.classList.contains('rl-compose')).toBe(true);

    // The commit action must close the card it commits.
    const commit = host.querySelector('.rl-review-card .rl-commit .ide-btn');
    expect(commit?.textContent).toContain('Create plan');

    // Version B: exactly one accent-filled control on the surface. The phone
    // footer's copy is display:none on a desktop, so scope the count to the
    // layout the way the stylesheet does.
    const accent = Array.from(host.querySelectorAll('.ide-compose-layout .ide-btn')).filter(
      (b) => !b.classList.contains('ghost'),
    );
    expect(accent).toHaveLength(1);
  });

  it('offers no dead Back control on the first phone step', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter>
          <IdeCompose />
        </MemoryRouter>,
      );
    });

    const footer = host.querySelector('.rl-compose-actions');
    const labels = Array.from(footer?.querySelectorAll('button') ?? []).map((b) =>
      (b.textContent || '').trim(),
    );
    expect(labels).toEqual(['Next']);
    expect(footer?.classList.contains('is-single')).toBe(true);

    // Next is gated on a described request, so describe one before advancing.
    const textarea = host.querySelector('textarea') as HTMLTextAreaElement;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set;
      setter?.call(textarea, 'Add a health endpoint.');
      textarea.dispatchEvent(new Event('input', {bubbles: true}));
    });
    await act(async () => (footer?.querySelector('button') as HTMLButtonElement | null)?.click());
    const afterNext = Array.from(
      host.querySelector('.rl-compose-actions')?.querySelectorAll('button') ?? [],
    ).map((b) => (b.textContent || '').trim());
    expect(afterNext).toEqual(['Back', 'Next']);
  });

  it('disables desktop plan creation when the selected provider cannot dispatch', async () => {
    readiness.byProvider = new Map([
      ['ANTHROPIC', {provider: 'ANTHROPIC', state: 'UNAVAILABLE', dispatchable: false}],
    ]);

    await act(async () => {
      root.render(
        <MemoryRouter>
          <IdeCompose />
        </MemoryRouter>,
      );
    });

    await vi.waitFor(() => expect(button('Create plan')?.disabled).toBe(true));
    expect(host.textContent).toContain(
      'Unavailable because no permitted provider credential is configured.',
    );
    expect(api.createRun).not.toHaveBeenCalled();
  });

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
