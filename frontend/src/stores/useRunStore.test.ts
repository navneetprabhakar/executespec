import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';

// Mock the API barrel the store imports. Only the calls exercised here are stubbed.
vi.mock('@/lib/api', () => ({
  getRun: vi.fn(),
  getRuns: vi.fn(),
  retryTask: vi.fn(),
}));

import * as api from '@/lib/api';
import {useRunStore} from './useRunStore';

const getRunMock = api.getRun as unknown as ReturnType<typeof vi.fn>;
const getRunsMock = api.getRuns as unknown as ReturnType<typeof vi.fn>;
const retryTaskMock = api.retryTask as unknown as ReturnType<typeof vi.fn>;

function resetStore() {
  useRunStore.setState({
    runs: [],
    activeRunId: null,
    activeRun: null,
    loading: false,
    error: null,
    retrySubmitting: null,
    page: 0,
  });
}

beforeEach(() => {
  resetStore();
  getRunMock.mockReset();
  getRunsMock.mockReset();
  retryTaskMock.mockReset();
});

afterEach(() => {
  vi.clearAllMocks();
});

describe('useRunStore — RR-200 stale request guard', () => {
  it('drops a slow fetchRun result superseded by a newer active run', async () => {
    // Active run is 'B'. A slow read for 'A' must NOT overwrite B's detail when
    // it eventually resolves (stale generation + identity mismatch).
    let resolveA!: () => void;
    getRunMock.mockImplementation((id: string) => {
      if (id === 'A') {
        return new Promise((res) => {
          resolveA = () => res({runId: 'A'});
        });
      }
      return Promise.resolve({runId: 'B'});
    });

    useRunStore.setState({activeRunId: 'A'});
    const pA = useRunStore.getState().fetchRun('A'); // generation N

    // User navigates to B — newer generation supersedes A.
    useRunStore.setState({activeRunId: 'B'});
    await useRunStore.getState().fetchRun('B'); // generation N+1 commits
    expect(useRunStore.getState().activeRun).toEqual({runId: 'B'});

    // A finally resolves — must be dropped.
    resolveA();
    await pA;
    expect(useRunStore.getState().activeRun).toEqual({runId: 'B'});
  });

  it('drops a slow fetchRuns list result superseded by a newer list request', async () => {
    let resolveOld!: () => void;
    const oldPage = {
      content: [{runId: 'old'}],
      page: 0,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    };
    const newPage = {
      content: [{runId: 'new'}],
      page: 1,
      totalElements: 1,
      totalPages: 1,
      hasNext: false,
      hasPrevious: false,
    };
    let call = 0;
    getRunsMock.mockImplementation(() => {
      call += 1;
      if (call === 1) {
        return new Promise((res) => {
          resolveOld = () => res(oldPage);
        });
      }
      return Promise.resolve(newPage);
    });

    const pOld = useRunStore.getState().fetchRuns(); // generation N
    await useRunStore.getState().fetchRuns(); // generation N+1 commits
    expect(useRunStore.getState().runs).toEqual([{runId: 'new'}]);

    resolveOld();
    await pOld;
    // Stale list must not clobber the newer one.
    expect(useRunStore.getState().runs).toEqual([{runId: 'new'}]);
  });
});

describe('useRunStore — RR-201 retry rollback', () => {
  it('does not leave a false PENDING when the retry is rejected', async () => {
    const serverTruth = {runId: 'r1', tasks: [{taskId: 't1', status: 'FAILED'}]};
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useRunStore.setState({activeRunId: 'r1', activeRun: serverTruth as any});
    retryTaskMock.mockRejectedValue(new Error('403 forbidden'));
    getRunMock.mockResolvedValue(serverTruth); // rollback refetch returns server truth

    await expect(useRunStore.getState().retryTask('r1', 't1')).rejects.toThrow();

    const state = useRunStore.getState();
    // Task must still read FAILED — never a fabricated PENDING.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((state.activeRun as any).tasks[0].status).toBe('FAILED');
    expect(state.error).toBeTruthy();
    expect(state.retrySubmitting).toBeNull();
  });

  it('commits server state and clears retrySubmitting on success', async () => {
    const before = {runId: 'r1', tasks: [{taskId: 't1', status: 'FAILED'}]};
    const after = {runId: 'r1', tasks: [{taskId: 't1', status: 'PENDING'}]};
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    useRunStore.setState({activeRunId: 'r1', activeRun: before as any});
    retryTaskMock.mockResolvedValue(undefined);
    getRunMock.mockResolvedValue(after);

    await useRunStore.getState().retryTask('r1', 't1');

    const state = useRunStore.getState();
    expect(state.activeRun).toEqual(after);
    expect(state.retrySubmitting).toBeNull();
  });
});
