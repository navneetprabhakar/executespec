import {act} from 'react';
import {createRoot, Root} from 'react-dom/client';
import {MemoryRouter, Route, Routes} from 'react-router-dom';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

const api = vi.hoisted(() => ({
  listBenchmarks: vi.fn(),
  getBenchmark: vi.fn(),
}));

vi.mock('@/lib/api', () => api);
vi.mock('@/stores/useAuthStore', () => ({
  selectIsSuperAdmin: (state: {user?: {globalRole?: string} | null}) =>
    state.user?.globalRole === 'SUPER_ADMIN',
  useAuthStore: (selector: (state: {user: {globalRole: string}}) => unknown) =>
    selector({user: {globalRole: 'SUPER_ADMIN'}}),
}));

import IdeBenchmark from './IdeBenchmark';

let host: HTMLDivElement;
let root: Root;

function setDocumentHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', {configurable: true, value: hidden});
}

(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT =
  true;

beforeEach(() => {
  setDocumentHidden(false);
  host = document.createElement('div');
  document.body.appendChild(host);
  root = createRoot(host);
  api.listBenchmarks.mockResolvedValue([
    {
      id: 'run-1',
      suite: 'SWE_BENCH',
      dataset: 'swebench_lite_test deepseek-v4-pro-native k-eval 2026-07-18 pass1',
      provider: 'deepseek-native-provider-with-a-long-identifier',
      model: 'deepseek-v4-pro-native',
      k: 1,
      totalTasks: 10,
      resolvedTasks: 7,
      status: 'DONE',
      costUsd: 1.25,
      inputTokens: 12345,
      outputTokens: 678,
      createdAt: '2026-07-18T08:00:00Z',
      finishedAt: '2026-07-18T08:05:00Z',
    },
  ]);
});

afterEach(() => {
  act(() => root.unmount());
  host.remove();
  vi.clearAllMocks();
  vi.useRealTimers();
  setDocumentHidden(false);
});

describe('IdeBenchmark mobile run cards', () => {
  it('renders a readable card with the run identity and metrics', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/benchmark']}>
          <Routes>
            <Route path='/benchmark' element={<IdeBenchmark />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    const card = host.querySelector('.ide-benchmark-mobile-card');
    expect(card).not.toBeNull();
    const detailButton = card?.querySelector('button');
    expect(detailButton?.getAttribute('aria-label')).toContain('deepseek-v4-pro-native');
    expect(detailButton?.getAttribute('type')).toBe('button');
    expect(card?.textContent).toContain('swebench_lite_test');
    expect(card?.textContent).toContain('7/10');
    expect(card?.textContent).toContain('$1.2500');
    // Was `12K↑ 678↓`. Arrows as unit suffixes were a guess in both directions —
    // nothing said which way meant input. Now labelled outright.
    expect(card?.textContent).toContain('12K in · 678 out');
    expect(host.querySelector('[data-testid="benchmark-sync-status"]')?.textContent).toContain(
      'Synced',
    );
  });

  it('refetches immediately when a hidden tab becomes visible', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/benchmark']}>
          <Routes>
            <Route path='/benchmark' element={<IdeBenchmark />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });
    expect(api.listBenchmarks).toHaveBeenCalledTimes(1);

    setDocumentHidden(true);
    document.dispatchEvent(new Event('visibilitychange'));
    expect(api.listBenchmarks).toHaveBeenCalledTimes(1);

    setDocumentHidden(false);
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
      await Promise.resolve();
    });
    expect(api.listBenchmarks).toHaveBeenCalledTimes(2);
  });

  it('keeps the last good rows and exposes a failed refresh', async () => {
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/benchmark']}>
          <Routes>
            <Route path='/benchmark' element={<IdeBenchmark />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    api.listBenchmarks.mockRejectedValueOnce(new Error('telemetry gateway unavailable'));
    await act(async () => {
      window.dispatchEvent(new Event('focus'));
      await Promise.resolve();
    });

    expect(host.textContent).toContain('deepseek-v4-pro-native');
    expect(host.textContent).toContain('telemetry gateway unavailable');
    expect(host.querySelector('[data-testid="benchmark-sync-status"]')?.textContent).toContain(
      'Sync error · showing data from',
    );
  });

  it('labels a last-known snapshot stale while background polling is suspended', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-19T08:00:00Z'));
    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/benchmark']}>
          <Routes>
            <Route path='/benchmark' element={<IdeBenchmark />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    setDocumentHidden(true);
    await act(async () => {
      vi.advanceTimersByTime(16_000);
    });
    expect(host.querySelector('[data-testid="benchmark-sync-status"]')?.textContent).toContain(
      'Stale · last synced',
    );
  });

  it('reloads durable attempt, ETA, trajectory and readiness evidence on detail', async () => {
    api.getBenchmark.mockResolvedValue({
      run: {
        id: 'run-1',
        suite: 'SWE_BENCH',
        dataset: 'lite-10',
        provider: 'openrouter',
        model: 'glm-5.2',
        k: 1,
        totalTasks: 10,
        resolvedTasks: 0,
        status: 'RUNNING',
        costUsd: 0.0001,
        createdAt: '2026-07-18T08:00:00Z',
        startedAt: '2026-07-18T08:00:00Z',
        activeAttemptCount: 1,
        completedAttempts: 3,
        elapsedSeconds: 120,
        etaSeconds: 280,
        submissionReadiness: 'IN_PROGRESS',
        submissionReadinessReason: 'benchmark has not reached DONE',
      },
      activeAttempts: [
        {
          id: 'active-1',
          benchmarkRunId: 'run-1',
          taskName: 'django__django-123',
          attempt: 1,
          startedAt: new Date().toISOString(),
          lastProgressAt: new Date().toISOString(),
          staleAfter: new Date(Date.now() - 60_000).toISOString(),
          stale: true,
          etaSeconds: 45,
        },
      ],
      tasks: [
        {
          id: 'task-1',
          benchmarkRunId: 'run-1',
          taskName: 'django__django-100',
          attempt: 1,
          reward: 0,
          outcome: 'TIMEOUT',
          failureClassification: 'TIMEOUT',
          trajectoryLocation: 'gs://evidence/run-1/task-1.json',
          trajectorySha256: 'a'.repeat(64),
          costUsd: 0.0001,
          createdAt: '2026-07-18T08:01:00Z',
        },
      ],
      events: [],
    });

    await act(async () => {
      root.render(
        <MemoryRouter initialEntries={['/benchmark/run-1']}>
          <Routes>
            <Route path='/benchmark/:benchmarkId' element={<IdeBenchmark />} />
          </Routes>
        </MemoryRouter>,
      );
      await Promise.resolve();
    });

    expect(host.textContent).toContain('1 active · 3 scored');
    expect(host.textContent).toContain('4m 40s');
    expect(host.textContent).toContain('django__django-123');
    expect(host.textContent).toContain('stale heartbeat');
    expect(host.textContent).toContain('TIMEOUT');
    expect(host.textContent).toContain('$0.0001');
    expect(host.textContent).toContain('a1 trajectory · aaaaaaaaaa…');
    expect(host.textContent).toContain('IN_PROGRESS');
  });
});
