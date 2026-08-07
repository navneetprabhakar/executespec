import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeProjectRepos} from './IdeProjectTabs';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getProjectDashboard: vi.fn(),
  getProjectRepos: vi.fn(),
  getProjectMembers: vi.fn(),
  getTechDebt: vi.fn(),
  getProject: vi.fn(),
  getWorkspaceHealth: vi.fn(),
  getProjectCodeIndex: vi.fn(),
  regenerateRepoArchitecture: vi.fn(),
  listScmRepos: vi.fn(),
  listRepos: vi.fn(),
  createRepo: vi.fn(),
  linkProjectRepo: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: {user: null}) => unknown) => selector({user: null}),
  selectIsSuperAdmin: () => false,
}));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getProjectDashboard.mockResolvedValue({activeRuns: 0, completedRuns: 0, failedRuns: 0});
  api.getProjectRepos.mockResolvedValue([]);
  api.getProjectMembers.mockResolvedValue([]);
  api.getTechDebt.mockResolvedValue({issuesBySeverity: {}});
  api.getProject.mockResolvedValue({name: 'Project one'});
  api.getWorkspaceHealth.mockResolvedValue({repos: []});
  api.getProjectCodeIndex.mockRejectedValue(new Error('index readiness unavailable'));
  api.regenerateRepoArchitecture.mockResolvedValue({
    id: 'job-12345678',
    projectId: 'project-1',
    status: 'QUEUED',
  });
  api.listScmRepos.mockResolvedValue([]);
  api.listRepos.mockResolvedValue([]);
  api.linkProjectRepo.mockResolvedValue({});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('IdeProjectRepos semantic-index honesty', () => {
  it('keeps the link panel open and presents the repository-access remediation', async () => {
    api.getProjectCodeIndex.mockResolvedValue([]);
    api.createRepo.mockRejectedValue(
      new Error(
        "ExecuteSpec's GitHub connection cannot access this repository. Grant the stored GitHub credential access to it, or reconnect GitHub.",
      ),
    );

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/projects/project-1/repos']}>
          <Routes>
            <Route path='/projects/:projectId/repos' element={<IdeProjectRepos />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    // The leading "+" is now a lucide <Plus/>, so the button's text is the label alone.
    const open = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent?.trim() === 'Link repo',
    );
    await act(async () => {
      open?.click();
      await Promise.resolve();
      await Promise.resolve();
    });
    const search = host.querySelector<HTMLInputElement>(
      'input[placeholder^="Search repos or paste"]',
    );
    expect(search).not.toBeNull();
    if (!search) throw new Error('repository search input was not rendered');
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(search, 'https://github.com/acme/private-repo');
      search.dispatchEvent(new Event('input', {bubbles: true}));
      await Promise.resolve();
    });
    const attach = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Attach by URL',
    );
    await act(async () => {
      attach?.click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(api.createRepo).toHaveBeenCalledWith(
      expect.objectContaining({repoFullName: 'acme/private-repo', scmProvider: 'GITHUB'}),
    );
    expect(api.linkProjectRepo).not.toHaveBeenCalled();
    expect(host.textContent).toContain('reconnect GitHub');
    expect(host.textContent).toContain('Available repos');
  });

  it('renders a retryable unknown state when readiness cannot be loaded', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/projects/project-1/repos']}>
          <Routes>
            <Route path='/projects/:projectId/repos' element={<IdeProjectRepos />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(host.textContent).toContain('index readiness unavailable');
    expect(host.textContent).toContain(
      'This is not a claim that you have no semantic index status',
    );
    expect(host.textContent).toContain('Retry');
  });

  it('reports per-repo regeneration as a durable project map job, not completed work', async () => {
    api.getProjectCodeIndex.mockResolvedValue([]);
    api.getProjectRepos.mockResolvedValue([
      {
        id: 'repo-1',
        githubRepoFullName: 'forge/service-one',
        scmProvider: 'GITHUB',
        sourceCapabilities: ['ARCHITECTURE'],
      },
    ]);

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/projects/project-1/repos']}>
          <Routes>
            <Route path='/projects/:projectId/repos' element={<IdeProjectRepos />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    const regenerate = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Regenerate',
    );
    expect(regenerate).toBeDefined();
    await act(async () => {
      regenerate?.click();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(api.regenerateRepoArchitecture).toHaveBeenCalledWith('project-1', 'repo-1');
    expect(host.textContent).toContain(
      'System map refresh was queued · job job-1234. Track progress in System map.',
    );
    expect(host.textContent).not.toContain('Architecture regenerated.');
  });
});
