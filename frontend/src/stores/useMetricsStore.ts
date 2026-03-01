import { create } from 'zustand';
import * as api from '@/lib/api';

interface MetricsStore {
  metrics: api.MetricsResponse | null;
  modelComparison: api.ModelComparisonData | null;
  loading: boolean;
  fetchMetrics: () => Promise<void>;
  fetchModelComparison: (days?: number) => Promise<void>;
}

export const useMetricsStore = create<MetricsStore>((set) => ({
  metrics: null,
  modelComparison: null,
  loading: false,

  fetchMetrics: async () => {
    set({ loading: true });
    try {
      const metrics = await api.getMetrics();
      set({ metrics, loading: false });
    } catch {
      set({ loading: false });
    }
  },

  fetchModelComparison: async (days = 30) => {
    try {
      const modelComparison = await api.getModelComparison(days);
      set({ modelComparison });
    } catch {}
  },
}));
