import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

import {IdeProjectArchitecture} from './IdeProjectMore';

(
  globalThis as typeof globalThis & {
    IS_REACT_ACT_ENVIRONMENT?: boolean;
  }
).IS_REACT_ACT_ENVIRONMENT = true;

const pending = new Promise<never>(() => {});
const api = vi.hoisted(() => ({
  getArchitectureDoc: vi.fn(),
  getArchitectureVersions: vi.fn(),
  listProjectInvariants: vi.fn(),
  listProjectInvariantViolations: vi.fn(),
  listProjectAdrs: vi.fn(),
  getArchitectureStaleness: vi.fn(),
  getProjectRepos: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/ide/IdeProjectTabs', () => ({ProjectTabs: () => <div>Project navigation</div>}));
vi.mock('@/stores/useAuthStore', () => ({
  useAuthStore: (selector: (state: {user: null}) => unknown) => selector({user: null}),
}));

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.getArchitectureDoc.mockResolvedValue(null);
  api.getArchitectureVersions.mockReturnValue(pending);
  api.listProjectInvariants.mockReturnValue(pending);
  api.listProjectInvariantViolations.mockReturnValue(pending);
  api.listProjectAdrs.mockReturnValue(pending);
  api.getArchitectureStaleness.mockReturnValue(pending);
  api.getProjectRepos.mockReturnValue(pending);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
});

async function renderArchitecture() {
  await act(async () => {
    root.render(
      <MemoryRouter initialEntries={['/projects/project-204/architecture']}>
        <Routes>
          <Route path='/projects/:projectId/architecture' element={<IdeProjectArchitecture />} />
        </Routes>
      </MemoryRouter>,
    );
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('IdeProjectArchitecture empty-state contract', () => {
  it('renders the refresh CTA when the primary architecture request returns no content', async () => {
    await renderArchitecture();

    expect(api.getArchitectureDoc).toHaveBeenCalledWith('project-204');
    expect(host.textContent).not.toContain('Loading system map…');
    expect(host.textContent).toContain(
      'No system map yet — refresh it to build one from your repos.',
    );
    expect(host.textContent).toContain('Refresh system map');
  });
});
