/**
 * PulseTab — replaces the old Overview pane after the v2.6.4 IA cleanup.
 *
 * Per the UX & Wireframe Report (2026-04-26 §5.2 + §5.3), the project
 * dashboard's first paint had 25 affordances and no primary action. This
 * tab consolidates what BOTH personas (Senior PM + Lead Engineer) actually
 * scan for on every visit:
 *
 *   ┌──────────────────────────────────────────────┐
 *   │  Last run hero (left)   ·  Verified score (right)
 *   │  ─────────────────────────────────────────────
 *   │  Health & debt summary  ·  Activity preview
 *   └──────────────────────────────────────────────┘
 *
 * Defers everything else (Generated Docs, Project Memory, full Activity
 * feed, Workspace Health detail, Standards table, Tech Debt drilldown)
 * into the More drawer or the dedicated Code tab.
 *
 * UX-preservation contract: reuses existing primitives + 4-band semantic
 * tokens. No new colors, no new fonts, no new component primitives.
 */
import {Link} from 'react-router-dom';
import {ArrowRight, Activity, Heart, DollarSign} from 'lucide-react';
import ScoreRing from '@/components/runs/ScoreRing';
import StatusBadge from '@/components/shared/StatusBadge';
import EmptyState from '@/components/shared/EmptyState';
import {Rocket, FolderGit2, Plus} from 'lucide-react';
import {cn} from '@/lib/utils';
import {useEffect, useState} from 'react';
import * as api from '@/lib/api';
import {useProjectStore} from '@/stores/useProjectStore';
import type {TaskSummary, PrSummary} from '@/types/project';
import type {ActivityEntry} from '@/lib/api';

interface PulseTabProps {
  projectId: string;
  recentTasks: TaskSummary[];
  recentPrs: PrSummary[];
  onOpenRun: (runId: string) => void;
  onSwitchTab: (tab: 'runs' | 'code') => void;
  /**
   * v2.7.2 — present only when the project has 0 linked repos. When set,
   * Pulse renders an inline "Add repository" banner above the hero row
   * so users with runs but no repos (e.g. all repos were unlinked) don't
   * have to discover the new top-level Repos tab to fix it.
   */
  onLinkRepo?: () => void;
}

export default function PulseTab({
  projectId,
  recentTasks,
  recentPrs,
  onOpenRun,
  onSwitchTab,
  onLinkRepo,
}: PulseTabProps) {
  const lastRun = recentTasks[0] ?? null;
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [activityLoading, setActivityLoading] = useState(false);

  // Pull the validation report for the last run so we can show the
  // verified score + signal breakdown without making the user click in.
  useEffect(() => {
    setReport(null);
    if (!lastRun) return;
    let cancelled = false;
    api
      .getValidationReport(lastRun.runId)
      .then((r) => {
        if (!cancelled) setReport(r);
      })
      .catch(() => {
        if (!cancelled) setReport(null);
      });
    return () => {
      cancelled = true;
    };
  }, [lastRun?.runId]);

  // Last 5 activity events (preview only — full feed in More drawer).
  useEffect(() => {
    setActivityLoading(true);
    api
      .getActivityFeed(projectId, {sinceDays: 30, limit: 5})
      .then((p) => setActivity(p.entries))
      .catch(() => setActivity([]))
      .finally(() => setActivityLoading(false));
  }, [projectId]);

  if (!lastRun) {
    return (
      <div className='space-y-4 w-full'>
        <EmptyState
          icon={Rocket}
          title='No runs yet'
          description='Click "Launch" up top to kick off your first execution. Pulse will populate with your last run, score signals, health rollup, and recent activity once you have one.'
        />
      </div>
    );
  }

  const verified = report?.verifiedScore ?? null;
  const accuracy = report?.accuracyScore ?? lastRun.score ?? null;
  const heroScore = verified ?? accuracy;

  return (
    <div className='space-y-3 w-full'>
      {/* v2.7.2 — inline "no repos linked" banner. Renders only when the
          parent passes onLinkRepo (which is only when repoCount === 0).
          Click pops AddRepoModal directly via the shared openAddRepoFlow
          plumbing in ProjectDashboardPage. */}
      {onLinkRepo && (
        <div className='rounded-md border border-status-waiting/40 bg-status-waiting/10 p-3 flex items-center gap-3'>
          <FolderGit2 className='h-4 w-4 text-status-waiting flex-shrink-0' />
          <div className='flex-1 min-w-0'>
            <div className='text-sm font-medium text-primary'>No repositories linked</div>
            <div className='text-[11px] text-muted'>
              You have runs but no repos. Launches will be blocked until you link at least one.
            </div>
          </div>
          <button
            onClick={onLinkRepo}
            className='inline-flex items-center gap-1 text-xs font-medium px-2.5 py-1.5 rounded-sm border border-status-waiting/40 bg-status-waiting/10 text-status-waiting hover:bg-status-waiting/20'
          >
            <Plus className='h-3.5 w-3.5' />
            Add repository
          </button>
        </div>
      )}

      {/* v2.6.4 user-feedback fix #1 — Pulse uses the full pane (was
          max-w-3xl ≈ 768px, looked tiny on wide monitors). 12-col grid
          gives the hero row LastRun cols 1-8 + ScoreRing cols 9-12 on
          md+ (tablet 768px and wider); collapses to a single column on
          phones (<768px) so the ScoreRing isn't squeezed. */}
      <div className='grid grid-cols-1 md:grid-cols-12 gap-3'>
        <div className='md:col-span-8 min-w-0'>
          <LastRunCard task={lastRun} report={report} onOpenRun={() => onOpenRun(lastRun.runId)} />
        </div>
        <div className='md:col-span-4'>
          <ScoreCardCompact score={heroScore} report={report} />
        </div>
      </div>

      {/* X-04 (UX audit, 2026-05-03) — project-scope spend chip. Pre-fix
          enterprise admins asking "how much has this project cost?" had
          to leave the project: SummaryBar only showed totalCost when > 0,
          per-run cost was buried under RunDetailTabs Overview, and org-
          wide spend at /exec is gated behind canViewCostBreakdown. The
          data is already on the dashboard endpoint; surfacing it here
          puts the answer one row from the score ring. Hides on cold
          projects (totalCostUsd null/0) so the empty-project hero
          doesn't get cluttered. */}
      <ProjectSpendBar />

      {/* Health & activity row — full-width 2-col split on md+ */}
      <div className='grid grid-cols-1 md:grid-cols-2 gap-3'>
        <HealthCard projectId={projectId} onOpenCode={() => onSwitchTab('code')} />
        <ActivityCard
          entries={activity}
          loading={activityLoading}
          onShowAll={() => {
            // Deep-link to the More drawer's full activity surface — wired
            // via a query param the parent page reads.
            const url = new URL(window.location.href);
            url.searchParams.set('drawer', 'activity');
            window.history.replaceState(null, '', url.toString());
            window.dispatchEvent(new Event('drawer-state'));
          }}
        />
      </div>

      {/* Recent PRs — compact list (full list under Runs tab → PRs sub) */}
      {recentPrs.length > 0 && (
        <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3'>
          <div className='flex items-center justify-between mb-2'>
            <h3 className='text-xs font-medium text-secondary'>Recent pull requests</h3>
            <button
              onClick={() => onSwitchTab('runs')}
              className='text-[11px] text-accent-blue hover:underline inline-flex items-center gap-0.5'
            >
              View all <ArrowRight className='h-3 w-3' />
            </button>
          </div>
          <ul className='space-y-1'>
            {recentPrs.slice(0, 3).map((pr) => (
              <li
                key={`${pr.repoFullName}-${pr.prNumber}`}
                className='flex items-center gap-2 text-[11px]'
              >
                <StatusBadge status={pr.status} />
                <a
                  href={pr.prUrl}
                  target='_blank'
                  rel='noreferrer'
                  className='text-primary hover:text-accent-blue truncate flex-1'
                >
                  {pr.repoFullName} {pr.headBranch ? `· ${pr.headBranch}` : ''}
                </a>
                <span className='text-muted font-mono'>#{pr.prNumber}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

/**
 * X-04 (UX audit, 2026-05-03) — project-scope spend chip rendered between the
 * hero row and the health/activity row. Reads totalCostUsd + totalTokens off
 * useProjectStore.dashboard. Hides on cold projects (totalCostUsd null/0)
 * so empty-project Pulse stays uncluttered. Tokens formatted compactly
 * (M / k suffix) so the line stays one row at any project size.
 */
function ProjectSpendBar() {
  const dashboard = useProjectStore((s) => s.dashboard);
  const total = dashboard?.totalCostUsd ?? 0;
  if (total <= 0) return null;
  const tokens = dashboard?.totalTokens ?? 0;
  const formatTokens = (n: number) => {
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
    if (n >= 1_000) return `${(n / 1_000).toFixed(0)}k`;
    return String(n);
  };
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 flex items-center gap-3'>
      <DollarSign className='h-3.5 w-3.5 text-accent-blue flex-shrink-0' />
      <span className='text-xs text-secondary'>Project spend so far</span>
      <span className='text-xs font-mono font-medium text-primary tabular-nums'>
        ${total.toFixed(2)}
      </span>
      {tokens > 0 && (
        <>
          <span className='text-muted'>·</span>
          <span className='text-[11px] text-muted tabular-nums'>{formatTokens(tokens)} tokens</span>
        </>
      )}
    </div>
  );
}

function LastRunCard({
  task,
  report,
  onOpenRun,
}: {
  task: TaskSummary;
  report: api.ValidationReportData | null;
  onOpenRun: () => void;
}) {
  const buildOk = report?.finalBuildPassed === true;
  const buildBad = report?.finalBuildPassed === false;
  const tests =
    report?.testsPassed != null && report?.testsTotal != null
      ? `${report.testsPassed}/${report.testsTotal}`
      : null;
  const reviewer = report?.reviewerVerdict ?? null;

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2'>
      <div className='flex items-center gap-2'>
        <span className='text-[10px] font-mono uppercase tracking-wider text-muted'>Last run</span>
        <StatusBadge status={task.status} />
      </div>
      <div className='font-semibold text-primary text-sm leading-tight truncate'>
        {task.name || task.runId.slice(0, 8)}
      </div>
      <div className='text-[11px] text-muted font-mono'>
        {task.runId.slice(0, 8)} · {task.createdAt ? timeAgo(task.createdAt) : '—'}
      </div>

      {/* Signal chips — only show when we have data */}
      {(buildOk || buildBad || tests || reviewer) && (
        <div className='flex flex-wrap gap-1 pt-1'>
          {buildOk && <Chip color='success'>build ✓</Chip>}
          {buildBad && <Chip color='error'>build ✗</Chip>}
          {tests && (
            <Chip color={report?.testsPassed === report?.testsTotal ? 'success' : 'waiting'}>
              tests {tests}
            </Chip>
          )}
          {reviewer && (
            <Chip
              color={
                reviewer === 'CLEAN' ? 'success' : reviewer === 'FIXED_ISSUES' ? 'waiting' : 'error'
              }
            >
              reviewer {reviewer.toLowerCase().replace('_', ' ')}
            </Chip>
          )}
        </div>
      )}

      <div className='flex gap-2 pt-1'>
        <button
          onClick={onOpenRun}
          className='text-[11px] font-medium px-2 py-0.5 rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue hover:bg-accent-blue/20'
        >
          View report
        </button>
        <Link
          to={`/runs/${task.runId}`}
          className='text-[11px] font-medium px-2 py-0.5 rounded-sm border border-[var(--border-default)] text-secondary hover:text-primary'
        >
          Open run
        </Link>
      </div>
    </div>
  );
}

function ScoreCardCompact({
  score,
  report,
}: {
  score: number | null;
  report: api.ValidationReportData | null;
}) {
  if (score == null) {
    return (
      <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 flex flex-col items-center justify-center min-w-[180px]'>
        <div className='text-[11px] text-muted'>No score yet</div>
        <div className='text-[10px] text-muted mt-1'>Awaiting validation report</div>
      </div>
    );
  }
  const isVerified = report?.verifiedScore != null;
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 flex flex-col items-center justify-center min-w-[180px]'>
      <ScoreRing score={score} label={isVerified ? 'verified / 100' : 'est / 100'} size={120} />
      {isVerified &&
        report?.accuracyScore != null &&
        report.accuracyScore !== report.verifiedScore && (
          <div className='text-[10px] text-muted mt-1.5 font-mono'>
            est. {Math.round(report.accuracyScore)}
          </div>
        )}
    </div>
  );
}

function HealthCard({projectId, onOpenCode}: {projectId: string; onOpenCode: () => void}) {
  const [health, setHealth] = useState<api.WorkspaceHealthReport | null>(null);
  useEffect(() => {
    api
      .getWorkspaceHealth(projectId)
      .then(setHealth)
      .catch(() => setHealth(null));
  }, [projectId]);

  const stale = health?.repos.filter((r) => r.isStale).length ?? 0;
  const totalRepos = health?.repos.length ?? 0;
  const openPrs = health?.repos.reduce((sum, r) => sum + (r.openPrCount ?? 0), 0) ?? 0;
  const lastAnalysed = health?.lastRefreshedAt ?? null;

  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2'>
      <div className='flex items-center gap-2'>
        <Heart className='h-3.5 w-3.5 text-status-success' />
        <h3 className='text-xs font-medium text-secondary'>Health & debt</h3>
      </div>
      {!health ? (
        <div className='text-[11px] text-muted'>Loading…</div>
      ) : totalRepos === 0 ? (
        <div className='text-[11px] text-muted'>No repos linked yet.</div>
      ) : (
        <ul className='text-[11px] space-y-1'>
          <li className='text-secondary'>
            {totalRepos} repo{totalRepos === 1 ? '' : 's'} linked
          </li>
          <li className={cn(stale > 0 ? 'text-status-waiting' : 'text-status-success')}>
            {stale === 0 ? 'codemaps current' : `${stale} stale codemap${stale === 1 ? '' : 's'}`}
          </li>
          <li className='text-secondary'>
            {openPrs} open PR{openPrs === 1 ? '' : 's'}
          </li>
          {lastAnalysed && (
            <li className='text-muted text-[10px] font-mono'>
              last analysed {timeAgo(lastAnalysed)}
            </li>
          )}
        </ul>
      )}
      <button
        onClick={onOpenCode}
        className='text-[11px] text-accent-blue hover:underline inline-flex items-center gap-0.5'
      >
        Drill into Code <ArrowRight className='h-3 w-3' />
      </button>
    </div>
  );
}

function ActivityCard({
  entries,
  loading,
  onShowAll,
}: {
  entries: ActivityEntry[];
  loading: boolean;
  onShowAll: () => void;
}) {
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2'>
      <div className='flex items-center gap-2'>
        <Activity className='h-3.5 w-3.5 text-accent-blue' />
        <h3 className='text-xs font-medium text-secondary'>Activity</h3>
      </div>
      {loading ? (
        <div className='text-[11px] text-muted'>Loading…</div>
      ) : entries.length === 0 ? (
        <div className='text-[11px] text-muted'>No events in the last 30 days.</div>
      ) : (
        <ul className='space-y-1 text-[11px]'>
          {entries.slice(0, 5).map((e) => (
            <li key={e.id} className='flex items-baseline gap-2'>
              <span className='text-muted font-mono text-[10px] w-12 flex-shrink-0'>
                {timeAgoShort(e.occurredAt)}
              </span>
              <span className='text-secondary truncate'>{e.summary || e.eventType}</span>
            </li>
          ))}
        </ul>
      )}
      <button
        onClick={onShowAll}
        className='text-[11px] text-accent-blue hover:underline inline-flex items-center gap-0.5'
      >
        Show all <ArrowRight className='h-3 w-3' />
      </button>
    </div>
  );
}

function Chip({
  children,
  color,
}: {
  children: React.ReactNode;
  color: 'success' | 'waiting' | 'error';
}) {
  const colorMap = {
    success: 'border-status-success/40 text-status-success',
    waiting: 'border-status-waiting/40 text-status-waiting',
    error: 'border-status-error/40 text-status-error',
  };
  return (
    <span
      className={cn(
        'inline-flex items-center text-[10px] font-mono uppercase tracking-wider px-1.5 py-0.5 rounded-sm border',
        colorMap[color],
      )}
    >
      {children}
    </span>
  );
}

function timeAgo(iso: string): string {
  const d = new Date(iso);
  const ms = Date.now() - d.getTime();
  const m = Math.round(ms / 60000);
  if (m < 1) return 'just now';
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const days = Math.round(h / 24);
  if (days < 30) return `${days}d ago`;
  return d.toLocaleDateString();
}

function timeAgoShort(iso: string): string {
  const d = new Date(iso);
  const ms = Date.now() - d.getTime();
  const m = Math.round(ms / 60000);
  if (m < 1) return 'now';
  if (m < 60) return `${m}m`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h`;
  const days = Math.round(h / 24);
  if (days < 7) return `${days}d`;
  return d.toLocaleDateString(undefined, {month: 'short', day: 'numeric'});
}
