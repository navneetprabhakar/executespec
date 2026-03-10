import { create } from 'zustand';
import * as api from '@/lib/api';
import { useUiStore } from '@/stores/useUiStore';

interface RunStore {
  runs: api.RunSummary[];
  activeRunId: string | null;
  activeRun: api.RunDetail | null;
  loading: boolean;
  error: string | null;
  statusFilter: string;
  projectFilter: string | null;

  setStatusFilter: (s: string) => void;
  setProjectFilter: (projectId: string | null) => void;
  setActiveRunId: (id: string | null) => void;
  fetchRuns: () => Promise<void>;
  fetchRun: (id: string) => Promise<void>;
  createRun: (req: { userRequest: string; skipApproval?: boolean; autoMerge?: boolean; modelName?: string; projectId?: string; repoMode?: string | null; repoUrl?: string | null }) => Promise<string>;
  cancelRun: (id: string) => Promise<void>;
  approveRun: (id: string, approved: boolean) => Promise<void>;
  clarifyRun: (id: string, response: string) => Promise<void>;
  mergeRun: (id: string, merge: boolean) => Promise<void>;
  retryTask: (runId: string, taskId: string) => Promise<void>;
  retryRun: (runId: string) => Promise<string>;
  reopenMergeGate: (runId: string) => Promise<void>;
  implementSuggestions: (runId: string) => Promise<void>;
  reworkRun: (runId: string) => Promise<void>;
  pauseRun: (id: string) => Promise<void>;
  editAndRestartRun: (runId: string, newGoal: string) => Promise<string>;
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

  setStatusFilter: (s) => set({ statusFilter: s }),
  setProjectFilter: (projectId) => set({ projectFilter: projectId }),

  setActiveRunId: (id) => {
    set({ activeRunId: id, activeRun: null });
    useUiStore.getState().setActiveTab('overview');
    if (id) get().fetchRun(id);
  },

  fetchRuns: async () => {
    set({ loading: true, error: null });
    try {
      const { statusFilter, projectFilter } = get();
      const runs = await api.getRuns({
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        projectId: projectFilter ?? undefined,
      });
      set({ runs, loading: false });
    } catch (e: any) {
      set({ loading: false, error: e.message });
    }
  },

  fetchRun: async (id) => {
    try {
      const activeRun = await api.getRun(id);
      set({ activeRun });
    } catch (e: any) {
      set({ error: e.message });
    }
  },

  createRun: async (req) => {
    const { runId } = await api.createRun(req);
    await get().fetchRuns();
    return runId;
  },

  cancelRun: async (id) => {
    await api.cancelRun(id);
    await get().fetchRuns();
    if (get().activeRunId === id) set({ activeRun: null, activeRunId: null });
  },

  approveRun: async (id, approved) => {
    await api.approveRun(id, approved);
    // fetchRun refreshes the detail; the runs list is kept current by SSE patchRunStatus.
    await get().fetchRun(id);
  },

  clarifyRun: async (id, response) => {
    await api.clarifyRun(id, response);
    await get().fetchRun(id);
  },

  mergeRun: async (id, merge) => {
    await api.mergeRun(id, merge);
    // fetchRun refreshes the detail; the runs list is kept current by SSE patchRunStatus.
    await get().fetchRun(id);
  },

  retryTask: async (runId, taskId) => {
    // Optimistically patch task to PENDING so the UI updates immediately
    set((s) => ({
      activeRun: s.activeRun ? {
        ...s.activeRun,
        tasks: s.activeRun.tasks?.map((t: any) =>
          t.taskId === taskId ? { ...t, status: 'PENDING' } : t),
      } : null,
    }));
    await api.retryTask(runId, taskId);
    await get().fetchRun(runId);
  },

  retryRun: async (runId) => {
    const newRun = await api.retryRun(runId);
    await get().fetchRuns();
    // Switch focus to the new run
    get().setActiveRunId(newRun.runId);
    return newRun.runId;
  },

  reopenMergeGate: async (runId) => {
    await api.reopenMergeGate(runId);
    await get().fetchRun(runId);
  },

  implementSuggestions: async (runId) => {
    await api.implementSuggestions(runId);
    await get().fetchRun(runId);
  },

  reworkRun: async (runId) => {
    await api.reworkRun(runId);
    await get().fetchRun(runId);
  },

  pauseRun: async (id) => {
    await api.pauseRun(id);
    await get().fetchRuns();
    if (get().activeRunId === id) await get().fetchRun(id);
  },

  editAndRestartRun: async (runId, newGoal) => {
    const newRun = await api.editAndRestartRun(runId, newGoal);
    await get().fetchRuns();
    get().setActiveRunId(newRun.runId);
    return newRun.runId;
  },

  resumeRun: async (runId) => {
    await api.resumeRun(runId);
    await get().fetchRuns();
    if (get().activeRunId === runId) await get().fetchRun(runId);
  },

  patchRunStatus: (runId, status) => {
    set((state) => ({
      runs: state.runs.map((r) => r.runId === runId ? { ...r, status } : r),
      activeRun: state.activeRun?.runId === runId
        ? { ...state.activeRun, status }
        : state.activeRun,
    }));
  },
}));
