import { useEffect } from 'react';
import MetricCard from '@/components/dashboard/MetricCard';
import TokenBubbleChart from '@/components/dashboard/TokenBubbleChart';
import AgentCommGraph from '@/components/dashboard/AgentCommGraph';
import ModelComparisonChart from '@/components/dashboard/ModelComparisonChart';
import ActivityFeed from '@/components/dashboard/ActivityFeed';
import { useMetricsStore } from '@/stores/useMetricsStore';
import { useRunStore } from '@/stores/useRunStore';
import { fmtTokens, fmtCost } from '@/lib/utils';

export default function DashboardPage() {
  const { metrics, modelComparison, fetchMetrics, fetchModelComparison } = useMetricsStore();
  const { runs, fetchRuns } = useRunStore();

  useEffect(() => {
    fetchMetrics();
    fetchModelComparison();
    fetchRuns();
    const interval = setInterval(() => { fetchMetrics(); fetchRuns(); }, 30000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      {/* Metric cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <MetricCard
          label="Total Runs"
          value={metrics?.totalRuns ?? 0}
        />
        <MetricCard
          label="Active Runs"
          value={metrics?.activeRuns ?? 0}
          accent="#8B5CF6"
          pulse
        />
        <MetricCard
          label="Total Tokens"
          value={metrics?.totalTokens ?? 0}
          format={fmtTokens}
          accent="#06B6D4"
        />
        <MetricCard
          label="Total Cost"
          value={metrics?.totalCostUsd ?? 0}
          format={fmtCost}
          accent="#10B981"
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-3">
        <TokenBubbleChart metrics={metrics} />
        <AgentCommGraph />
        <ModelComparisonChart data={modelComparison} />
      </div>

      {/* Activity feed */}
      <ActivityFeed runs={runs} />
    </div>
  );
}
