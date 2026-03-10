import { useEffect } from 'react';
import { Navigate } from 'react-router-dom';
import MetricCard from '@/components/dashboard/MetricCard';
import TokenBubbleChart from '@/components/dashboard/TokenBubbleChart';
import ModelComparisonChart from '@/components/dashboard/ModelComparisonChart';
import UserRunsChart from '@/components/dashboard/UserRunsChart';
import DateRangeFilter from '@/components/dashboard/DateRangeFilter';
import ActivityFeed from '@/components/dashboard/ActivityFeed';
import { useMetricsStore } from '@/stores/useMetricsStore';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import { fmtTokens } from '@/lib/utils';

export default function DashboardPage() {
  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const { metrics, modelComparison, userRunsSummary, days, fetchAll } = useMetricsStore();
  const { runs, fetchRuns } = useRunStore();

  useEffect(() => {
    if (!isAdmin) return;
    fetchAll();
    fetchRuns();
    const interval = setInterval(() => { fetchAll(); }, 30000);
    return () => clearInterval(interval);
  }, [isAdmin, days]);

  // Redirect non-admin users to the Runs page
  if (!isAdmin) {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      {/* Header with date filter */}
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-zinc-100">Dashboard</h2>
        <DateRangeFilter />
      </div>

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

      {/* Charts row 1 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <TokenBubbleChart metrics={metrics} />
        <ModelComparisonChart data={modelComparison} />
      </div>

      {/* Charts row 2 + Activity feed */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <UserRunsChart data={userRunsSummary} />
        <ActivityFeed runs={runs} />
      </div>
    </div>
  );
}
