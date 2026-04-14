import { create } from 'zustand';
import { executionApi } from '@/lib/execution-api';
import type {
  ExecutionRunDto,
  RepoAnalysisStatus,
  CreateExecutionRequest,
  DecisionAction,
} from '@/types/execution';

interface ExecutionState {
  executionRun: ExecutionRunDto | null;
  loading: boolean;
  error: string | null;

  analysisProgress: RepoAnalysisStatus[];
  expandedSolutionId: string | null;
  solutionLogs: Record<string, string[]>;
  showLogs: boolean;

  createExecution: (projectId: string, req: CreateExecutionRequest) => Promise<string>;
  fetchExecution: (id: string) => Promise<void>;
  triggerAnalysis: (id: string) => Promise<void>;
  approveArch: (id: string, annotations?: string) => Promise<void>;
  generatePlan: (id: string) => Promise<void>;
  approvePlan: (id: string) => Promise<void>;
  decide: (id: string, solutionId: string, action: DecisionAction) => Promise<void>;
  signalManualReady: (id: string, solutionId: string) => Promise<void>;
  abort: (id: string) => Promise<void>;

  updateStatus: (status: string) => void;
  updateSolutionStatus: (solutionId: string, status: string) => void;
  updateAnalysisProgress: (repoId: string, field: 'codemapStatus' | 'archDocStatus', status: string) => void;
  appendSolutionLog: (solutionId: string, line: string) => void;

  setExpandedSolution: (id: string | null) => void;
  toggleLogs: () => void;
  seedAnalysisProgress: (repos: { repoId: string; repoName: string }[]) => void;
  reset: () => void;
}

export const useExecutionStore = create<ExecutionState>((set, get) => ({
  executionRun: null,
  loading: false,
  error: null,
  analysisProgress: [],
  expandedSolutionId: null,
  solutionLogs: {},
  showLogs: false,

  createExecution: async (projectId, reqBody) => {
    set({ loading: true, error: null });
    try {
      const run = await executionApi.create(projectId, reqBody);
      set({ executionRun: run, loading: false });
      return run.id;
    } catch (e: any) {
      set({ error: e?.message ?? 'Failed to create execution', loading: false });
      throw e;
    }
  },

  fetchExecution: async (id) => {
    try {
      const run = await executionApi.get(id);
      set({ executionRun: run });
    } catch (e: any) {
      set({ error: e?.message ?? 'Failed to load execution' });
    }
  },

  triggerAnalysis: async (id) => {
    await executionApi.triggerAnalysis(id);
  },
  approveArch: async (id, annotations) => {
    await executionApi.approveArch(id, { approved: true, annotations });
    await get().fetchExecution(id);
  },
  generatePlan: async (id) => {
    await executionApi.generatePlan(id);
  },
  approvePlan: async (id) => {
    await executionApi.approvePlan(id);
  },
  decide: async (id, solutionId, action) => {
    await executionApi.decide(id, solutionId, { action });
    await get().fetchExecution(id);
  },
  signalManualReady: async (id, solutionId) => {
    await executionApi.signalManualReady(id, solutionId);
  },
  abort: async (id) => {
    await executionApi.abort(id);
    await get().fetchExecution(id);
  },

  updateStatus: (status) => {
    const run = get().executionRun;
    if (run) set({ executionRun: { ...run, status: status as any } });
  },
  updateSolutionStatus: (solutionId, status) => {
    const run = get().executionRun;
    if (!run) return;
    const solutions = run.solutions.map((s) =>
      s.id === solutionId ? { ...s, status: status as any } : s,
    );
    set({ executionRun: { ...run, solutions } });
  },
  updateAnalysisProgress: (repoId, field, status) => {
    const progress = [...get().analysisProgress];
    const idx = progress.findIndex((p) => p.repoId === repoId);
    if (idx >= 0) progress[idx] = { ...progress[idx], [field]: status as any };
    set({ analysisProgress: progress });
  },
  appendSolutionLog: (solutionId, line) => {
    const logs = { ...get().solutionLogs };
    logs[solutionId] = [...(logs[solutionId] ?? []), line];
    set({ solutionLogs: logs });
  },

  setExpandedSolution: (id) => set({ expandedSolutionId: id }),
  toggleLogs: () => set({ showLogs: !get().showLogs }),
  seedAnalysisProgress: (repos) =>
    set({
      analysisProgress: repos.map((r) => ({
        repoId: r.repoId, repoName: r.repoName,
        codemapStatus: 'PENDING', archDocStatus: 'PENDING',
      })),
    }),
  reset: () =>
    set({
      executionRun: null,
      loading: false,
      error: null,
      analysisProgress: [],
      expandedSolutionId: null,
      solutionLogs: {},
      showLogs: false,
    }),
}));
