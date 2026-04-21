import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { TrendingUp, DollarSign, CheckCircle2, AlertTriangle, Rocket, Trophy, Clock, Lock, ArrowUpRight } from 'lucide-react';
import PageShell from '@/components/shared/PageShell';
import * as api from '@/lib/api';
import type { ProjectDto, RunSummary } from '@/lib/api';
import StatusBadge from '@/components/shared/StatusBadge';
import { relativeTime } from '@/lib/utils';
import { useTierPermissions } from '@/hooks/useTierPermissions';
import { useAuthStore } from '@/stores/useAuthStore';

/**
 * v2.5.14 Phase 3.1 — Executive summary page for Business Owner / Exec persona.
 *
 * Aggregates data already exposed by the Projects + Runs APIs (no new backend
 * endpoints). Pulls the last 100 runs + all active projects, computes:
 *   • this month's LLM spend (sum of actualCostUsd for runs in the current month)
 *   • success rate (COMPLETE / total terminal)
 *   • top 3 successful runs by spend × completion (simple proxy for "wins")
 *   • spend breakdown per project
 *   • attention list (runs that FAILED in the last 7 days)
 *
 * Design goal: a non-engineer opens this page and understands value + spend in
 * under 30 seconds. Everything else stays one click away.
 */
export default function ExecSummaryPage() {
  const [projects, setProjects] = useState<ProjectDto[]>([]);
  const [runs, setRuns] = useState<RunSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const permissions = useTierPermissions();
  const userTier = useAuthStore((s) => s.user?.tier ?? 'FREE');
  const navigate = useNavigate();
  // v2.5.14 — gated on canViewCostBreakdown which is PRO+ only. FREE users
  // see an upgrade wall instead of the aggregated spend view.
  const canView = permissions ? permissions.canViewCostBreakdown : false;

  useEffect(() => {
    if (!canView) return; // skip data fetch for locked tiers
    let cancelled = false;
    Promise.all([
      api.getProjects().catch(() => [] as ProjectDto[]),
      api.getRuns({ size: 100, scope: 'org' }).catch(() => null),
    ]).then(([projs, runPage]) => {
      if (cancelled) return;
      setProjects(projs);
      // API returns either RunSummary[] or PagedResponse<RunSummary>. Tolerate both.
      const anyPage = runPage as unknown as { content?: RunSummary[] } | RunSummary[] | null;
      const list: RunSummary[] = Array.isArray(anyPage)
        ? anyPage
        : (anyPage?.content ?? []);
      setRuns(list);
      setLoading(false);
    });
    return () => { cancelled = true; };
  }, [canView]);

  // v2.5.14 — useMemo MUST be called on every render (React Rules of Hooks).
  // Previously placed after the early return for FREE users, which flipped
  // the hook order when the tier changed and crashed the page.
  const stats = useMemo(() => computeStats(runs, projects), [runs, projects]);

  if (!canView) {
    return (
      <PageShell
        eyebrow="Executive summary"
        title="This month at a glance."
        accent="stack"
        description="Spend, velocity, and wins across every project — a non-technical view of what the platform has shipped."
        hero
      >
        <div className="rounded-md border border-accent-blue/30 bg-accent-blue/[0.05] p-6 max-w-xl">
          <div className="flex items-center gap-2 mb-2">
            <Lock className="h-4 w-4 text-accent-blue" />
            <h2 className="text-sm font-semibold text-primary">A paid-tier feature</h2>
          </div>
          <p className="text-sm text-secondary leading-relaxed mb-4">
            Executive Summary rolls up spend, success rate, and wins across every project.
            It's included on <strong className="text-primary">Pro</strong>, <strong className="text-primary">Max</strong>,
            and <strong className="text-primary">Enterprise</strong> plans.
          </p>
          <p className="text-xs text-muted mb-4">You're on the <span className="font-mono text-secondary">{userTier}</span> plan today.</p>
          <div className="flex items-center gap-2">
            <button
              onClick={() => navigate('/plans')}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-sm bg-accent-blue text-white text-sm font-medium hover:bg-accent-blue-hover transition-colors"
            >
              Upgrade to Pro
              <ArrowUpRight className="h-3.5 w-3.5" />
            </button>
            <Link to="/pricing" className="text-sm text-muted hover:text-primary transition-colors px-3 py-2">
              Compare plans
            </Link>
          </div>
        </div>
      </PageShell>
    );
  }

  return (
    <PageShell
      eyebrow="Executive summary"
      title="This month at a glance."
      accent="stack"
      description="Spend, velocity, and wins across every project — a non-technical view of what the platform has shipped."
      hero
    >
      {loading ? (
        <div className="text-xs text-muted">Loading…</div>
      ) : (
        <div className="space-y-6">

          {/* Top-line KPIs */}
          <section className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
            <KpiCard label="Spend (MTD)"    value={`$${stats.mtdSpend.toFixed(2)}`} icon={<DollarSign className="h-3.5 w-3.5" />} accent="text-accent-blue" />
            <KpiCard label="Runs completed" value={String(stats.completedRuns)}     icon={<CheckCircle2 className="h-3.5 w-3.5" />} accent="text-status-success" />
            <KpiCard label="Success rate"   value={`${stats.successRate}%`}           icon={<TrendingUp className="h-3.5 w-3.5" />} accent={stats.successRate >= 80 ? 'text-status-success' : stats.successRate >= 60 ? 'text-status-waiting' : 'text-status-error'} />
            <KpiCard label="Active projects" value={String(projects.filter((p) => p.status === 'ACTIVE').length)} icon={<Rocket className="h-3.5 w-3.5" />} />
            <KpiCard label="Needs attention" value={String(stats.attentionCount)}     icon={<AlertTriangle className="h-3.5 w-3.5" />} accent={stats.attentionCount > 0 ? 'text-status-error' : 'text-muted'} />
          </section>

          {/* Spend by project */}
          <section>
            <h2 className="text-xs font-semibold uppercase tracking-wider text-muted mb-2">Spend by project (MTD)</h2>
            {stats.perProjectSpend.length === 0 ? (
              <p className="text-xs text-muted italic">No spend this month yet.</p>
            ) : (
              <div className="rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] divide-y divide-[var(--border-default)]">
                {stats.perProjectSpend.slice(0, 8).map((row) => (
                  <Link
                    key={row.projectId}
                    to={`/projects/${row.projectId}/dashboard`}
                    className="flex items-center justify-between gap-3 px-3 py-2.5 hover:bg-[var(--gray-200)] transition-colors"
                  >
                    <div className="flex-1 min-w-0 flex items-center gap-2">
                      <div className="h-2 w-2 rounded-full bg-accent-blue flex-shrink-0" />
                      <span className="text-sm text-primary truncate">{row.projectName}</span>
                      <span className="text-[11px] text-muted flex-shrink-0">
                        {row.runCount} run{row.runCount === 1 ? '' : 's'}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 flex-shrink-0">
                      <div className="w-24 h-1.5 rounded-full bg-[var(--gray-300)] overflow-hidden">
                        <div
                          className="h-full rounded-full bg-gradient-to-r from-accent-blue to-accent-planner"
                          style={{ width: `${Math.max(4, Math.min(100, (row.spend / stats.maxProjectSpend) * 100))}%` }}
                        />
                      </div>
                      <span className="text-sm text-secondary tabular-nums w-16 text-right">${row.spend.toFixed(2)}</span>
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </section>

          {/* v2.5.14 Phase 3.2 — Program-Manager digest: every run currently
              awaiting approval across all projects. This was previously only
              discoverable by filtering the Runs list manually. */}
          {stats.awaitingApproval.length > 0 && (
            <section className="rounded-md border border-status-waiting/40 bg-status-waiting/[0.04] p-3">
              <h2 className="text-xs font-semibold uppercase tracking-wider text-status-waiting mb-2 flex items-center gap-1.5">
                <Clock className="h-3 w-3" />
                {stats.awaitingApproval.length} awaiting approval
              </h2>
              <ul className="space-y-1">
                {stats.awaitingApproval.map((r) => (
                  <li key={r.runId}>
                    <Link
                      to={`/?runId=${r.runId}`}
                      className="flex items-center justify-between gap-2 px-2 py-1.5 rounded-sm hover:bg-status-waiting/[0.08] transition-colors"
                    >
                      <span className="text-sm text-primary line-clamp-1">
                        {r.runTitle ?? r.userRequest?.slice(0, 60) ?? r.runId.slice(0, 8)}
                      </span>
                      <span className="text-[11px] text-muted flex-shrink-0">
                        {r.projectName ?? 'Personal'} · {relativeTime(r.startedAt)}
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}

          {/* Wins + Attention side-by-side on desktop */}
          <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <div>
              <h2 className="text-xs font-semibold uppercase tracking-wider text-muted mb-2 flex items-center gap-1.5">
                <Trophy className="h-3 w-3 text-accent-planner" /> Top 3 wins
              </h2>
              {stats.topWins.length === 0 ? (
                <p className="text-xs text-muted italic">No completed runs yet this period.</p>
              ) : (
                <ul className="space-y-1.5">
                  {stats.topWins.map((r) => (
                    <li key={r.runId}>
                      <Link
                        to={`/?runId=${r.runId}`}
                        className="block rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] hover:bg-[var(--gray-200)] px-3 py-2 transition-colors"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <span className="text-sm text-primary line-clamp-1">{r.runTitle ?? r.userRequest?.slice(0, 60) ?? r.runId.slice(0, 8)}</span>
                          <span className="text-[11px] text-muted tabular-nums flex-shrink-0">${(r.actualCostUsd ?? 0).toFixed(2)}</span>
                        </div>
                        <div className="flex items-center gap-2 mt-0.5">
                          <StatusBadge status={r.status} />
                          <span className="text-[11px] text-muted">{r.projectName ?? 'Personal'}</span>
                          <span className="text-[11px] text-muted">· {relativeTime(r.startedAt)}</span>
                        </div>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h2 className="text-xs font-semibold uppercase tracking-wider text-muted mb-2 flex items-center gap-1.5">
                <AlertTriangle className="h-3 w-3 text-status-error" /> Needs attention
              </h2>
              {stats.attention.length === 0 ? (
                <p className="text-xs text-status-success italic">All green in the last 7 days.</p>
              ) : (
                <ul className="space-y-1.5">
                  {stats.attention.slice(0, 5).map((r) => (
                    <li key={r.runId}>
                      <Link
                        to={`/?runId=${r.runId}`}
                        className="block rounded-md border border-status-error/30 bg-status-error/[0.04] hover:bg-status-error/[0.08] px-3 py-2 transition-colors"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <span className="text-sm text-primary line-clamp-1">{r.runTitle ?? r.userRequest?.slice(0, 60) ?? r.runId.slice(0, 8)}</span>
                          <StatusBadge status={r.status} />
                        </div>
                        <div className="flex items-center gap-2 mt-0.5">
                          <span className="text-[11px] text-muted">{r.projectName ?? 'Personal'}</span>
                          <span className="text-[11px] text-muted">· {relativeTime(r.startedAt)}</span>
                        </div>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </section>

        </div>
      )}
    </PageShell>
  );
}

function KpiCard({ label, value, icon, accent }: { label: string; value: string; icon: React.ReactNode; accent?: string }) {
  return (
    <div className="rounded-md border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2.5">
      <div className="flex items-center gap-1.5 text-[11px] font-medium text-muted uppercase tracking-wide">
        <span className={accent ?? 'text-muted'}>{icon}</span>
        {label}
      </div>
      <div className={`text-xl font-semibold tabular-nums mt-1 ${accent ?? 'text-primary'}`}>{value}</div>
    </div>
  );
}

function computeStats(runs: RunSummary[], projects: ProjectDto[]) {
  const now = new Date();
  const monthStart = new Date(now.getFullYear(), now.getMonth(), 1);
  const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

  const mtdRuns = runs.filter((r) => new Date(r.startedAt) >= monthStart);
  const mtdSpend = mtdRuns.reduce((acc, r) => acc + (r.actualCostUsd ?? 0), 0);

  const terminal = runs.filter((r) => r.status === 'COMPLETE' || r.status === 'FAILED' || r.status === 'PARTIAL');
  const completed = terminal.filter((r) => r.status === 'COMPLETE');
  const successRate = terminal.length === 0 ? 0 : Math.round((completed.length / terminal.length) * 100);

  // Spend per project (MTD).
  const spendByProject = new Map<string, { projectId: string; projectName: string; spend: number; runCount: number }>();
  for (const r of mtdRuns) {
    if (!r.projectId) continue;
    const cur = spendByProject.get(r.projectId) ?? {
      projectId: r.projectId,
      projectName: r.projectName ?? projects.find((p) => p.id === r.projectId)?.name ?? 'Unknown project',
      spend: 0,
      runCount: 0,
    };
    cur.spend += r.actualCostUsd ?? 0;
    cur.runCount += 1;
    spendByProject.set(r.projectId, cur);
  }
  const perProjectSpend = Array.from(spendByProject.values()).sort((a, b) => b.spend - a.spend);
  const maxProjectSpend = perProjectSpend[0]?.spend || 1;

  // Top 3 wins = completed runs ranked by cost (proxy: higher-cost success = more work shipped).
  const topWins = completed
    .slice()
    .sort((a, b) => (b.actualCostUsd ?? 0) - (a.actualCostUsd ?? 0))
    .slice(0, 3);

  // Needs attention = failed runs in last 7 days.
  const attention = runs.filter((r) => r.status === 'FAILED' && new Date(r.startedAt) >= weekAgo);

  // v2.5.14 Phase 3.2 — every run waiting on a human across all projects.
  const awaitingApproval = runs
    .filter((r) => r.status === 'AWAITING_APPROVAL' || r.status === 'AWAITING_CLARIFICATION' || r.status === 'ARCH_REVIEW')
    .sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime());

  return {
    mtdSpend,
    completedRuns: completed.length,
    successRate,
    attentionCount: attention.length,
    perProjectSpend,
    maxProjectSpend,
    topWins,
    attention,
    awaitingApproval,
  };
}
