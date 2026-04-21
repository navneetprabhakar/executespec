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
import { useViewMode } from '@/hooks/useViewMode';
import { fmtTokens } from '@/lib/utils';
import PageShell, { Section, StatGrid, Stat } from '@/components/shared/PageShell';

export default function DashboardPage() {
  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const { metrics, modelComparison, userRunsSummary, days, fetchAll } = useMetricsStore();
  const { runs, fetchRuns } = useRunStore();
  const { isVibe } = useViewMode();

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

  // ── Vibe mode: simplified dashboard ─────────────────────────────────────
  if (isVibe) {
    return (
      <PageShell
        eyebrow="Overview"
        title="Your workspace, at a glance."
        accent="glance"
        description="Only what matters most. Switch to Pro for the full breakdown."
        maxWidth="4xl"
        hero
      >
        <StatGrid cols={2}>
          <Stat label="Total runs"  value={metrics?.totalRuns ?? 0} />
          <Stat label="Active"      value={metrics?.activeRuns ?? 0} tone="purple" pulse />
        </StatGrid>

        <Section eyebrow="Recent activity" title="Latest runs" card={false}>
          <ActivityFeed runs={runs} />
        </Section>
      </PageShell>
    );
  }

  // ── Pro mode: full dashboard ────────────────────────────────────────────
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
