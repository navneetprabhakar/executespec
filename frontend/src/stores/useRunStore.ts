import {create} from 'zustand';
import {errMsg} from '@/lib/utils';
import * as api from '@/lib/api';
import {useUiStore} from '@/stores/useUiStore';

// Inlined from the retired `@/components/billing/UsageBadge` — fire a window event
// for usage-badge consumers (legacy UsageBadge is gone after the 2026-05-16
// chrome cleanup; the event is harmless without a listener).
const refreshUsageBadge = () => window.dispatchEvent(new CustomEvent('usage-badge:refresh'));

interface RunStore {
  runs: api.RunSummary[];
  activeRunId: string | null;
  activeRun: api.RunDetail | null;
  loading: boolean;
  error: string | null;
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
  createRun: (req: {
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
  }) => Promise<string>;
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
    useUiStore.getState().setActiveTab('overview');
    if (id) get().fetchRun(id);
  },

  fetchRuns: async () => {
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
      set({loading: false, error: errMsg(e)});
    }
  },

  fetchRun: async (id) => {
    try {
      const activeRun = await api.getRun(id);
      set({activeRun});
    } catch (e) {
      set({error: errMsg(e)});
    }
  },

  createRun: async (req) => {
    const {runId} = await api.createRun(req);
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
    // Credits were charged — refresh sidebar usage badge
    if (approved) refreshUsageBadge();
  },

  retryTask: async (runId, taskId) => {
    set((s) => ({
      activeRun: s.activeRun
        ? {
            ...s.activeRun,
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            tasks: s.activeRun.tasks?.map((t: any) =>
              t.taskId === taskId ? {...t, status: 'PENDING'} : t,
            ),
          }
        : null,
    }));
    await api.retryTask(runId, taskId);
    await get().fetchRun(runId);
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
