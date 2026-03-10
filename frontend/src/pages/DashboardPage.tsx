import { useEffect } from 'react';
import { Navigate } from 'react-router-dom';
import MetricCard from '@/components/dashboard/MetricCard';
import TokenBubbleChart from '@/components/dashboard/TokenBubbleChart';
import ModelComparisonChart from '@/components/dashboard/ModelComparisonChart';
import ActivityFeed from '@/components/dashboard/ActivityFeed';
import { useMetricsStore } from '@/stores/useMetricsStore';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import { fmtTokens } from '@/lib/utils';

export default function DashboardPage() {
  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const { metrics, modelComparison, fetchMetrics, fetchModelComparison } = useMetricsStore();
  const { runs, fetchRuns } = useRunStore();

  useEffect(() => {
    if (!isAdmin) return;
    fetchMetrics();
    fetchModelComparison();
    fetchRuns();
    const interval = setInterval(() => { fetchMetrics(); }, 30000);
    return () => clearInterval(interval);
  }, [isAdmin]);

  // Redirect non-admin users to the Runs page
  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

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
          value={(metrics?.totalInputTokens ?? 0) + (metrics?.totalOutputTokens ?? 0)}
          format={fmtTokens}
          accent="#06B6D4"
        />
        <MetricCard
          label="Completed"
          value={metrics?.completedRuns ?? 0}
          accent="#10B981"
        />
      </div>

      {/* Charts */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <TokenBubbleChart metrics={metrics} />
        <ModelComparisonChart data={modelComparison} />
      </div>

      {/* Activity feed */}
      <ActivityFeed runs={runs} />
    </div>
  );
}
