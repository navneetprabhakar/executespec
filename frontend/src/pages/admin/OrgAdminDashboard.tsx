import { useState, useEffect, useCallback } from 'react';
import { Navigate } from 'react-router-dom';
import {
  LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  BarChart, Bar, PieChart, Pie, Cell, Legend
} from 'recharts';
import { Activity, TrendingUp, Clock, DollarSign, Users } from 'lucide-react';
import GlassPanel from '@/components/shared/GlassPanel';
import MetricCard from '@/components/dashboard/MetricCard';
import { useAuthStore, selectIsOrgAdmin, selectIsSuperAdmin } from '@/stores/useAuthStore';
import {
  getAnalyticsUsage, getAnalyticsMembers, getAnalyticsBilling,
  getMyOrg,
  type UsageMetrics, type MemberUsage, type BillingOverviewData
} from '@/lib/api';
import { cn } from '@/lib/utils';

const PERIODS = [
  { label: '7d', value: 7 },
  { label: '30d', value: 30 },
  { label: '90d', value: 90 },
];

const CHART_COLORS = ['#8B5CF6', '#3B82F6', '#10B981', '#F59E0B', '#EF4444', '#EC4899', '#6366F1', '#14B8A6'];

export default function OrgAdminDashboard() {
  const user = useAuthStore((s) => s.user);
  const isAdmin = useAuthStore(selectIsOrgAdmin);
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);

  const [period, setPeriod] = useState(30);
  const [usage, setUsage] = useState<UsageMetrics | null>(null);
  const [members, setMembers] = useState<MemberUsage[]>([]);
  const [billing, setBilling] = useState<BillingOverviewData | null>(null);
  // V141 — orgDto + seat-adjust state removed along with the seat panel.
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
          await getMyOrg();
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

  // V141 — handleSeatsAdjust removed along with the seat panel.

  useEffect(() => { fetchData(); }, [fetchData]);

  // Non-admin standalone users see their own analytics
  if (!isAdmin && !user?.id) {
    return <Navigate to="/" replace />;
  }

  const successRate = usage && usage.totalRuns > 0
    ? Math.round((usage.successfulRuns / usage.totalRuns) * 100)
    : 0;

  const avgTimeMins = usage ? Math.round(usage.avgTimeToCompleteSeconds / 60) : 0;

  const sparkData = usage?.runsByDay.map(d => ({ v: d.count })) ?? [];

  // Sort members
  const sortedMembers = [...members].sort((a, b) => {
    const aVal = a[sortCol] ?? 0;
    const bVal = b[sortCol] ?? 0;
    if (typeof aVal === 'string' && typeof bVal === 'string') {
      return sortDir === 'asc' ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
    }
    return sortDir === 'asc' ? (aVal as number) - (bVal as number) : (bVal as number) - (aVal as number);
  });

  const toggleSort = (col: typeof sortCol) => {
    if (sortCol === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    else { setSortCol(col); setSortDir('desc'); }
  };

  // Cost by model for donut chart
  const costData = usage
    ? Object.entries(usage.costByModel).map(([model, cost]) => ({ name: model, value: Math.round(cost * 100) / 100 }))
    : [];

  // Runs by project for bar chart
  const projectData = usage
    ? Object.entries(usage.runsByProject).slice(0, 10).map(([name, count]) => ({ name: name.length > 20 ? name.slice(0, 20) + '...' : name, count }))
    : [];

  return (
    <div className="h-full overflow-y-auto p-4 space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Activity className="h-5 w-5 text-secondary" />
          <h2 className="text-lg font-semibold text-primary">Analytics</h2>
        </div>

        {/* Period selector */}
        <div className="flex rounded-md border border-[var(--border-default)] overflow-hidden">
          {PERIODS.map(p => (
            <button
              key={p.value}
              onClick={() => setPeriod(p.value)}
              className={cn(
                'px-3 py-1.5 text-xs font-medium transition-colors',
                period === p.value
                  ? 'bg-[#8B5CF6]/20 text-[#8B5CF6]'
                  : 'text-muted hover:text-secondary hover:bg-[var(--gray-200)]'
              )}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {loading && !usage ? (
        <div className="flex items-center justify-center py-20">
          <div className="animate-spin h-6 w-6 border-2 border-[var(--gray-400)] border-t-[#8B5CF6] rounded-full" />
        </div>
      ) : (
        <>
          {/* Row 1: Stat cards */}
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
            <MetricCard
              label="Total Runs"
              value={usage?.totalRuns ?? 0}
              sparkData={sparkData}
            />
            <MetricCard
              label="Success Rate"
              value={successRate}
              format={(n) => `${n}%`}
              accent="#10B981"
            />
            <MetricCard
              label="Avg Time"
              value={avgTimeMins}
              format={(n) => `${n}m`}
              accent="#3B82F6"
            />
            <MetricCard
              label="Total Cost"
              value={Math.round((usage?.totalCost ?? 0) * 100) / 100}
              format={(n) => `$${n.toFixed(2)}`}
              accent="#F59E0B"
            />
          </div>

          {/* Row 2: Runs per day line chart */}
          <GlassPanel className="p-4">
            <p className="text-xs text-muted font-medium uppercase tracking-wider mb-3">Runs per Day</p>
            {usage && usage.runsByDay.length > 0 ? (
              <div className="h-52">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={usage.runsByDay} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                    <XAxis dataKey="date" tick={{ fontSize: 10, fill: '#71717a' }} tickFormatter={d => d.slice(5)} />
                    <YAxis tick={{ fontSize: 10, fill: '#71717a' }} allowDecimals={false} />
                    <Tooltip
                      contentStyle={{ background: '#18181b', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 4, fontSize: 12 }}
                      labelStyle={{ color: '#a1a1aa' }}
                    />
                    <Line type="monotone" dataKey="count" stroke="#8B5CF6" strokeWidth={2} dot={false} name="Runs" />
                    <Line type="monotone" dataKey="successRate" stroke="#10B981" strokeWidth={1.5} dot={false} name="Success %" />
                  </LineChart>
                </ResponsiveContainer>
              </div>
            ) : (
              <p className="text-xs text-muted py-10 text-center">No run data for this period</p>
            )}
          </GlassPanel>

          {/* Row 3: Runs by Project + Cost by Model */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
            <GlassPanel className="p-4">
              <p className="text-xs text-muted font-medium uppercase tracking-wider mb-3">Runs by Project</p>
              {projectData.length > 0 ? (
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={projectData} layout="vertical" margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                      <XAxis type="number" tick={{ fontSize: 10, fill: '#71717a' }} allowDecimals={false} />
                      <YAxis type="category" dataKey="name" tick={{ fontSize: 10, fill: '#71717a' }} width={120} />
                      <Tooltip
                        contentStyle={{ background: '#18181b', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 4, fontSize: 12 }}
                      />
                      <Bar dataKey="count" fill="#8B5CF6" radius={[0, 4, 4, 0]} />
                    </BarChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p className="text-xs text-muted py-10 text-center">No project data</p>
              )}
            </GlassPanel>

            <GlassPanel className="p-4">
              <p className="text-xs text-muted font-medium uppercase tracking-wider mb-3">Cost by Model</p>
              {costData.length > 0 ? (
                <div className="h-52">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie
                        data={costData}
                        cx="50%" cy="50%"
                        innerRadius={50} outerRadius={80}
                        paddingAngle={2}
                        dataKey="value"
                        label={({ name, value }) => `${name.split('/').pop()}: $${value}`}
                        labelLine={false}
                      >
                        {costData.map((_, i) => (
                          <Cell key={i} fill={CHART_COLORS[i % CHART_COLORS.length]} />
                        ))}
                      </Pie>
                      <Tooltip
                        contentStyle={{ background: '#18181b', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 4, fontSize: 12 }}
                        formatter={(v: number) => [`$${v.toFixed(2)}`, 'Cost']}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p className="text-xs text-muted py-10 text-center">No cost data</p>
              )}
            </GlassPanel>
          </div>

          {/* Row 4: Member usage table (ORG only) */}
          {entityType === 'ORG' && (
            <GlassPanel className="p-4">
              <div className="flex items-center gap-2 mb-3">
                <Users className="h-4 w-4 text-secondary" />
                <p className="text-xs text-muted font-medium uppercase tracking-wider">Member Activity</p>
              </div>
              {sortedMembers.length > 0 ? (
                <div className="overflow-x-auto">
                  <table className="w-full text-xs min-w-[560px] md:min-w-0">
                    <thead>
                      <tr className="border-b border-[var(--border-default)]">
                        <th className="text-left py-2 px-2 text-muted font-medium">Name</th>
                        <th className="text-left py-2 px-2 text-muted font-medium">Email</th>
                        <th
                          className="text-right py-2 px-2 text-muted font-medium cursor-pointer hover:text-secondary"
                          onClick={() => toggleSort('runCount')}
                        >
                          Runs {sortCol === 'runCount' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th
                          className="text-right py-2 px-2 text-muted font-medium cursor-pointer hover:text-secondary"
                          onClick={() => toggleSort('successRate')}
                        >
                          Success % {sortCol === 'successRate' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                        <th
                          className="text-right py-2 px-2 text-muted font-medium cursor-pointer hover:text-secondary"
                          onClick={() => toggleSort('lastRunDate')}
                        >
                          Last Active {sortCol === 'lastRunDate' ? (sortDir === 'asc' ? '↑' : '↓') : ''}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {sortedMembers.map(m => (
                        <tr key={m.userId} className="border-b border-[var(--gray-200)] hover:bg-[var(--gray-100)] transition-colors">
                          <td className="py-2 px-2 text-primary">{m.firstName} {m.lastName}</td>
                          <td className="py-2 px-2 text-secondary">{m.email}</td>
                          <td className="py-2 px-2 text-right text-primary font-mono">{m.runCount}</td>
                          <td className="py-2 px-2 text-right">
                            <span className={cn(
                              'font-mono',
                              m.successRate >= 80 ? 'text-status-success' :
                              m.successRate >= 50 ? 'text-accent-amber' : 'text-rose-400'
                            )}>
                              {m.successRate}%
                            </span>
                          </td>
                          <td className="py-2 px-2 text-right text-muted">
                            {m.lastRunDate ? new Date(m.lastRunDate).toLocaleDateString() : '-'}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <p className="text-xs text-muted py-6 text-center">No member activity in this period</p>
              )}
            </GlassPanel>
          )}

          {/* V141 (2026-04-26) — TEAM seat management panel removed.
              The v2.6.1-Wave-1 per-seat TEAM tier was deleted; the new
              "TEAM" name refers to the renamed MAX tier (BYOK unlimited,
              single-seat). Seat adjustment endpoint returns 410 Gone. */}

          {/* Row 5: Billing overview */}
          {billing && (
            <GlassPanel className="p-4">
              <div className="flex items-center gap-2 mb-3">
                <DollarSign className="h-4 w-4 text-secondary" />
                <p className="text-xs text-muted font-medium uppercase tracking-wider">Billing Overview</p>
              </div>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-4">
                <div>
                  <p className="text-[10px] text-muted uppercase tracking-wider">Plan</p>
                  <p className="text-sm font-medium text-primary mt-0.5">
                    <span className={cn(
                      'inline-block px-2 py-0.5 rounded text-[10px] font-semibold uppercase',
                      billing.currentPlan.startsWith('ENTERPRISE') ? 'bg-accent-blue/20 text-accent-blue' :
                      billing.currentPlan.startsWith('PRO') ? 'bg-blue-500/20 text-blue-300' :
                      'bg-[var(--gray-400)]/50 text-secondary'
                    )}>
                      {billing.currentPlan}
                    </span>
                  </p>
                </div>
                <div>
                  <p className="text-[10px] text-muted uppercase tracking-wider">Status</p>
                  <p className={cn(
                    'text-sm font-medium mt-0.5',
                    billing.subscriptionStatus === 'ACTIVE' ? 'text-status-success' :
                    billing.subscriptionStatus === 'PAST_DUE' ? 'text-accent-amber' : 'text-secondary'
                  )}>
                    {billing.subscriptionStatus}
                  </p>
                </div>
                <div>
                  <p className="text-[10px] text-muted uppercase tracking-wider">Next Billing</p>
                  <p className="text-sm text-secondary mt-0.5">
                    {billing.nextBillingDate ? new Date(billing.nextBillingDate).toLocaleDateString() : '-'}
                  </p>
                </div>
                <div>
                  <p className="text-[10px] text-muted uppercase tracking-wider">Gateway</p>
                  <p className="text-sm text-secondary mt-0.5 capitalize">{billing.gateway ?? 'None'}</p>
                </div>
              </div>

              {/* Usage progress bar */}
              <div className="mb-4">
                <div className="flex justify-between text-[10px] text-muted mb-1">
                  <span>Usage: {billing.periodUsage} / {billing.runLimit} runs</span>
                  <span>{billing.topupCredits > 0 ? `+${billing.topupCredits} top-up credits` : ''}</span>
                </div>
                <div className="h-2 bg-[var(--gray-200)] rounded-full overflow-hidden">
                  <div
                    className={cn(
                      'h-full rounded-full transition-all duration-500',
                      billing.periodUsage / billing.runLimit > 0.9 ? 'bg-rose-500' :
                      billing.periodUsage / billing.runLimit > 0.7 ? 'bg-status-waiting' : 'bg-[#8B5CF6]'
                    )}
                    style={{ width: `${Math.min(100, (billing.periodUsage / billing.runLimit) * 100)}%` }}
                  />
                </div>
              </div>

              {/* Recent invoices */}
              {billing.recentInvoices.length > 0 && (
                <div>
                  <p className="text-[10px] text-muted uppercase tracking-wider mb-2">Recent Invoices</p>
                  <div className="space-y-1">
                    {billing.recentInvoices.slice(0, 5).map(inv => (
                      <div key={inv.invoiceNumber} className="flex items-center justify-between py-1.5 border-b border-[var(--gray-200)] text-xs">
                        <span className="text-secondary font-mono">{inv.invoiceNumber}</span>
                        <div className="flex items-center gap-3">
                          <span className="text-secondary">
                            {inv.currency === 'INR' ? '₹' : '$'}{inv.amount.toFixed(2)}
                          </span>
                          <span className={cn(
                            'px-1.5 py-0.5 rounded text-[10px] font-medium',
                            inv.status === 'PAID' ? 'bg-status-success/10 text-status-success' :
                            inv.status === 'ISSUED' ? 'bg-blue-500/10 text-accent-blue' :
                            'bg-[var(--gray-400)]/50 text-secondary'
                          )}>
                            {inv.status}
                          </span>
                          <span className="text-muted">{new Date(inv.issuedAt).toLocaleDateString()}</span>
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
