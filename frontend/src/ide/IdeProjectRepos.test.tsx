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
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

describe('IdeProjectRepos semantic-index honesty', () => {
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
});
