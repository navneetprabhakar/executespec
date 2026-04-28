import { useEffect } from 'react';
import { Navigate } from 'react-router-dom';
import TokenBubbleChart from '@/components/dashboard/TokenBubbleChart';
import ModelComparisonChart from '@/components/dashboard/ModelComparisonChart';
import UserRunsChart from '@/components/dashboard/UserRunsChart';
import DateRangeFilter from '@/components/dashboard/DateRangeFilter';
import ActivityFeed from '@/components/dashboard/ActivityFeed';
import { useMetricsStore } from '@/stores/useMetricsStore';
import { useRunStore } from '@/stores/useRunStore';
import { useAuthStore, selectIsOrgAdmin } from '@/stores/useAuthStore';
import { fmtTokens } from '@/lib/utils';
import PageShell, { Section, StatGrid, Stat } from '@/components/shared/PageShell';

export default function DashboardPage() {
  // R-190 (post-cutover) — useViewMode no longer exposes isVibe; the
  // simplified-dashboard branch (2-stat grid + activity feed only) was
  // deleted with the Vibe surface. The full Pro dashboard is the only
  // path now.
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

  // ── Full dashboard (was Pro mode pre-R-190) ─────────────────────────────
  return (
    <PageShell
      eyebrow="Dashboard"
      title="Ship velocity, at a glance."
      accent="glance"
      description="Run volume, token spend, model mix, and activity across your workspace."
      actions={<DateRangeFilter />}
      hero
    >
      {/* Primary metrics row */}
      <StatGrid cols={4}>
        <Stat label="Total runs"   value={metrics?.totalRuns ?? 0} />
        <Stat label="Active"       value={metrics?.activeRuns ?? 0} tone="purple" pulse />
        <Stat
          label="Total tokens"
          value={fmtTokens((metrics?.totalInputTokens ?? 0) + (metrics?.totalOutputTokens ?? 0))}
          tone="brand"
        />
        <Stat label="Completed"    value={metrics?.completedRuns ?? 0} tone="success" />
      </StatGrid>

      {/* Charts row 1 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-5">
        <Section eyebrow="Tokens" title="Token usage over time" card>
          <TokenBubbleChart metrics={metrics} />
        </Section>
        <Section eyebrow="Models" title="Model comparison" card>
          <ModelComparisonChart data={modelComparison} />
        </Section>
      </div>

      {/* Charts row 2 */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-5">
        <Section eyebrow="Users" title="Runs by user" card>
          <UserRunsChart data={userRunsSummary} />
        </Section>
        <Section eyebrow="Activity" title="Recent runs" card>
          <ActivityFeed runs={runs} />
        </Section>
      </div>
    </PageShell>
  );
}
