import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {Activity, ArrowLeft, Clock, DollarSign, TrendingUp} from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import RoleGuard from '@/components/auth/RoleGuard';
import {
  getAnalyticsUsage,
  getAnalyticsBilling,
  getRuns,
  type UsageMetrics,
  type BillingOverviewData,
  type RunSummary,
  type AuthUser,
  adminGetUser,
} from '@/lib/api';

/**
 * M-022 (Wave 3) — per-team-member dashboard.
 *
 * Drill-down from the OrgAdminDashboard members table. Re-uses the
 * existing per-USER analytics endpoint (`getAnalyticsUsage` with
 * entityType=USER) so no new backend surface is needed. Shows:
 *   • who the user is (name / email / role)
 *   • the standard usage block scoped to them
 *   • their last 10 runs (filterable in a follow-up)
 *
 * RBAC: page-level RoleGuard requires ORG_ADMIN. The backend
 * analytics endpoint enforces "you're either super-admin or you
 * belong to the same org as the requested user" — we don't
 * re-enforce here.
 */
export default function TeamMemberDashboard() {
  return (
    <RoleGuard requireOrgAdmin>
      <TeamMemberDashboardInner />
    </RoleGuard>
  );
}

function TeamMemberDashboardInner() {
  const {userId} = useParams<{userId: string}>();
  const navigate = useNavigate();
  const [user, setUser] = useState<AuthUser | null>(null);
  const [usage, setUsage] = useState<UsageMetrics | null>(null);
  const [billing, setBilling] = useState<BillingOverviewData | null>(null);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [period, setPeriod] = useState(30);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    Promise.all([
      adminGetUser(userId).catch(() => null),
      getAnalyticsUsage('USER', userId, period).catch(() => null),
      getAnalyticsBilling('USER', userId).catch(() => null),
      getRuns({size: 10, scope: 'org'}).catch(() => null),
    ])
      .then(([u, us, b, runPage]) => {
        if (cancelled) return;
        setUser(u);
        setUsage(us);
        setBilling(b);
        const list = Array.isArray(runPage)
          ? runPage
          : ((runPage as unknown as {content?: RunSummary[]} | null)?.content ?? []);
        setRuns(list.filter((r) => r.createdByUserId === userId));
        setLoading(false);
      })
      .catch((e) => {
        if (!cancelled) {
          setError(e?.message ?? 'Failed to load member dashboard');
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [userId, period]);

  if (loading) return <div className='p-8 text-muted'>Loading…</div>;
  if (error) return <div className='p-8 text-status-error'>{error}</div>;
  if (!user) return <div className='p-8 text-muted'>Member not found.</div>;

  return (
    <div className='h-full overflow-y-auto p-4 sm:p-6 space-y-4'>
      {/* Header */}
      <div className='flex items-baseline justify-between gap-3 flex-wrap'>
        <div>
          <button
            onClick={() => navigate(-1)}
            className='text-xs text-muted hover:text-primary inline-flex items-center gap-1 mb-2'
          >
            <ArrowLeft className='h-3 w-3' /> Back
          </button>
          <h1 className='text-xl font-semibold text-primary'>
            {user.firstName} {user.lastName}
          </h1>
          <p className='text-xs text-muted mt-0.5'>
            {user.email} ·{' '}
            <span className='font-mono text-secondary'>{user.orgRole ?? user.globalRole}</span>
            {' · '}
            <span className='font-mono'>{user.tier}</span>
          </p>
        </div>
        <div className='flex items-center gap-1 bg-[var(--gray-200)] rounded-md p-0.5 border border-[var(--border-default)]'>
          {[7, 30, 90].map((d) => (
            <button
              key={d}
              onClick={() => setPeriod(d)}
              className={
                'px-2 py-1 text-[11px] rounded-sm transition-colors ' +
                (period === d
                  ? 'bg-[var(--gray-300)] text-primary'
                  : 'text-muted hover:text-primary')
              }
            >
              {d}d
            </button>
          ))}
        </div>
      </div>

      {/* KPIs */}
      <div className='grid grid-cols-2 sm:grid-cols-4 gap-3'>
        <GlassPanel className='p-3'>
          <div className='flex items-center gap-1.5 text-[11px] text-muted uppercase tracking-wide'>
            <Activity className='h-3 w-3 text-accent-blue' /> Total runs
          </div>
          <div className='text-xl font-semibold text-primary tabular-nums mt-1'>
            {usage?.totalRuns ?? '—'}
          </div>
        </GlassPanel>
        <GlassPanel className='p-3'>
          <div className='flex items-center gap-1.5 text-[11px] text-muted uppercase tracking-wide'>
            <TrendingUp className='h-3 w-3 text-status-success' /> Success rate
          </div>
          <div className='text-xl font-semibold text-primary tabular-nums mt-1'>
            {usage && usage.totalRuns > 0
              ? `${Math.round((usage.successfulRuns / usage.totalRuns) * 100)}%`
              : '—'}
          </div>
        </GlassPanel>
        <GlassPanel className='p-3'>
          <div className='flex items-center gap-1.5 text-[11px] text-muted uppercase tracking-wide'>
            <Clock className='h-3 w-3 text-secondary' /> Avg duration
          </div>
          <div className='text-xl font-semibold text-primary tabular-nums mt-1'>
            {usage?.avgTimeToCompleteSeconds != null
              ? `${(usage.avgTimeToCompleteSeconds / 60).toFixed(1)}m`
              : '—'}
          </div>
        </GlassPanel>
        <GlassPanel className='p-3'>
          <div className='flex items-center gap-1.5 text-[11px] text-muted uppercase tracking-wide'>
            <DollarSign className='h-3 w-3 text-accent-blue' /> Spend (period)
          </div>
          <div className='text-xl font-semibold text-primary tabular-nums mt-1'>
            ${usage?.totalCost != null ? usage.totalCost.toFixed(2) : '0.00'}
          </div>
        </GlassPanel>
      </div>

      {/* Plan + Runs side-by-side on desktop */}
      <div className='grid grid-cols-1 lg:grid-cols-3 gap-4'>
        <GlassPanel className='p-4 lg:col-span-1'>
          <h3 className='text-xs uppercase tracking-wider text-muted mb-2'>Plan</h3>
          {billing ? (
            <div className='space-y-1 text-xs'>
              <div className='flex justify-between'>
                <span className='text-muted'>Current</span>
                <span className='text-primary font-mono'>{billing.currentPlan}</span>
              </div>
              <div className='flex justify-between'>
                <span className='text-muted'>Status</span>
                <span className='text-primary'>{billing.subscriptionStatus}</span>
              </div>
              <div className='flex justify-between'>
                <span className='text-muted'>Period usage</span>
                <span className='text-primary tabular-nums'>
                  {billing.periodUsage} / {billing.runLimit === -1 ? '∞' : billing.runLimit}
                </span>
              </div>
              <div className='flex justify-between'>
                <span className='text-muted'>Topup credits</span>
                <span className='text-primary tabular-nums'>{billing.topupCredits}</span>
              </div>
            </div>
          ) : (
            <p className='text-xs text-muted'>No billing data.</p>
          )}
        </GlassPanel>

        <GlassPanel className='p-4 lg:col-span-2'>
          <h3 className='text-xs uppercase tracking-wider text-muted mb-2'>
            Recent runs ({runs.length})
          </h3>
          {runs.length === 0 ? (
            <p className='text-xs text-muted'>No runs in the org-scoped window for this member.</p>
          ) : (
            <ul className='divide-y divide-[var(--border-default)]'>
              {runs.slice(0, 10).map((r) => (
                <li key={r.runId}>
                  <button
                    onClick={() => navigate(`/?runId=${r.runId}`)}
                    className='w-full text-left px-1 py-1.5 hover:bg-[var(--gray-200)] rounded-sm flex items-baseline gap-2'
                  >
                    <span className='font-mono text-[10px] text-muted'>{r.runId.slice(0, 8)}</span>
                    <span className='text-xs text-primary truncate flex-1'>
                      {r.runTitle ?? r.userRequest?.slice(0, 70) ?? '(untitled)'}
                    </span>
                    <span className='text-[10px] text-muted whitespace-nowrap'>{r.status}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </GlassPanel>
      </div>
    </div>
  );
}
