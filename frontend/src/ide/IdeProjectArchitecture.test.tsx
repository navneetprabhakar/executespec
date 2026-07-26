import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes, useNavigate} from 'react-router-dom';
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
  getProjectAdrCapability: vi.fn(),
  getArchitectureStaleness: vi.fn(),
  getProjectRepos: vi.fn(),
  getContractCoverage: vi.fn(),
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
  api.getProjectAdrCapability.mockReturnValue(pending);
  api.getArchitectureStaleness.mockReturnValue(pending);
  api.getProjectRepos.mockReturnValue(pending);
  api.getContractCoverage.mockReturnValue(pending);
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

function NavigableArchitecture() {
  const navigate = useNavigate();
  return (
    <>
      <button type='button' onClick={() => navigate('/projects/project-205/architecture')}>
        Open project 205
      </button>
      <IdeProjectArchitecture />
    </>
  );
}

describe('IdeProjectArchitecture empty-state contract', () => {
  it('renders unknown and not-measurable source state as unverified, never up to date', async () => {
    api.getArchitectureDoc.mockResolvedValue({
      id: 'architecture-1',
      version: 1,
      content: '# System map',
      userAnnotations: null,
      generatedAt: '2026-07-26T00:00:00Z',
    });
    api.getArchitectureVersions.mockResolvedValue([]);
    api.listProjectInvariants.mockResolvedValue([]);
    api.listProjectInvariantViolations.mockResolvedValue([]);
    api.listProjectAdrs.mockResolvedValue([]);
    api.getProjectAdrCapability.mockResolvedValue(null);
    api.getArchitectureStaleness.mockResolvedValue({
      stale: false,
      staleCount: 0,
      totalCount: 2,
      unknownCount: 1,
      notMeasurableCount: 1,
      verified: false,
      repos: [],
    });
    api.getProjectRepos.mockResolvedValue([]);

    await renderArchitecture();

    expect(host.textContent).toContain('Unverified · 1 unknown, 1 not measurable');
    expect(host.textContent).not.toContain('Up to date');
  });

  it('renders the refresh CTA when the primary architecture request returns no content', async () => {
    await renderArchitecture();

    expect(api.getArchitectureDoc).toHaveBeenCalledWith('project-204');
    expect(host.textContent).not.toContain('Loading system map…');
    expect(host.textContent).toContain(
      'No system map yet — refresh it to build one from your repos.',
    );
    expect(host.textContent).toContain('Refresh system map');
  });

  it('does not present an empty ADR cache as proof that repositories contain no decisions', async () => {
    api.getArchitectureVersions.mockResolvedValue([]);
    api.listProjectInvariants.mockResolvedValue([]);
    api.listProjectInvariantViolations.mockResolvedValue([]);
    api.listProjectAdrs.mockResolvedValue([]);
    api.getProjectAdrCapability.mockResolvedValue({
      automaticIngestion: false,
      source: 'READ_ONLY_CACHE',
      indexedCount: 0,
      plannerInjectionEnabled: false,
      reason: 'Checked-in ADR discovery and synchronization are not implemented.',
    });
    api.getArchitectureStaleness.mockResolvedValue(null);
    api.getProjectRepos.mockResolvedValue([]);
    await renderArchitecture();

    const decisions = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.startsWith('Decisions'),
    );
    expect(decisions).toBeDefined();
    await act(async () => decisions?.click());

    expect(host.textContent).toContain('Checked-in ADR discovery is not available yet');
    expect(host.textContent).toContain('No cached architecture decisions.');
    expect(host.textContent).not.toContain('No architecture decisions yet.');
  });

  it('renders a retryable history failure instead of claiming there are no previous versions', async () => {
    api.getArchitectureVersions.mockRejectedValue(new Error('history unavailable'));
    api.listProjectInvariants.mockResolvedValue([]);
    api.listProjectInvariantViolations.mockResolvedValue([]);
    api.listProjectAdrs.mockResolvedValue([]);
    api.getProjectAdrCapability.mockResolvedValue(null);
    api.getArchitectureStaleness.mockResolvedValue(null);
    api.getProjectRepos.mockResolvedValue([]);

    await renderArchitecture();

    const history = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.startsWith('History'),
    );
    expect(history).toBeDefined();
    await act(async () => history?.click());

    expect(host.textContent).toContain('Could not load this.');
    expect(host.textContent).toContain('history unavailable');
    expect(host.textContent).toContain('This is not a claim that you have no system map history');
    expect(host.textContent).not.toContain('No previous map versions yet.');
  });

  it('does not render rules as passing when violation evidence failed to load', async () => {
    api.getArchitectureVersions.mockResolvedValue([]);
    api.listProjectInvariants.mockResolvedValue([
      {id: 'inv-1', invariantId: 'NO_CYCLES', description: 'No cycles'},
    ]);
    api.listProjectInvariantViolations.mockRejectedValue(new Error('violations unavailable'));
    api.listProjectAdrs.mockResolvedValue([]);
    api.getProjectAdrCapability.mockResolvedValue(null);
    api.getArchitectureStaleness.mockResolvedValue(null);
    api.getProjectRepos.mockResolvedValue([]);

    await renderArchitecture();

    const rules = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.startsWith('Rules'),
    );
    expect(rules).toBeDefined();
    await act(async () => rules?.click());

    expect(host.textContent).toContain('violations unavailable');
    expect(host.textContent).not.toContain('PASS');
  });

  it('clears the prior project history while the next project is still loading', async () => {
    api.getArchitectureVersions.mockImplementation((projectId: string) =>
      projectId === 'project-204'
        ? Promise.resolve([
            {
              id: 'architecture-99',
              version: 99,
              content: '# Old project',
              userAnnotations: null,
              generatedAt: '2026-07-25T00:00:00Z',
            },
          ])
        : pending,
    );
    api.listProjectInvariants.mockResolvedValue([]);
    api.listProjectInvariantViolations.mockResolvedValue([]);
    api.listProjectAdrs.mockResolvedValue([]);
    api.getProjectAdrCapability.mockResolvedValue(null);
    api.getArchitectureStaleness.mockResolvedValue(null);
    api.getProjectRepos.mockResolvedValue([]);

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/projects/project-204/architecture']}>
          <Routes>
            <Route path='/projects/:projectId/architecture' element={<NavigableArchitecture />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    const history = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.startsWith('History'),
    );
    await act(async () => history?.click());
    expect(host.textContent).toContain('v99');

    const navigate = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Open project 205',
    );
    await act(async () => {
      navigate?.click();
      await Promise.resolve();
    });

    expect(host.textContent).not.toContain('v99');
    expect(host.textContent).toContain('Loading history…');
  });

  it('does not call a failed contract-coverage read an empty scan result', async () => {
    api.getArchitectureVersions.mockResolvedValue([]);
    api.listProjectInvariants.mockResolvedValue([]);
    api.listProjectInvariantViolations.mockResolvedValue([]);
    api.listProjectAdrs.mockResolvedValue([]);
    api.getProjectAdrCapability.mockResolvedValue(null);
    api.getArchitectureStaleness.mockResolvedValue(null);
    api.getProjectRepos.mockResolvedValue([
      {repoId: 'repo-1', repo: {id: 'repo-1', sourceDisplayName: 'acme/service'}},
    ]);
    api.getContractCoverage.mockRejectedValue(new Error('coverage unavailable'));

    await renderArchitecture();

    const contracts = Array.from(host.querySelectorAll('button')).find((button) =>
      button.textContent?.startsWith('Contracts'),
    );
    await act(async () => contracts?.click());

    expect(host.textContent).toContain('coverage unavailable');
    expect(host.textContent).toContain(
      'This is not a claim that you have no contract coverage for acme/service',
    );
    expect(host.textContent).not.toContain('No coverage data — start a scan');
  });
});
