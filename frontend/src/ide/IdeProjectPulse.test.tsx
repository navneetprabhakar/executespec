import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeProjectPulse} from './IdeProjectTabs';

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
  getRuns: vi.fn(),
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
  api.getProjectRepos.mockResolvedValue([]);
  api.getProjectMembers.mockResolvedValue([]);
  api.getTechDebt.mockResolvedValue({issuesBySeverity: {}});
  api.getProject.mockResolvedValue({name: 'Project one'});
  api.getWorkspaceHealth.mockResolvedValue({repos: []});
  api.getRuns.mockResolvedValue({content: []});
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

async function renderPulse() {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={['/projects/project-1']}>
        <Routes>
          <Route path='/projects/:projectId' element={<IdeProjectPulse />} />
        </Routes>
      </MemoryRouter>,
    );
  });
}

function metricValue(label: string): string | undefined {
  const labels = Array.from(host.querySelectorAll('div')).filter(
    (el) => el.textContent === label && el.children.length === 0,
  );
  return labels[0]?.nextElementSibling?.textContent ?? undefined;
}

describe('IdeProjectPulse run-bucket counters', () => {
  it('renders completed, partial, and failed counters from the dashboard display buckets', async () => {
    // The observed dev defect: 2 PARTIAL runs were invisible while the counters read
    // "completed 0 failed 1". partialRuns now has its own visible bucket.
    api.getProjectDashboard.mockResolvedValue({
      activeRuns: 0,
      completedRuns: 4,
      partialRuns: 2,
      failedRuns: 1,
      avgScore: 0,
      recentTasks: [],
      recentPrs: [],
    });

    await renderPulse();

    expect(metricValue('Runs · completed')).toBe('4');
    expect(metricValue('Runs · partial')).toBe('2');
    expect(metricValue('Runs · failed')).toBe('1');
  });

  it('is null-safe when an older server omits partialRuns — unknown renders as "—", not 0', async () => {
    api.getProjectDashboard.mockResolvedValue({
      activeRuns: 0,
      completedRuns: 3,
      failedRuns: 1,
      avgScore: 0,
      recentTasks: [],
      recentPrs: [],
    });

    await renderPulse();

    expect(metricValue('Runs · completed')).toBe('3');
    expect(metricValue('Runs · partial')).toBe('—');
    expect(metricValue('Runs · failed')).toBe('1');
  });
});
