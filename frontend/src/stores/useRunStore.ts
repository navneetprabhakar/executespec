import {create} from 'zustand';
import {errMsg} from '@/lib/utils';
import * as api from '@/lib/api';

/**
 * RR-200 — request generation tokens. Each async read bumps its counter; a
 * completion only commits to the store when it is still the newest request for
 * that slot (and, for the single active-run slot, still the run the user is
 * looking at). Kept as module-level closure state rather than store state so a
 * bump never triggers a React re-render. A query cache keyed by run/filter is
 * the larger refactor noted as deferred — this generation guard is the bounded
 * correctness fix.
 */
let activeRunReqGen = 0;
let runsReqGen = 0;

interface RunStore {
  runs: api.RunSummary[];
  activeRunId: string | null;
  activeRun: api.RunDetail | null;
  loading: boolean;
  error: string | null;
  /** RR-201 — taskId currently being retried (null = none). Lets the UI show a
   * pending-submit spinner without faking a server-confirmed PENDING status. */
  retrySubmitting: string | null;
  statusFilter: string;
  projectFilter: string | null;
  scopeFilter: string | null; // 'mine' = my runs (default), null = all visible runs, 'org' = org runs (super admin)
  archivedFilter: boolean;

  // Pagination
  page: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
  hasPrevious: boolean;

  setStatusFilter: (s: string) => void;
  setProjectFilter: (projectId: string | null) => void;
  setScopeFilter: (scope: string | null) => void;
  setArchivedFilter: (archived: boolean) => void;
  setPage: (page: number) => void;
  setActiveRunId: (id: string | null) => void;
  fetchRuns: () => Promise<void>;
  fetchRun: (id: string) => Promise<void>;
  createRun: (
    req: {
      userRequest: string;
      executionMode?: 'PROTOTYPE' | 'PRODUCTION';
      skipApproval?: boolean;
      autoMerge?: boolean;
      modelName?: string;
      aiProvider?: string;
      projectId?: string;
      repoMode?: string | null;
      repoUrl?: string | null;
      selectedRepoIds?: string[];
      figmaUrl?: string;
      tddMode?: boolean;
      /** P3.2 — optional customer "Stop at $X" cost ceiling, USD. */
      userCostCap?: number;
    },
    idempotencyKey: string,
  ) => Promise<string>;
  cancelRun: (id: string) => Promise<void>;
  archiveRun: (id: string) => Promise<void>;
  approveRun: (
    id: string,
    approved: boolean,
    creditsConfirmed?: number,
    executionMode?: string,
  ) => Promise<void>;
  retryTask: (runId: string, taskId: string) => Promise<void>;
  // v2.5.12 — V1-only actions removed (clarify, merge, retry, reopenMergeGate,
  // implementSuggestions, rework, editAndRestart). Backend returns 410 Gone.
  // pause/resume kept pending V2 full-replay (harness-v2-remaining-work Gap 2).
  pauseRun: (id: string) => Promise<void>;
  resumeRun: (runId: string) => Promise<void>;
  patchRunStatus: (runId: string, status: string) => void;
}

export const useRunStore = create<RunStore>((set, get) => ({
  runs: [],
  activeRunId: null,
  activeRun: null,
  loading: false,
  error: null,
  retrySubmitting: null,
  statusFilter: 'ALL',
  projectFilter: null,
  scopeFilter: 'mine',
  archivedFilter: false,

  // Pagination defaults
  page: 0,
  pageSize: 20,
  totalElements: 0,
  totalPages: 0,
  hasNext: false,
  hasPrevious: false,

  setStatusFilter: (s) => {
    set({statusFilter: s, page: 0}); // Reset to first page on filter change
  },
  setProjectFilter: (projectId) => {
    set({projectFilter: projectId, page: 0});
  },
  setScopeFilter: (scope) => {
    set({scopeFilter: scope, page: 0});
  },
  setArchivedFilter: (archived) => {
    set({archivedFilter: archived, statusFilter: 'ALL', page: 0});
  },
  setPage: (page) => {
    set({page});
    get().fetchRuns();
  },

  setActiveRunId: (id) => {
    set({activeRunId: id, activeRun: null});
    if (id) get().fetchRun(id);
  },

  fetchRuns: async () => {
    // RR-200 — claim a generation; only the newest fetchRuns commits, so a slow
    // response for a stale filter/page cannot overwrite the current list.
    const gen = ++runsReqGen;
    set({loading: true, error: null});
    try {
      const {statusFilter, projectFilter, scopeFilter, archivedFilter, page, pageSize} = get();
      const result = await api.getRuns({
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        projectId: projectFilter ?? undefined,
        scope: scopeFilter ?? undefined,
        archived: archivedFilter || undefined,
        page,
        size: pageSize,
      });
      if (gen !== runsReqGen) return; // superseded by a newer request — drop.
      set({
        runs: result.content,
        page: result.page,
        totalElements: result.totalElements,
        totalPages: result.totalPages,
        hasNext: result.hasNext,
        hasPrevious: result.hasPrevious,
        loading: false,
      });
    } catch (e) {
      if (gen !== runsReqGen) return; // superseded — don't surface a stale error.
      set({loading: false, error: errMsg(e)});
    }
  },

  fetchRun: async (id) => {
    // RR-200 — a per-request generation token plus an identity check. Commit the
    // result only if this is still the newest fetchRun AND the id is still the
    // active run the user navigated to; otherwise a late/uneven response would
    // display run A's detail under run B's route.
    const gen = ++activeRunReqGen;
    try {
      const activeRun = await api.getRun(id);
      if (gen !== activeRunReqGen || get().activeRunId !== id) return;
      set({activeRun});
    } catch (e) {
      if (gen !== activeRunReqGen || get().activeRunId !== id) return;
      set({error: errMsg(e)});
    }
  },

  createRun: async (req, idempotencyKey) => {
    const {runId} = await api.createRun(req, idempotencyKey);
    set({page: 0}); // Go to first page to see the new run
    await get().fetchRuns();
    return runId;
  },

  cancelRun: async (id) => {
    await api.cancelRun(id);
    await get().fetchRuns();
    if (get().activeRunId === id) set({activeRun: null, activeRunId: null});
  },

  archiveRun: async (id) => {
    await api.archiveRun(id);
    await get().fetchRuns();
    if (get().activeRunId === id) set({activeRun: null, activeRunId: null});
  },

  approveRun: async (id, approved, creditsConfirmed?, executionMode?) => {
    await api.approveRun(id, approved, creditsConfirmed, executionMode);
    await get().fetchRun(id);
  },

  retryTask: async (runId, taskId) => {
    // RR-201 — do NOT optimistically flip the task to PENDING before the server
    // accepts the retry: a 403/409/network failure would leave the UI claiming a
    // retry is queued when the server rejected it. Track a separate
    // `retrySubmitting` marker so the UI can show a submit spinner, then commit
    // the real server state via fetchRun only after acceptance. On failure,
    // surface the error and refetch so the displayed state matches the server.
    set({retrySubmitting: taskId, error: null});
    try {
      await api.retryTask(runId, taskId);
      await get().fetchRun(runId); // commit server-confirmed task state.
    } catch (e) {
      set({error: errMsg(e)});
      await get()
        .fetchRun(runId)
        .catch(() => {}); // roll back to server truth.
      throw e;
    } finally {
      set({retrySubmitting: null});
    }
  },

  pauseRun: async (id) => {
    await api.pauseRun(id);
    await get().fetchRuns();
    if (get().activeRunId === id) await get().fetchRun(id);
  },

  resumeRun: async (runId) => {
    await api.resumeRun(runId);
    await get().fetchRuns();
    if (get().activeRunId === runId) await get().fetchRun(runId);
  },

  patchRunStatus: (runId, status) => {
    set((state) => ({
      runs: state.runs.map((r) => (r.runId === runId ? {...r, status} : r)),
      activeRun: state.activeRun?.runId === runId ? {...state.activeRun, status} : state.activeRun,
    }));
  },
}));
