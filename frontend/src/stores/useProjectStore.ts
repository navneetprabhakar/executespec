import {create} from 'zustand';
import * as api from '@/lib/api';
import type {
  ProjectDashboardDTO,
  ProjectRepoLink,
  ProjectRepo,
  ArchitectureDoc,
  ScoreEntry,
} from '@/types/project';

interface ProjectStore {
  dashboard: ProjectDashboardDTO | null;
  repos: ProjectRepoLink[];
  availableRepos: ProjectRepo[];
  architectureDoc: ArchitectureDoc | null;
  scoreTrend: ScoreEntry[];
  loading: boolean;
  error: string | null;

  fetchDashboard: (projectId: string) => Promise<void>;
  fetchRepos: (projectId: string) => Promise<void>;
  fetchAvailableRepos: (scope?: 'ORG' | 'USER' | 'ALL') => Promise<void>;
  fetchArchitectureDoc: (projectId: string) => Promise<void>;
  fetchScoreTrend: (projectId: string) => Promise<void>;
  reset: () => void;
}

export const useProjectStore = create<ProjectStore>((set) => ({
  dashboard: null,
  repos: [],
  availableRepos: [],
  architectureDoc: null,
  scoreTrend: [],
  loading: false,
  error: null,

  fetchDashboard: async (projectId: string) => {
    set({loading: true, error: null});
    try {
      const dashboard = await api.getProjectDashboard(projectId);
      set({dashboard, loading: false});
    } catch (e: unknown) {
      set({error: e instanceof Error ? e.message : 'Failed to load dashboard', loading: false});
    }
  },

  fetchRepos: async (projectId: string) => {
    try {
      const repos = await api.getProjectRepos(projectId);
      set({repos});
    } catch (e: unknown) {
      set({error: e instanceof Error ? e.message : 'Failed to load repos'});
    }
  },

  fetchAvailableRepos: async (scope = 'ALL') => {
    try {
      const availableRepos = await api.listRepos(scope);
      set({availableRepos});
    } catch (e: unknown) {
      set({error: e instanceof Error ? e.message : 'Failed to load available repos'});
    }
  },

  fetchArchitectureDoc: async (projectId: string) => {
    try {
      const architectureDoc = await api.getArchitectureDoc(projectId);
      set({architectureDoc});
    } catch (e: unknown) {
      set({error: e instanceof Error ? e.message : 'Failed to load architecture doc'});
    }
  },

  fetchScoreTrend: async (projectId: string) => {
    try {
      const scoreTrend = await api.getProjectScores(projectId);
      set({scoreTrend});
    } catch (e: unknown) {
      set({error: e instanceof Error ? e.message : 'Failed to load scores'});
    }
  },

  reset: () =>
    set({
      dashboard: null,
      repos: [],
      availableRepos: [],
      architectureDoc: null,
      scoreTrend: [],
      error: null,
    }),
}));
