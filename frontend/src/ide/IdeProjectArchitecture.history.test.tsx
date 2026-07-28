import {act} from 'react';
import {createRoot, type Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeProjectArchitecture} from './IdeProjectMore';

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

const api = vi.hoisted(() => ({
  getArchitectureDoc: vi.fn(),
  getArchitectureVersions: vi.fn(),
  listProjectInvariants: vi.fn(),
  listProjectInvariantViolations: vi.fn(),
  listProjectAdrs: vi.fn(),
  getProjectAdrCapability: vi.fn(),
  getArchitectureStaleness: vi.fn(),
  getProjectRepos: vi.fn(),
  getProjectDashboard: vi.fn(),
  getProjectMembers: vi.fn(),
  getTechDebt: vi.fn(),
  getProject: vi.fn(),
  getArchitectureDiff: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: {user: {globalRole: string}}) => unknown) =>
    selector({user: {globalRole: 'USER'}}),
}));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getArchitectureDoc.mockResolvedValue({
    id: 'a2',
    version: 2,
    content: '# Map\n\nNew runtime',
    userAnnotations: null,
    generatedAt: '2026-07-28T04:00:00Z',
  });
  api.getArchitectureVersions.mockResolvedValue([
    {id: 'a2', version: 2, content: null, generatedAt: '2026-07-28T04:00:00Z'},
    {id: 'a1', version: 1, content: null, generatedAt: '2026-07-27T04:00:00Z'},
  ]);
  api.listProjectInvariants.mockResolvedValue([]);
  api.listProjectInvariantViolations.mockResolvedValue([]);
  api.listProjectAdrs.mockResolvedValue([]);
  api.getProjectAdrCapability.mockResolvedValue(null);
  api.getArchitectureStaleness.mockResolvedValue(null);
  api.getProjectRepos.mockResolvedValue([]);
  api.getProjectDashboard.mockResolvedValue({activeRuns: 0, completedRuns: 0, failedRuns: 0});
  api.getProjectMembers.mockResolvedValue([]);
  api.getTechDebt.mockResolvedValue({issuesBySeverity: {}});
  api.getProject.mockResolvedValue({id: 'p1', name: 'API'});
  api.getArchitectureDiff.mockResolvedValue({
    fromVersion: 1,
    toVersion: 2,
    fromContent: '# Map\n\nOld runtime',
    toContent: '# Map\n\nNew runtime',
    totalFiles: 1,
    perRepo: [
      {
        repoFullName: 'acme/api',
        files: [{filename: 'src/App.java', status: 'modified', additions: 1, deletions: 1}],
      },
    ],
  });
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('system map history comparison', () => {
  it('renders the architecture.md content diff before optional source-revision context', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/projects/p1/code']}>
          <Routes>
            <Route path='/projects/:projectId/code' element={<IdeProjectArchitecture />} />
          </Routes>
        </MemoryRouter>,
      );
    });
    await flush();

    const history = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.includes('History'),
    ) as HTMLButtonElement;
    act(() => history.click());

    const selects = host.querySelectorAll('select');
    await act(async () => {
      const from = selects[0] as HTMLSelectElement;
      from.value = '1';
      from.dispatchEvent(new Event('change', {bubbles: true}));
      const to = selects[1] as HTMLSelectElement;
      to.value = '2';
      to.dispatchEvent(new Event('change', {bubbles: true}));
    });
    const compare = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Compare',
    ) as HTMLButtonElement;
    await act(async () => compare.click());
    await flush();

    expect(api.getArchitectureDiff).toHaveBeenCalledWith('p1', 1, 2);
    expect(host.textContent).toContain('System map content · v1 → v2');
    expect(host.textContent).toContain('architecture.md');
    expect(host.textContent).toContain('Old runtime');
    expect(host.textContent).toContain('New runtime');
    expect(host.textContent).toContain('Source revisions · 1 file changed');
    expect(host.textContent?.indexOf('System map content')).toBeLessThan(
      host.textContent?.indexOf('Source revisions') ?? 0,
    );
  });
});
