import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeProjectSettings} from './IdeProjectSettings';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const api = vi.hoisted(() => ({
  getProject: vi.fn(),
  getProjectDashboard: vi.fn(),
  getProjectRepos: vi.fn(),
  getProjectMembers: vi.fn(),
  getTechDebt: vi.fn(),
  listProjectDocuments: vi.fn(),
  getProjectMemory: vi.fn(),
  deleteProjectDocument: vi.fn(),
  compactProjectMemory: vi.fn(),
  archiveProject: vi.fn(),
  deleteProject: vi.fn(),
  uploadProjectDocument: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: {user: {globalRole: string; orgRole: string}}) => unknown) =>
    selector({user: {globalRole: 'USER', orgRole: 'ORG_ADMIN'}}),
}));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getProject.mockResolvedValue({
    id: 'p1',
    orgId: 'o1',
    name: 'Core API',
    description: 'Backend service',
    targetBranch: 'main',
    branchNamingPattern: 'execspec/{slug}-{date}',
    githubOwner: 'executespec',
    aiProvider: 'deepseek',
    status: 'ACTIVE',
    createdAt: '2026-06-20T00:00:00Z',
  });
  api.getProjectDashboard.mockResolvedValue({activeRuns: 0, completedRuns: 2, failedRuns: 0});
  api.getProjectRepos.mockResolvedValue([{id: 'r1'}]);
  api.getProjectMembers.mockResolvedValue([{id: 'm1'}]);
  api.getTechDebt.mockResolvedValue({issuesBySeverity: {CRITICAL: 1}});
  api.listProjectDocuments.mockResolvedValue([
    {
      id: 'd1',
      filename: 'Architecture.md',
      pathRelative: 'docs/Architecture.md',
      status: 'READY',
      sizeBytes: 1024,
      mimeType: 'text/markdown',
    },
  ]);
  api.getProjectMemory.mockResolvedValue({
    stats: {
      totalRuns: 2,
      snapshotVersion: 1,
      recentEntryCount: 1,
      currentTokens: 120,
      tokenBudget: 2000,
    },
    fullContext: 'Recent project context',
  });
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

async function renderSettings() {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={['/projects/p1/settings']}>
        <Routes>
          <Route path='/projects/:projectId/settings' element={<IdeProjectSettings />} />
        </Routes>
      </MemoryRouter>,
    );
  });
  await flush();
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('IdeProjectSettings', () => {
  it('renders project settings from the moved route module', async () => {
    await renderSettings();

    expect(host.textContent).toContain('Settings');
    expect((host.querySelector('input.ide-input') as HTMLInputElement).value).toBe('Core API');
    expect(host.textContent).toContain('Project ID');
    expect(host.textContent).toContain('p1');
  });

  it('renders reference documents through the settings sub-tab', async () => {
    await renderSettings();

    const docsTab = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('Reference docs'),
    ) as HTMLButtonElement;
    act(() => docsTab.click());

    expect(host.textContent).toContain('Architecture.md');
    expect(host.textContent).toContain('1.0 KB');
    expect(host.textContent).toContain('READY');
  });
});
