import {create} from 'zustand';
import * as api from '@/lib/api';

interface MetricsStore {
  metrics: api.MetricsResponse | null;
  modelComparison: api.ModelComparisonData | null;
  userRunsSummary: api.UserRunsSummaryData | null;
  loading: boolean;
  days: number; // 0 = all-time, 7, 30, 90

  setDays: (days: number) => void;
  fetchMetrics: (days?: number) => Promise<void>;
  fetchModelComparison: (days?: number) => Promise<void>;
  fetchUserRunsSummary: (days?: number) => Promise<void>;
  fetchAll: () => Promise<void>;
  // F-07 fix (2026-05-09) — full reset, used by useAuthStore.logout.
  reset: () => void;
}

export const useMetricsStore = create<MetricsStore>((set, get) => ({
  metrics: null,
  modelComparison: null,
  userRunsSummary: null,
  loading: false,
  days: 30,

  setDays: (days) => {
    set({days});
    get().fetchAll();
  },

  fetchMetrics: async (days?) => {
    const d = days ?? get().days;
    set({loading: true});
    try {
      const metrics = await api.getMetrics(d);
      set({metrics, loading: false});
    } catch {
      set({loading: false});
    }
  },

  fetchModelComparison: async (days?) => {
    const d = days ?? get().days;
    try {
      const modelComparison = await api.getModelComparison(d);
      set({modelComparison});
    } catch {}
  },

  fetchUserRunsSummary: async (days?) => {
    const d = days ?? get().days;
    try {
      const userRunsSummary = await api.getUserRunsSummary(d);
      set({userRunsSummary});
    } catch {}
  },

  fetchAll: async () => {
    const d = get().days;
    await Promise.allSettled([
      get().fetchMetrics(d),
      get().fetchModelComparison(d),
      get().fetchUserRunsSummary(d),
    ]);
  },

  reset: () =>
    set({
      metrics: null,
      modelComparison: null,
      userRunsSummary: null,
      loading: false,
      days: 30,
    }),
}));
