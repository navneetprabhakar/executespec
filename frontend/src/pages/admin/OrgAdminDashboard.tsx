import {useState, useEffect, useCallback} from 'react';
import {useNavigate} from 'react-router-dom';
import AccessDenied from '@/components/shared/AccessDenied';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  BarChart,
  Bar,
  PieChart,
  Pie,
  Cell,
} from 'recharts';
import {Activity, DollarSign, Users, UserCheck} from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import MetricCard from '@/components/dashboard/MetricCard';
import {useAuthStore, selectIsOrgAdmin} from '@/stores/useAuthStore';
import {
  getAnalyticsUsage,
  getAnalyticsMembers,
  getAnalyticsBilling,
  getMyOrg,
  adjustOrgSeats,
  type UsageMetrics,
  type MemberUsage,
  type BillingOverviewData,
  type OrgDto,
} from '@/lib/api';
import {cn, errMsg} from '@/lib/utils';

const PERIODS = [
  {label: '7d', value: 7},
  {label: '30d', value: 30},
  {label: '90d', value: 90},
];

const CHART_COLORS = [
  '#8B5CF6',
  '#3B82F6',
  '#10B981',
  '#F59E0B',
  '#EF4444',
  '#EC4899',
  '#6366F1',
  '#14B8A6',
];

export default function OrgAdminDashboard() {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const isAdmin = useAuthStore(selectIsOrgAdmin);

  const [period, setPeriod] = useState(30);
  const [usage, setUsage] = useState<UsageMetrics | null>(null);
  const [members, setMembers] = useState<MemberUsage[]>([]);
  const [billing, setBilling] = useState<BillingOverviewData | null>(null);
  const [orgDto, setOrgDto] = useState<OrgDto | null>(null);
  const [seatsDraft, setSeatsDraft] = useState<number>(1);
  const [seatsBusy, setSeatsBusy] = useState(false);
  const [seatsMsg, setSeatsMsg] = useState<string | null>(null);
  const [seatsError, setSeatsError] = useState<string | null>(null);
  // M-002 — first-seat-purchase on-ramp: pre-pick a seat count for the
  // /plans?tier=team CTA when the org isn't on TEAM yet.
  const [onrampSeats, setOnrampSeats] = useState<number>(3);
  const [loading, setLoading] = useState(true);
  const [sortCol, setSortCol] = useState<'runCount' | 'successRate' | 'lastRunDate'>('runCount');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('desc');

  const entityType = user?.orgId ? 'ORG' : 'USER';
  const entityId = user?.orgId ?? user?.id ?? '';

  const fetchData = useCallback(async () => {
    if (!entityId) return;
    setLoading(true);
    try {
      const [u, b] = await Promise.all([
        getAnalyticsUsage(entityType, entityId, period),
        getAnalyticsBilling(entityType, entityId),
      ]);
      setUsage(u);
      setBilling(b);

      if (entityType === 'ORG') {
        const m = await getAnalyticsMembers(entityId, period);
        setMembers(m);
        try {
          const dto = await getMyOrg();
          setOrgDto(dto);
          setSeatsDraft(dto?.seatCount ?? 1);
        } catch (e) {
          console.warn('Org fetch failed', e);
        }
      }
    } catch (e) {
      console.error('Analytics fetch failed', e);
    } finally {
      setLoading(false);
    }
  }, [entityType, entityId, period]);

  const handleSeatsAdjust = async () => {
    setSeatsBusy(true);
    setSeatsError(null);
    setSeatsMsg(null);
    try {
      const result = await adjustOrgSeats(seatsDraft);
      setSeatsMsg(result.message);
      setOrgDto((prev) => (prev ? {...prev, seatCount: result.newSeats} : prev));
    } catch (e) {
      setSeatsError(errMsg(e) ?? 'Seat adjustment failed');
      // reset draft to server value on error
      setSeatsDraft(orgDto?.seatCount ?? 1);
    } finally {
      setSeatsBusy(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  // Non-admin standalone users see their own analytics. R1 (2026-05-01
  // follow-up) — if the gate ever does fire (user not logged in / no
  // admin role), render AccessDenied in place rather than redirecting
  // to "/" and chaining through HomeRedirectPage to /projects/empty.
  if (!isAdmin && !user?.id) {
    return <AccessDenied feature='Org analytics' requiredRole='ORG_ADMIN' />;
  }

  const successRate =
    usage && usage.totalRuns > 0 ? Math.round((usage.successfulRuns / usage.totalRuns) * 100) : 0;

  const avgTimeMins = usage ? Math.round(usage.avgTimeToCompleteSeconds / 60) : 0;

  const sparkData = usage?.runsByDay.map((d) => ({v: d.count})) ?? [];

  // Sort members
  const sortedMembers = [...members].sort((a, b) => {
    const aVal = a[sortCol] ?? 0;
    const bVal = b[sortCol] ?? 0;
    if (typeof aVal === 'string' && typeof bVal === 'string') {
      return sortDir === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
    }
    return sortDir === 'asc'
      ? (aVal as number) - (bVal as number)
      : (bVal as number) - (aVal as number);
  });

  const toggleSort = (col: typeof sortCol) => {
    if (sortCol === col) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      setSortCol(col);
      setSortDir('desc');
    }
  };

  // Cost by model for donut chart
  const costData = usage
    ? Object.entries(usage.costByModel).map(([model, cost]) => ({
        name: model,
        value: Math.round(cost * 100) / 100,
      }))
    : [];

  // Runs by project for bar chart
  const projectData = usage
    ? Object.entries(usage.runsByProject)
        .slice(0, 10)
        .map(([name, count]) => ({
          name: name.length > 20 ? name.slice(0, 20) + '...' : name,
          count,
        }))
    : [];

  return (
    <div className='h-full overflow-y-auto p-4 space-y-4'>
      {/* Header */}
      <div className='flex items-center justify-between'>
        <div className='flex items-center gap-2'>
          <Activity className='h-5 w-5 text-secondary' />
          {/* 2026-05-14 (responsive UX parity audit) — promoted from <h2>
              to <h1> so the page has a top-level heading. Pre-fix the
              page rendered an empty document title (probe captured
              h1Text="" across all viewports + tiers). */}
          <h1 className='text-lg font-semibold text-primary'>Analytics</h1>
        </div>

        {/* Period selector */}
        <div className='flex rounded-md border border-[var(--border-default)] overflow-hidden'>
          {PERIODS.map((p) => (
            <button
              key={p.value}
              onClick={() => setPeriod(p.value)}
              className={cn(
                'px-3 py-1.5 text-xs font-medium transition-colors',
                period === p.value
                  ? 'bg-[#8B5CF6]/20 text-[#8B5CF6]'
                  : 'text-muted hover:text-secondary hover:bg-[var(--gray-200)]',
              )}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {loading && !usage ? (
        <div className='flex items-center justify-center py-20'>
          <div className='animate-spin h-6 w-6 border-2 border-[var(--gray-400)] border-t-[#8B5CF6] rounded-full' />
        </div>
      ) : (
        <>
          {/* Row 1: Stat cards */}
          <div className='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3'>
            <MetricCard label='Total Runs' value={usage?.totalRuns ?? 0} sparkData={sparkData} />
            <MetricCard
              label='Success Rate'
              value={successRate}
              format={(n) => `${n}%`}
              accent='#10B981'
            />
            <MetricCard
              label='Avg Time'
              value={avgTimeMins}
              format={(n) => `${n}m`}
              accent='#3B82F6'
            />
            <MetricCard
              label='Total Cost'
              value={Math.round((usage?.totalCost ?? 0) * 100) / 100}
              format={(n) => `$${n.toFixed(2)}`}
              accent='#F59E0B'
            />
          </div>

          {/* Row 2: Runs per day line chart */}
          <GlassPanel className='p-4'>
            <p className='text-xs text-muted font-medium uppercase tracking-wider mb-3'>
              Runs per Day
            </p>
            {usage && usage.runsByDay.length > 0 ? (
              <div className='h-52'>
                <ResponsiveContainer width='100%' height='100%'>
                  <LineChart
                    data={usage.runsByDay}
                    margin={{top: 5, right: 10, left: 0, bottom: 5}}
                  >
                    <CartesianGrid strokeDasharray='3 3' stroke='rgba(255,255,255,0.05)' />
                    <XAxis
                      dataKey='date'
                      tick={{fontSize: 12, fill: '#71717a'}}
                      tickFormatter={(d) => d.slice(5)}
                    />
                    <YAxis tick={{fontSize: 12, fill: '#71717a'}} allowDecimals={false} />
                    <Tooltip
                      contentStyle={{
                        background: '#18181b',
                        border: '1px solid rgba(255,255,255,0.1)',
                        borderRadius: 4,
                        fontSize: 12,
                      }}
                      labelStyle={{color: '#a1a1aa'}}
                    />
                    <Line
                      type='monotone'
                      dataKey='count'
                      stroke='#8B5CF6'
                      strokeWidth={2}
                      dot={false}
                      name='Runs'
                    />
                    <Line
                      type='monotone'
                      dataKey='successRate'
                      stroke='#10B981'
                      strokeWidth={1.5}
                      dot={false}
                      name='Success %'
                    />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <p className='text-xs text-muted py-10 text-center'>No run data for this period</p>
            )}
          </GlassPanel>

          {/* Row 3: Runs by Project + Cost by Model */}
          <div className='grid grid-cols-1 lg:grid-cols-2 gap-3'>
            <GlassPanel className='p-4'>
              <p className='text-xs text-muted font-medium uppercase tracking-wider mb-3'>
                Runs by Project
              </p>
              {projectData.length > 0 ? (
                <div className='h-52'>
                  <ResponsiveContainer width='100%' height='100%'>
                    <BarChart
                      data={projectData}
                      layout='vertical'
                      margin={{top: 5, right: 10, left: 0, bottom: 5}}
                    >
                      <CartesianGrid strokeDasharray='3 3' stroke='rgba(255,255,255,0.05)' />
                      <XAxis
                        type='number'
                        tick={{fontSize: 12, fill: '#71717a'}}
                        allowDecimals={false}
                      />
                      <YAxis
                        type='category'
                        dataKey='name'
                        tick={{fontSize: 12, fill: '#71717a'}}
                        width={120}
                      />
                      <Tooltip
                        contentStyle={{
                          background: '#18181b',
                          border: '1px solid rgba(255,255,255,0.1)',
                          borderRadius: 4,
                          fontSize: 12,
                        }}
                      />
                      <Bar dataKey='count' fill='#8B5CF6' radius={[0, 4, 4, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p className='text-xs text-muted py-10 text-center'>No project data</p>
              )}
            </GlassPanel>

            <GlassPanel className='p-4'>
              <p className='text-xs text-muted font-medium uppercase tracking-wider mb-3'>
                Cost by Model
              </p>
              {costData.length > 0 ? (
                <div className='h-52'>
                  <ResponsiveContainer width='100%' height='100%'>
                    <PieChart>
                      <Pie
                        data={costData}
                        cx='50%'
                        cy='50%'
                        innerRadius={50}
                        outerRadius={80}
                        paddingAngle={2}
                        dataKey='value'
                        label={({name, value}) => `${name.split('/').pop()}: $${value}`}
                        labelLine={false}
                      >
                        {costData.map((_, i) => (
                          <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                        ))}
                      </Pie>
                      <Tooltip
                        contentStyle={{
                          background: '#18181b',
                          border: '1px solid rgba(255,255,255,0.1)',
                          borderRadius: 4,
                          fontSize: 12,
                        }}
                        formatter={(v: number) => [`$${v.toFixed(2)}`, 'Cost']}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p className='text-xs text-muted py-10 text-center'>No cost data</p>
              )}
            </GlassPanel>
          </div>

          {/* Row 4: Member usage table (ORG only) */}
          {entityType === 'ORG' && (
            <GlassPanel className='p-4'>
              <div className='flex items-center gap-2 mb-3'>
                <Users className='h-4 w-4 text-secondary' />
                <p className='text-xs text-muted font-medium uppercase tracking-wider'>
                  Member Activity
                </p>
              </div>
              {sortedMembers.length > 0 ? (
                <div className='overflow-x-auto'>
                  <table className='w-full text-xs md:min-w-0 responsive-table'>
                    <thead>
                      <tr className='border-b border-[var(--border-default)]'>
                        <th className='text-left py-2 px-2 text-muted font-medium'>Name</th>
                        <th className='text-left py-2 px-2 text-muted font-medium'>Email</th>
                        <th
                          className='text-right py-2 px-2 text-muted font-medium cursor-pointer hover:text-secondary'
                          onClick={() => toggleSort('runCount')}
                        >
                          Runs {sortCol === 'runCount' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th
                          className='text-right py-2 px-2 text-muted font-medium cursor-pointer hover:text-secondary'
                          onClick={() => toggleSort('successRate')}
                        >
                          Success %{' '}
                          {sortCol === 'successRate' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th
                          className='text-right py-2 px-2 text-muted font-medium cursor-pointer hover:text-secondary'
                          onClick={() => toggleSort('lastRunDate')}
                        >
                          Last Active{' '}
                          {sortCol === 'lastRunDate' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {sortedMembers.map((m) => (
                        <tr
                          key={m.userId}
                          onClick={() => navigate(`/team/${m.userId}`)}
                          className='border-b border-[var(--gray-200)] hover:bg-[var(--gray-100)] transition-colors cursor-pointer'
                          title='Open per-member dashboard'
                        >
                          <td className='py-2 px-2 text-primary' data-label='Name'>
                            {m.firstName} {m.lastName}
                          </td>
                          <td className='py-2 px-2 text-secondary' data-label='Email'>
                            {m.email}
                          </td>
                          <td
                            className='py-2 px-2 text-right text-primary font-mono'
                            data-label="toggleSort('runCount')} > Runs {sortCol === 'runCount' ? (sortDir === 'asc' ? '↑' : '↓') : ''}"
                          >
                            {m.runCount}
                          </td>
                          <td
                            className='py-2 px-2 text-right'
                            data-label="toggleSort('successRate')} > Success %{' '} {sortCol === 'successRate' ? (sortDir === 'asc' ? '↑' : '↓') : ''}"
                          >
                            <span
                              className={cn(
                                'font-mono',
                                m.successRate >= 80
                                  ? 'text-status-success'
                                  : m.successRate >= 50
                                    ? 'text-accent-amber'
                                    : 'text-rose-400',
                              )}
                            >
                              {m.successRate}%
                            </span>
                          </td>
                          <td
                            className='py-2 px-2 text-right text-muted'
                            data-label="toggleSort('lastRunDate')} > Last Active{' '} {sortCol === 'lastRunDate' ? (sortDir === 'asc' ? '↑' : '↓') : ''}"
                          >
                            {m.lastRunDate ? new Date(m.lastRunDate).toLocaleDateString() : '-'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className='text-xs text-muted py-6 text-center'>
                  No member activity in this period
                </p>
              )}
            </GlassPanel>
          )}

          {/* Row 4.4: First-seat-purchase on-ramp (M-002, Wave 1).
              Renders for ORG admins on a non-TEAM tier (excluding ENTERPRISE
              which is sales-managed). Replaces the dead "+seat 402" path:
              instead of a hard error, the buyer lands on /plans with the
              TEAM tier pre-selected and their chosen seat count carried
              over. */}
          {/* Tier-coherence: shown to FREE + PRO orgs (sub-Team tiers). TEAM
              already has seats; ENTERPRISE is sales-managed. */}
          {entityType === 'ORG' && orgDto && (orgDto.tier === 'FREE' || orgDto.tier === 'PRO') && (
            <GlassPanel className='p-4'>
              <div className='flex items-center gap-2 mb-3'>
                <UserCheck className='h-4 w-4 text-secondary' />
                <p className='text-xs text-muted font-medium uppercase tracking-wider'>
                  Add team seats
                </p>
              </div>
              <p className='text-sm text-secondary mb-3'>
                Your org is on the <span className='font-semibold text-primary'>{orgDto.tier}</span>{' '}
                plan. Team seats unlock a shared credit pool, member management, and per-seat
                billing. Start the Team plan to begin adding seats.
              </p>
              <div className='flex items-center gap-3 flex-wrap'>
                <label className='text-xs text-muted font-mono uppercase tracking-wide'>
                  Seats
                </label>
                <input
                  type='number'
                  min={2}
                  max={20}
                  value={onrampSeats}
                  onChange={(e) => {
                    const n = parseInt(e.target.value, 10);
                    if (!Number.isNaN(n)) setOnrampSeats(Math.max(2, Math.min(20, n)));
                  }}
                  className='w-20 px-2 py-1 bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm text-sm text-primary focus:outline-none focus:ring-1 focus:ring-accent-blue'
                />
                <button
                  onClick={() => navigate(`/plans?tier=team&seats=${onrampSeats}&from=admin`)}
                  className='px-3 py-1.5 bg-accent-blue text-primary text-xs font-medium rounded-sm hover:bg-accent-blue-hover'
                >
                  Continue to checkout →
                </button>
                <span className='text-[11px] text-muted'>2-20 (larger → Enterprise)</span>
              </div>
            </GlassPanel>
          )}

          {/* Row 4.5: TEAM seat management (v2.6.0) */}
          {entityType === 'ORG' && orgDto?.tier === 'TEAM' && (
            <GlassPanel className='p-4'>
              <div className='flex items-center gap-2 mb-3'>
                <UserCheck className='h-4 w-4 text-secondary' />
                <p className='text-xs text-muted font-medium uppercase tracking-wider'>
                  Team seats
                </p>
              </div>
              <div className='grid grid-cols-1 sm:grid-cols-3 gap-4 mb-4'>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>Active members</p>
                  <p className='text-xl font-semibold text-primary mt-0.5'>
                    {members.length}{' '}
                    <span className='text-sm text-muted'>of {orgDto.seatCount ?? 1} seats</span>
                  </p>
                </div>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>Credits pool</p>
                  <p className='text-xl font-semibold text-primary mt-0.5'>
                    {(orgDto.seatCount ?? 1) * 50}{' '}
                    <span className='text-sm text-muted'>credits/mo</span>
                  </p>
                </div>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>
                    Usage this period
                  </p>
                  <p className='text-xl font-semibold text-primary mt-0.5'>
                    {orgDto.monthlyRunCount}{' '}
                    <span className='text-sm text-muted'>/ {(orgDto.seatCount ?? 1) * 50}</span>
                  </p>
                </div>
              </div>
              <div className='flex items-center gap-3 flex-wrap'>
                <label className='text-xs text-muted font-mono uppercase tracking-wide'>
                  Adjust seats
                </label>
                <input
                  type='number'
                  min={2}
                  max={20}
                  value={seatsDraft}
                  onChange={(e) => {
                    const n = parseInt(e.target.value, 10);
                    if (!Number.isNaN(n)) setSeatsDraft(Math.max(2, Math.min(20, n)));
                  }}
                  className='w-20 px-2 py-1 bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm text-sm text-primary focus:outline-none focus:ring-1 focus:ring-accent-blue'
                  disabled={seatsBusy}
                />
                <button
                  onClick={handleSeatsAdjust}
                  disabled={seatsBusy || seatsDraft === (orgDto.seatCount ?? 1)}
                  className='px-3 py-1.5 bg-accent-blue text-primary text-xs font-medium rounded-sm hover:bg-accent-blue-hover disabled:opacity-50 disabled:cursor-not-allowed'
                >
                  {seatsBusy ? 'Updating…' : 'Apply'}
                </button>
                <span className='text-[11px] text-muted'>
                  2-20 (larger → Enterprise) &middot; prorated on Stripe
                </span>
              </div>
              {seatsMsg && <p className='mt-3 text-xs text-status-success'>{seatsMsg}</p>}
              {seatsError && <p className='mt-3 text-xs text-status-error'>{seatsError}</p>}
            </GlassPanel>
          )}

          {/* Row 5: Billing overview */}
          {billing && (
            <GlassPanel className='p-4'>
              <div className='flex items-center gap-2 mb-3'>
                <DollarSign className='h-4 w-4 text-secondary' />
                <p className='text-xs text-muted font-medium uppercase tracking-wider'>
                  Billing Overview
                </p>
              </div>
              <div className='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-4'>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>Plan</p>
                  <p className='text-sm font-medium text-primary mt-0.5'>
                    <span
                      className={cn(
                        'inline-block px-2 py-0.5 rounded text-[10px] font-semibold uppercase',
                        billing.currentPlan.startsWith('ENTERPRISE')
                          ? 'bg-accent-blue/20 text-accent-blue'
                          : billing.currentPlan.startsWith('PRO')
                            ? 'bg-blue-500/20 text-blue-300'
                            : 'bg-[var(--gray-400)]/50 text-secondary',
                      )}
                    >
                      {billing.currentPlan}
                    </span>
                  </p>
                </div>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>Status</p>
                  <p
                    className={cn(
                      'text-sm font-medium mt-0.5',
                      billing.subscriptionStatus === 'ACTIVE'
                        ? 'text-status-success'
                        : billing.subscriptionStatus === 'PAST_DUE'
                          ? 'text-accent-amber'
                          : 'text-secondary',
                    )}
                  >
                    {billing.subscriptionStatus}
                  </p>
                </div>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>Next Billing</p>
                  <p className='text-sm text-secondary mt-0.5'>
                    {billing.nextBillingDate
                      ? new Date(billing.nextBillingDate).toLocaleDateString()
                      : '-'}
                  </p>
                </div>
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider'>Gateway</p>
                  <p className='text-sm text-secondary mt-0.5 capitalize'>
                    {billing.gateway ?? 'None'}
                  </p>
                </div>
              </div>

              {/* Usage progress bar */}
              <div className='mb-4'>
                <div className='flex justify-between text-[10px] text-muted mb-1'>
                  <span>
                    Usage: {billing.periodUsage} / {billing.runLimit} runs
                  </span>
                  <span>
                    {billing.topupCredits > 0 ? `+${billing.topupCredits} top-up credits` : ''}
                  </span>
                </div>
                <div className='h-2 bg-[var(--gray-200)] rounded-full overflow-hidden'>
                  <div
                    className={cn(
                      'h-full rounded-full transition-all duration-500',
                      billing.periodUsage / billing.runLimit > 0.9
                        ? 'bg-rose-500'
                        : billing.periodUsage / billing.runLimit > 0.7
                          ? 'bg-status-waiting'
                          : 'bg-[#8B5CF6]',
                    )}
                    style={{
                      width: `${Math.min(100, (billing.periodUsage / billing.runLimit) * 100)}%`,
                    }}
                  />
                </div>
              </div>

              {/* Recent invoices */}
              {billing.recentInvoices.length > 0 && (
                <div>
                  <p className='text-[10px] text-muted uppercase tracking-wider mb-2'>
                    Recent Invoices
                  </p>
                  <div className='space-y-1'>
                    {billing.recentInvoices.slice(0, 5).map((inv) => (
                      <div
                        key={inv.invoiceNumber}
                        className='flex items-center justify-between py-1.5 border-b border-[var(--gray-200)] text-xs'
                      >
                        <span className='text-secondary font-mono'>{inv.invoiceNumber}</span>
                        <div className='flex items-center gap-3'>
                          <span className='text-secondary'>
                            {inv.currency === 'INR' ? '₹' : '$'}
                            {inv.amount.toFixed(2)}
                          </span>
                          <span
                            className={cn(
                              'px-1.5 py-0.5 rounded text-[10px] font-medium',
                              inv.status === 'PAID'
                                ? 'bg-status-success/10 text-status-success'
                                : inv.status === 'ISSUED'
                                  ? 'bg-blue-500/10 text-accent-blue'
                                  : 'bg-[var(--gray-400)]/50 text-secondary',
                            )}
                          >
                            {inv.status}
                          </span>
                          <span className='text-muted'>
                            {new Date(inv.issuedAt).toLocaleDateString()}
                          </span>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </GlassPanel>
          )}
        </>
      )}
    </div>
  );
}
