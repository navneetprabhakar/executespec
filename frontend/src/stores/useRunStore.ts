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

  setStatusFilter: (s: string) => void;
  setActiveRunId: (id: string | null) => void;
  fetchRuns: () => Promise<void>;
  fetchRun: (id: string) => Promise<void>;
  createRun: (req: { userRequest: string; skipApproval?: boolean; modelName?: string }) => Promise<string>;
  cancelRun: (id: string) => Promise<void>;
  approveRun: (id: string, approved: boolean) => Promise<void>;
  mergeRun: (id: string, merge: boolean) => Promise<void>;
  retryTask: (runId: string, taskId: string) => Promise<void>;
  retryRun: (runId: string) => Promise<string>;
  patchRunStatus: (runId: string, status: string) => void;
}

export const useRunStore = create<RunStore>((set, get) => ({
  runs: [],
  activeRunId: null,
  activeRun: null,
  loading: false,
  error: null,
  statusFilter: 'ALL',

  setStatusFilter: (s) => set({ statusFilter: s }),

  setActiveRunId: (id) => {
    set({ activeRunId: id, activeRun: null });
    useUiStore.getState().setActiveTab('overview');
    if (id) get().fetchRun(id);
  },

  fetchRuns: async () => {
    set({ loading: true, error: null });
    try {
      const filter = get().statusFilter;
      const runs = await api.getRuns(filter === 'ALL' ? undefined : filter);
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
    await get().fetchRun(id);
    await get().fetchRuns();
  },

  mergeRun: async (id, merge) => {
    await api.mergeRun(id, merge);
    await get().fetchRun(id);
    await get().fetchRuns();
  },

  retryTask: async (runId, taskId) => {
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

  patchRunStatus: (runId, status) => {
    set((state) => ({
      runs: state.runs.map((r) => r.runId === runId ? { ...r, status } : r),
      activeRun: state.activeRun?.runId === runId
        ? { ...state.activeRun, status }
        : state.activeRun,
    }));
  },
}));
