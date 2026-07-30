import {act, StrictMode} from 'react';
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
  getLatestArchitectureRefreshJob: vi.fn(),
  startArchitectureRefresh: vi.fn(),
  getArchitectureRefreshJob: vi.fn(),
  cancelArchitectureRefreshJob: vi.fn(),
  confirmArchitectureRefreshRetry: vi.fn(),
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
  api.getLatestArchitectureRefreshJob.mockResolvedValue(null);
  api.cancelArchitectureRefreshJob.mockResolvedValue(undefined);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
  vi.useRealTimers();
});

async function renderArchitecture(strict = false) {
  const page = (
    <MemoryRouter initialEntries={['/projects/project-204/architecture']}>
      <Routes>
        <Route path='/projects/:projectId/architecture' element={<IdeProjectArchitecture />} />
      </Routes>
    </MemoryRouter>
  );
  await act(async () => {
    root.render(strict ? <StrictMode>{page}</StrictMode> : page);
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
  it('treats the latest-job 204 response as no recorded refresh', async () => {
    api.getLatestArchitectureRefreshJob.mockResolvedValue(undefined);

    await renderArchitecture();

    expect(api.getLatestArchitectureRefreshJob).toHaveBeenCalledWith('project-204');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('Resumed status checks');
  });

  it('resumes an active job on mount without submitting a duplicate refresh', async () => {
    vi.useFakeTimers();
    const running = {
      id: 'job-resume',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      attemptCount: 1,
      deadlineAt: '2026-07-28T00:05:00Z',
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
    api.getArchitectureRefreshJob.mockResolvedValue({
      ...running,
      phase: 'PUBLISHING',
      status: 'SUCCEEDED',
      terminal: true,
      architectureVersion: 5,
      completedAt: '2026-07-28T00:00:03Z',
    });

    await renderArchitecture();

    expect(host.textContent).toContain('attempts started 1');
    expect(host.textContent).toContain('deadline');
    expect(host.textContent).toContain('Resumed status checks');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(host.textContent).toContain('System map refreshed to v5.');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
  });

  it('submits one durable refresh then polls queued, running, and succeeded states', async () => {
    vi.useFakeTimers();
    const base = {
      id: 'job-12345678',
      projectId: 'project-204',
      phase: 'PREPARING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: null,
      completedAt: null,
    };
    api.startArchitectureRefresh.mockResolvedValue({...base, status: 'QUEUED'});
    api.getArchitectureRefreshJob
      .mockResolvedValueOnce({
        ...base,
        phase: 'EXECUTING',
        status: 'RUNNING',
        startedAt: '2026-07-28T00:00:01Z',
      })
      .mockResolvedValueOnce({
        ...base,
        phase: 'PUBLISHING',
        status: 'SUCCEEDED',
        terminal: true,
        architectureVersion: 4,
        completedAt: '2026-07-28T00:00:03Z',
      });
    await renderArchitecture();

    const refresh = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Refresh map',
    );
    await act(async () => refresh?.click());
    expect(api.startArchitectureRefresh).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('Map refresh is queued');

    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(host.textContent).toContain('Map refresh is running');
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
    expect(host.textContent).toContain('System map refreshed to v4.');
    expect(api.startArchitectureRefresh).toHaveBeenCalledTimes(1);
  });

  it('deduplicates a double click and a StrictMode mount into one refresh submission', async () => {
    vi.useFakeTimers();
    const queued = {
      id: 'job-strict',
      projectId: 'project-204',
      phase: 'PREPARING',
      status: 'QUEUED',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: null,
      completedAt: null,
    };
    api.startArchitectureRefresh.mockResolvedValue(queued);
    api.getArchitectureRefreshJob.mockReturnValue(pending);

    await renderArchitecture(true);
    const refresh = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Refresh map',
    );
    await act(async () => {
      refresh?.click();
      refresh?.click();
      await Promise.resolve();
    });

    expect(api.startArchitectureRefresh).toHaveBeenCalledTimes(1);
  });

  it('retains the last running truth across transient network, 429, and 5xx failures', async () => {
    vi.useFakeTimers();
    const running = {
      id: 'job-transient',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    const transient = (status: number) => Object.assign(new Error(`transient ${status}`), {status});
    api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
    api.getArchitectureRefreshJob
      .mockRejectedValueOnce(transient(0))
      .mockRejectedValueOnce(transient(429))
      .mockRejectedValueOnce(transient(503))
      .mockResolvedValue({
        ...running,
        status: 'SUCCEEDED',
        phase: 'PUBLISHING',
        terminal: true,
        architectureVersion: 8,
        completedAt: '2026-07-28T00:00:10Z',
      });

    await renderArchitecture();
    for (const delay of [500, 1000, 1500]) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(delay);
      });
      expect(host.textContent).toContain('Map refresh is running');
      expect(host.textContent).toContain('keeping the last known state');
    }
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(host.textContent).toContain('System map refreshed to v8.');
  });

  it.each([401, 403, 404])(
    'stops immediately on permanent status %s instead of retrying for five minutes',
    async (status) => {
      vi.useFakeTimers();
      const running = {
        id: `job-permanent-${status}`,
        projectId: 'project-204',
        phase: 'EXECUTING',
        status: 'RUNNING',
        terminal: false,
        architectureVersion: null,
        errorCode: null,
        errorMessage: null,
        createdAt: '2026-07-28T00:00:00Z',
        startedAt: '2026-07-28T00:00:01Z',
        completedAt: null,
      };
      api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
      api.getArchitectureRefreshJob.mockRejectedValue(
        Object.assign(new Error(`permanent ${status}`), {status}),
      );

      await renderArchitecture();
      await act(async () => {
        await vi.advanceTimersByTimeAsync(500);
        await vi.advanceTimersByTimeAsync(300_000);
      });

      expect(api.getArchitectureRefreshJob).toHaveBeenCalledTimes(1);
      expect(host.textContent).toContain(`permanent ${status}`);
      expect(
        Array.from(host.querySelectorAll('button')).find((button) =>
          button.textContent?.includes('refresh status'),
        )?.disabled,
      ).toBe(true);
    },
  );

  it('fails closed when active-job discovery is forbidden', async () => {
    vi.useFakeTimers();
    api.getLatestArchitectureRefreshJob.mockRejectedValue(
      Object.assign(new Error('project access denied'), {status: 403}),
    );

    await renderArchitecture();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300_000);
    });

    expect(api.getLatestArchitectureRefreshJob).toHaveBeenCalledTimes(1);
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
    expect(host.textContent).toContain('project access denied');
    expect(
      Array.from(host.querySelectorAll('button')).find(
        (button) => button.textContent === 'Refresh map',
      )?.disabled,
    ).toBe(true);
  });

  it('clears pending poll timers when the page unmounts', async () => {
    vi.useFakeTimers();
    api.getLatestArchitectureRefreshJob.mockResolvedValue({
      id: 'job-unmount',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    });

    await renderArchitecture();
    const timersBeforeUnmount = vi.getTimerCount();
    expect(timersBeforeUnmount).toBeGreaterThan(0);
    const pollsBeforeUnmount = api.getArchitectureRefreshJob.mock.calls.length;
    act(() => root.unmount());
    expect(vi.getTimerCount()).toBeLessThan(timersBeforeUnmount);
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getArchitectureRefreshJob).toHaveBeenCalledTimes(pollsBeforeUnmount);
  });

  it('drops a late old-project job result after project navigation', async () => {
    vi.useFakeTimers();
    let resolveOld!: (job: unknown) => void;
    const oldPoll = new Promise((resolve) => {
      resolveOld = resolve;
    });
    const oldRunning = {
      id: 'job-old-project',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockImplementation((projectId: string) =>
      Promise.resolve(projectId === 'project-204' ? oldRunning : null),
    );
    api.getArchitectureRefreshJob.mockReturnValue(oldPoll);

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
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    const navigate = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Open project 205',
    );
    await act(async () => {
      navigate?.click();
      await Promise.resolve();
      resolveOld({
        ...oldRunning,
        status: 'SUCCEEDED',
        phase: 'PUBLISHING',
        terminal: true,
        architectureVersion: 99,
        completedAt: '2026-07-28T00:00:10Z',
      });
      await Promise.resolve();
    });

    expect(api.getLatestArchitectureRefreshJob).toHaveBeenCalledWith('project-205');
    expect(host.textContent).not.toContain('v99');
    expect(host.textContent).not.toContain('job-old-');
  });

  it('pauses a long-running poll truthfully and resumes status checks without another POST', async () => {
    vi.useFakeTimers();
    const running = {
      id: 'job-long-running',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
    api.getArchitectureRefreshJob.mockResolvedValue(running);

    await renderArchitecture();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(300_000);
    });
    expect(host.textContent).toContain('Automatic checks paused');
    const resume = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Resume status checks',
    );
    expect(resume).toBeDefined();

    api.getArchitectureRefreshJob.mockResolvedValueOnce({
      ...running,
      status: 'SUCCEEDED',
      phase: 'PUBLISHING',
      terminal: true,
      architectureVersion: 12,
      completedAt: '2026-07-28T00:06:00Z',
    });
    await act(async () => resume?.click());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(host.textContent).toContain('System map refreshed to v12.');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
  });

  it('restores an uncertain outcome on reload and only retries through explicit confirmation', async () => {
    vi.useFakeTimers();
    const uncertain = {
      id: 'job-failed',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'NEEDS_CONFIRMATION',
      terminal: true,
      architectureVersion: null,
      errorCode: 'OUTCOME_UNCERTAIN',
      errorMessage: 'The worker stopped after paid execution began; no automatic retry was made.',
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: '2026-07-28T00:01:00Z',
    };
    const retried = {
      ...uncertain,
      status: 'QUEUED',
      phase: 'PREPARING',
      terminal: false,
      errorCode: null,
      errorMessage: null,
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockResolvedValue(uncertain);
    api.confirmArchitectureRefreshRetry.mockResolvedValue(retried);
    api.getArchitectureRefreshJob.mockResolvedValue(retried);

    await renderArchitecture();

    expect(host.textContent).toContain('no automatic retry was made');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
    expect(api.confirmArchitectureRefreshRetry).not.toHaveBeenCalled();

    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true);
    const retry = () =>
      Array.from(host.querySelectorAll('button')).find(
        (button) => button.textContent === 'Refresh map',
      );

    await act(async () => retry()?.click());
    expect(host.textContent).toContain('uncertain refresh was not retried');
    expect(api.confirmArchitectureRefreshRetry).not.toHaveBeenCalled();
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();

    await act(async () => retry()?.click());
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(api.confirmArchitectureRefreshRetry).toHaveBeenCalledTimes(1);
    expect(api.confirmArchitectureRefreshRetry).toHaveBeenCalledWith('project-204', 'job-failed');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('cancels the exact queued or running refresh without submitting another job', async () => {
    vi.useFakeTimers();
    const running = {
      id: 'job-cancel',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
    api.getArchitectureRefreshJob.mockResolvedValue({
      ...running,
      status: 'CANCELLED',
      terminal: true,
      errorCode: 'CANCELLED',
      errorMessage: 'Map refresh was cancelled.',
      completedAt: '2026-07-28T00:00:02Z',
    });

    await renderArchitecture();
    const cancel = Array.from(host.querySelectorAll('button')).find(
      (button) => button.textContent === 'Cancel refresh',
    );
    expect(cancel).toBeDefined();
    await act(async () => cancel?.click());

    expect(api.cancelArchitectureRefreshJob).toHaveBeenCalledWith('project-204', 'job-cancel');
    expect(api.startArchitectureRefresh).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Map refresh was cancelled.');
  });

  it('surfaces persisted map-quality warnings after successful publication', async () => {
    vi.useFakeTimers();
    const running = {
      id: 'job-warning',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      qualityStatus: null,
      qualityEvidence: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
    api.getArchitectureRefreshJob.mockResolvedValue({
      ...running,
      phase: 'PUBLISHING',
      status: 'SUCCEEDED',
      terminal: true,
      architectureVersion: 9,
      qualityStatus: 'WARNING',
      qualityEvidence: '{"warnings":["repo architecture used deterministic fallback"]}',
      completedAt: '2026-07-28T00:00:03Z',
    });

    await renderArchitecture();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });

    expect(host.textContent).toContain('The generated map has quality warnings.');
    expect(host.textContent).toContain('repo architecture used deterministic fallback');
  });

  it('renders an ordinary persisted failure without calling it an uncertain outcome', async () => {
    vi.useFakeTimers();
    const running = {
      id: 'job-ordinary-failure',
      projectId: 'project-204',
      phase: 'EXECUTING',
      status: 'RUNNING',
      terminal: false,
      architectureVersion: null,
      errorCode: null,
      errorMessage: null,
      createdAt: '2026-07-28T00:00:00Z',
      startedAt: '2026-07-28T00:00:01Z',
      completedAt: null,
    };
    api.getLatestArchitectureRefreshJob.mockResolvedValue(running);
    api.getArchitectureRefreshJob.mockResolvedValue({
      ...running,
      status: 'FAILED',
      terminal: true,
      errorCode: 'PROVIDER_REJECTED',
      errorMessage: 'The configured provider rejected architecture generation.',
      completedAt: '2026-07-28T00:00:03Z',
    });

    await renderArchitecture();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });

    expect(host.textContent).toContain('The configured provider rejected architecture generation.');
    expect(host.textContent).not.toContain('outcome is uncertain');
    expect(
      Array.from(host.querySelectorAll('button')).some(
        (button) => button.textContent === 'Refresh map',
      ),
    ).toBe(true);
  });

  it('surfaces that refreshing the stored map does not publish a codemap PR', async () => {
    await renderArchitecture();
    expect(host.textContent).toContain(
      "Refresh updates ExecuteSpec's stored system map. It does not publish codemap changes or open a pull request.",
    );
  });

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
