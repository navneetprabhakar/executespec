/**
 * PulseTab — replaces the old Overview pane after the v2.6.4 IA cleanup.
 *
 * 2026-05-07 visual refresh — rebuilt to match the website's
 * `Project Workspace` mockup (website/index.html .ws-mockup-* classes).
 * Same layout the marketing site shows: hero card + score ring on top,
 * spend bar below, 3-cell rollup (Health · Knowledge · Activity), then
 * the recent-PRs strip. Uses the new v3 primitives (`.v3-card`,
 * `.v3-rollup`, `.v3-eyebrow`, `.v3-chip`, `.v3-spendbar`,
 * `.v3-hero-grid`) so the page reads consistently with every other v3
 * surface and survives a future palette swap without per-component
 * edits.
 *
 * The earlier Tailwind-token version (`bg-[var(--gray-100)]`,
 * `border-[var(--border-default)]`) leaked v2 design tokens into a v3
 * shell. Switching to the v3 token namespace closes that gap and
 * lets light-theme + paper-* cascade through cleanly.
 */
import {Link} from 'react-router-dom';
import {
  ArrowRight,
  Activity,
  Heart,
  BookOpen,
  Sparkles,
  Rocket,
  FolderGit2,
  Plus,
} from 'lucide-react';
import ScoreRing from '@/components/runs/ScoreRing';
import StatusBadge from '@/components/shared/StatusBadge';
import EmptyState from '@/components/shared/EmptyState';
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
  onSwitchTab: (
    tab: 'pulse' | 'repos' | 'runs' | 'code' | 'chat',
    opts?: {
      codeSub?:
        | 'architecture'
        | 'tech-debt'
        | 'standards'
        | 'contracts'
        | 'health'
        | 'adrs'
        | 'invariants';
    },
  ) => void;
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
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
      <div style={{padding: 'var(--v3-pad-4)'}}>
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
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--v3-pad-4)',
        // Padding is applied by the parent `<main>` in ProjectDashboardPage
        // (`p-3 sm:p-4`); duplicating it here would double-pad the pane.
      }}
    >
      {/* v2.7.2 — inline "no repos linked" banner. Renders only when the
          parent passes onLinkRepo (which is only when repoCount === 0).
          Click pops AddRepoModal directly via the shared openAddRepoFlow
          plumbing in ProjectDashboardPage. */}
      {onLinkRepo && (
        <div
          style={{
            background: 'rgba(245,158,11,0.10)',
            border: '1px solid rgba(245,158,11,0.40)',
            borderRadius: 'var(--v3-radius-lg)',
            padding: 'var(--v3-pad-3) var(--v3-pad-4)',
            display: 'flex',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <FolderGit2 className='h-4 w-4' style={{color: '#fbbf24', flexShrink: 0}} />
          <div style={{flex: 1, minWidth: 0}}>
            <div style={{fontSize: 13, fontWeight: 600, color: 'var(--v3-text)'}}>
              No repositories linked
            </div>
            <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)', marginTop: 2}}>
              You have runs but no repos. Launches will be blocked until you link at least one.
            </div>
          </div>
          <button
            type='button'
            onClick={onLinkRepo}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 4,
              fontSize: 12,
              fontWeight: 600,
              padding: '5px 12px',
              borderRadius: 'var(--v3-radius)',
              border: '1px solid rgba(245,158,11,0.40)',
              background: 'rgba(245,158,11,0.14)',
              color: '#fbbf24',
              cursor: 'pointer',
              fontFamily: 'inherit',
            }}
          >
            <Plus className='h-3.5 w-3.5' />
            Add repository
          </button>
        </div>
      )}

      {/* Hero — last-run card on the left, verified-score ring on the right.
          Mirrors the website mockup's `.ws-mockup-grid` (1fr 200px), collapses
          on phones via the .v3-hero-grid media-query. */}
      <div className='v3-hero-grid'>
        <LastRunCard task={lastRun} report={report} onOpenRun={() => onOpenRun(lastRun.runId)} />
        <ScoreCardCompact score={heroScore} report={report} />
      </div>

      {/* Spend bar — only renders on warm projects. Single horizontal strip
          with mono spend + token total. Mirrors `.ws-mockup-spendbar`. */}
      <ProjectSpendBar />

      {/* Triple rollup — Health · Knowledge · Activity. */}
      <div className='v3-rollup'>
        <HealthCard projectId={projectId} onOpenCode={() => onSwitchTab('code')} />
        <KnowledgeCard projectId={projectId} />
        <ActivityCard
          entries={activity}
          loading={activityLoading}
          onShowAll={() => {
            const url = new URL(window.location.href);
            url.searchParams.set('drawer', 'activity');
            window.history.replaceState(null, '', url.toString());
            window.dispatchEvent(new Event('drawer-state'));
          }}
        />
      </div>

      {/* Recent PRs — compact list (full list under Runs tab → PRs sub) */}
      {recentPrs.length > 0 && (
        <div className='v3-card'>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              marginBottom: 'var(--v3-pad-2)',
            }}
          >
            <h3 style={{margin: 0, fontSize: 13, fontWeight: 600, color: 'var(--v3-text)'}}>
              Recent pull requests
            </h3>
            <button
              type='button'
              onClick={() => onSwitchTab('runs')}
              style={{
                fontSize: 11.5,
                color: 'var(--v3-brand-hover)',
                background: 'transparent',
                border: 0,
                cursor: 'pointer',
                fontFamily: 'inherit',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 2,
              }}
            >
              View all <ArrowRight className='h-3 w-3' />
            </button>
          </div>
          <ul
            style={{
              margin: 0,
              padding: 0,
              listStyle: 'none',
              display: 'flex',
              flexDirection: 'column',
              gap: 6,
            }}
          >
            {recentPrs.slice(0, 3).map((pr) => (
              <li
                key={`${pr.repoFullName}-${pr.prNumber}`}
                style={{display: 'flex', alignItems: 'center', gap: 8, fontSize: 12}}
              >
                <StatusBadge status={pr.status} />
                <a
                  href={pr.prUrl}
                  target='_blank'
                  rel='noreferrer'
                  style={{
                    color: 'var(--v3-text)',
                    flex: 1,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    textDecoration: 'none',
                  }}
                >
                  {pr.repoFullName} {pr.headBranch ? `· ${pr.headBranch}` : ''}
                </a>
                <span className='v3-mono' style={{color: 'var(--v3-text-muted)', fontSize: 11}}>
                  #{pr.prNumber}
                </span>
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
    <div className='v3-spendbar'>
      <span style={{color: 'var(--v3-text-muted)'}}>Project spend so far</span>
      <span
        className='v3-mono'
        style={{fontWeight: 600, color: 'var(--v3-text)', fontVariantNumeric: 'tabular-nums'}}
      >
        ${total.toFixed(2)}
      </span>
      {tokens > 0 && (
        <>
          <span style={{color: 'var(--v3-text-subtle)'}}>·</span>
          <span
            className='v3-mono'
            style={{
              color: 'var(--v3-text-muted)',
              fontSize: 11.5,
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            {formatTokens(tokens)} tokens
          </span>
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
    <div className='v3-card' style={{display: 'flex', flexDirection: 'column', gap: 8}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 8}}>
        <span className='v3-eyebrow'>Last run</span>
        <StatusBadge status={task.status} />
      </div>
      <div
        style={{
          fontSize: 15,
          fontWeight: 600,
          color: 'var(--v3-text)',
          lineHeight: 1.35,
          overflow: 'hidden',
          display: '-webkit-box',
          WebkitLineClamp: 2,
          WebkitBoxOrient: 'vertical',
        }}
      >
        {task.name || task.runId.slice(0, 8)}
      </div>
      <div className='v3-mono' style={{fontSize: 11, color: 'var(--v3-text-subtle)'}}>
        {task.runId.slice(0, 8)} · {task.createdAt ? timeAgo(task.createdAt) : '—'}
      </div>

      {/* Signal chips */}
      {(buildOk || buildBad || tests || reviewer) && (
        <div style={{display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 4}}>
          {buildOk && <span className='v3-chip v3-chip--ok'>BUILD ✓</span>}
          {buildBad && <span className='v3-chip v3-chip--err'>BUILD ✗</span>}
          {tests && (
            <span
              className={
                report?.testsPassed === report?.testsTotal
                  ? 'v3-chip v3-chip--ok'
                  : 'v3-chip v3-chip--warn'
              }
            >
              {tests} tests
            </span>
          )}
          {reviewer && (
            <span
              className={
                reviewer === 'CLEAN'
                  ? 'v3-chip v3-chip--ok'
                  : reviewer === 'FIXED_ISSUES'
                    ? 'v3-chip v3-chip--warn'
                    : 'v3-chip v3-chip--err'
              }
            >
              Reviewer {reviewer.toLowerCase().replace('_', ' ')}
            </span>
          )}
        </div>
      )}

      <div style={{display: 'flex', gap: 8, marginTop: 6}}>
        <button
          type='button'
          onClick={onOpenRun}
          style={{
            background: 'var(--v3-brand)',
            color: 'var(--v3-text-on-brand)',
            border: 0,
            padding: '5px 12px',
            borderRadius: 5,
            fontSize: 12,
            fontWeight: 600,
            cursor: 'pointer',
            fontFamily: 'inherit',
          }}
        >
          View report
        </button>
        <Link
          to={`/runs/${task.runId}`}
          style={{
            background: 'transparent',
            color: 'var(--v3-text-muted)',
            border: '1px solid var(--v3-border)',
            padding: '5px 12px',
            borderRadius: 5,
            fontSize: 12,
            fontWeight: 600,
            textDecoration: 'none',
            fontFamily: 'inherit',
          }}
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
      <div
        className='v3-card'
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          minHeight: 160,
          gap: 4,
        }}
      >
        <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)'}}>No score yet</div>
        <div style={{fontSize: 10.5, color: 'var(--v3-text-subtle)'}}>
          Awaiting validation report
        </div>
      </div>
    );
  }
  const isVerified = report?.verifiedScore != null;
  return (
    <div
      className='v3-card'
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 6,
      }}
    >
      <ScoreRing score={score} label={isVerified ? 'verified / 100' : 'est / 100'} size={120} />
      {isVerified &&
        report?.accuracyScore != null &&
        report.accuracyScore !== report.verifiedScore && (
          <div className='v3-mono' style={{fontSize: 11, color: 'var(--v3-text-muted)'}}>
            est. {Math.round(report.accuracyScore)}
          </div>
        )}
    </div>
  );
}

function HealthCard({projectId, onOpenCode}: {projectId: string; onOpenCode: () => void}) {
  const [health, setHealth] = useState<api.WorkspaceHealthReport | null>(null);
  const dashboard = useProjectStore((s) => s.dashboard);
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
  const archStatus = dashboard?.architectureDocStatus ?? null;

  const openArchTab = () => {
    const url = new URL(window.location.href);
    url.searchParams.set('tab', 'code');
    url.searchParams.set('codeSub', 'architecture');
    window.history.pushState(null, '', url.toString());
    window.dispatchEvent(new PopStateEvent('popstate'));
  };

  return (
    <div className='v3-rollup-cell'>
      <div style={{display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6}}>
        <Heart className='h-3.5 w-3.5' style={{color: 'var(--v3-success)'}} />
        <h3 style={{margin: 0, fontSize: 12, fontWeight: 600, color: 'var(--v3-text)'}}>
          Health &amp; debt
        </h3>
      </div>
      {!health ? (
        <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)'}}>Loading…</div>
      ) : totalRepos === 0 ? (
        <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)'}}>No repos linked yet.</div>
      ) : (
        <ul
          style={{
            margin: 0,
            padding: 0,
            listStyle: 'none',
            display: 'flex',
            flexDirection: 'column',
            gap: 3,
            fontSize: 11.5,
            lineHeight: 1.5,
          }}
        >
          <li style={{color: 'var(--v3-text-muted)'}}>
            {totalRepos} repo{totalRepos === 1 ? '' : 's'} linked
          </li>
          <li style={{color: stale > 0 ? 'var(--v3-warning)' : 'var(--v3-success)'}}>
            {stale === 0 ? 'codemaps current' : `${stale} stale codemap${stale === 1 ? '' : 's'}`}
          </li>
          {archStatus === 'NONE' ? (
            <li style={{color: 'var(--v3-warning)'}}>
              Architecture not generated —{' '}
              <button
                type='button'
                onClick={openArchTab}
                style={{
                  color: 'var(--v3-brand-hover)',
                  background: 'transparent',
                  border: 0,
                  cursor: 'pointer',
                  fontFamily: 'inherit',
                  fontSize: 'inherit',
                  padding: 0,
                  textDecoration: 'underline',
                }}
              >
                Generate now
              </button>
            </li>
          ) : archStatus === 'STALE' ? (
            <li style={{color: 'var(--v3-warning)'}}>
              Architecture stale —{' '}
              <button
                type='button'
                onClick={openArchTab}
                style={{
                  color: 'var(--v3-brand-hover)',
                  background: 'transparent',
                  border: 0,
                  cursor: 'pointer',
                  fontFamily: 'inherit',
                  fontSize: 'inherit',
                  padding: 0,
                  textDecoration: 'underline',
                }}
              >
                Regenerate
              </button>
            </li>
          ) : archStatus === 'GENERATED' ? (
            <li style={{color: 'var(--v3-success)'}}>Architecture current</li>
          ) : null}
          <li style={{color: 'var(--v3-text-muted)'}}>
            {openPrs} open PR{openPrs === 1 ? '' : 's'}
          </li>
          {lastAnalysed && (
            <li className='v3-mono' style={{color: 'var(--v3-text-subtle)', fontSize: 10.5}}>
              last analysed {timeAgo(lastAnalysed)}
            </li>
          )}
        </ul>
      )}
      <button
        type='button'
        onClick={onOpenCode}
        style={{
          fontSize: 11.5,
          color: 'var(--v3-brand-hover)',
          background: 'transparent',
          border: 0,
          cursor: 'pointer',
          fontFamily: 'inherit',
          padding: 0,
          marginTop: 8,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 2,
          textDecoration: 'underline',
        }}
      >
        Drill into Code <ArrowRight className='h-3 w-3' />
      </button>
    </div>
  );
}

function KnowledgeCard({projectId}: {projectId: string}) {
  const dashboard = useProjectStore((s) => s.dashboard);
  const archStatus = dashboard?.architectureDocStatus ?? null;
  const archReady = archStatus === 'GENERATED' || archStatus === 'STALE';
  const [explanationReady, setExplanationReady] = useState<boolean | null>(null);
  const [setupReady, setSetupReady] = useState<boolean | null>(null);

  useEffect(() => {
    if (!archReady) {
      setExplanationReady(null);
      setSetupReady(null);
      return;
    }
    let cancelled = false;
    Promise.all([
      api.getProjectDoc(projectId, 'EXPLANATION').catch(() => null),
      api.getProjectDoc(projectId, 'SETUP').catch(() => null),
    ]).then(([e, s]) => {
      if (cancelled) return;
      setExplanationReady(e !== null);
      setSetupReady(s !== null);
    });
    return () => {
      cancelled = true;
    };
  }, [projectId, archReady]);

  const openDocsDrawer = () => {
    const url = new URL(window.location.href);
    url.searchParams.set('drawer', 'docs');
    window.history.replaceState(null, '', url.toString());
    window.dispatchEvent(new Event('drawer-state'));
  };

  return (
    <div className='v3-rollup-cell'>
      <div style={{display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6}}>
        <BookOpen className='h-3.5 w-3.5' style={{color: 'var(--v3-brand-hover)'}} />
        <h3 style={{margin: 0, fontSize: 12, fontWeight: 600, color: 'var(--v3-text)'}}>
          Knowledge
        </h3>
      </div>
      {!archReady ? (
        <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)', lineHeight: 1.5}}>
          Generate the architecture doc to unlock the human-readable explanation + setup write-ups.
        </div>
      ) : (
        <ul
          style={{
            margin: 0,
            padding: 0,
            listStyle: 'none',
            display: 'flex',
            flexDirection: 'column',
            gap: 3,
            fontSize: 11.5,
            lineHeight: 1.5,
          }}
        >
          <li style={{color: explanationReady ? 'var(--v3-success)' : 'var(--v3-text-muted)'}}>
            Explanation{' '}
            {explanationReady === null ? '…' : explanationReady ? 'ready' : 'not generated'}
          </li>
          <li style={{color: setupReady ? 'var(--v3-success)' : 'var(--v3-text-muted)'}}>
            Setup guide {setupReady === null ? '…' : setupReady ? 'ready' : 'not generated'}
          </li>
        </ul>
      )}
      <button
        type='button'
        onClick={openDocsDrawer}
        style={{
          fontSize: 11.5,
          color: 'var(--v3-brand-hover)',
          background: 'transparent',
          border: 0,
          cursor: 'pointer',
          fontFamily: 'inherit',
          padding: 0,
          marginTop: 8,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 4,
          textDecoration: 'underline',
        }}
      >
        {archReady && (explanationReady || setupReady) ? (
          <>
            Open in More <ArrowRight className='h-3 w-3' />
          </>
        ) : (
          <>
            <Sparkles className='h-3 w-3' /> Generate write-ups
          </>
        )}
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
    <div className='v3-rollup-cell'>
      <div style={{display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6}}>
        <Activity className='h-3.5 w-3.5' style={{color: 'var(--v3-brand-hover)'}} />
        <h3 style={{margin: 0, fontSize: 12, fontWeight: 600, color: 'var(--v3-text)'}}>
          Activity
        </h3>
      </div>
      {loading ? (
        <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)'}}>Loading…</div>
      ) : entries.length === 0 ? (
        <div style={{fontSize: 11.5, color: 'var(--v3-text-muted)'}}>
          No events in the last 30 days.
        </div>
      ) : (
        <ul
          style={{
            margin: 0,
            padding: 0,
            listStyle: 'none',
            display: 'flex',
            flexDirection: 'column',
            gap: 3,
            fontSize: 11.5,
          }}
        >
          {entries.slice(0, 5).map((e) => (
            <li key={e.id} style={{display: 'flex', alignItems: 'baseline', gap: 8}}>
              <span
                className='v3-mono'
                style={{
                  color: 'var(--v3-text-subtle)',
                  fontSize: 10.5,
                  width: 44,
                  flexShrink: 0,
                }}
              >
                {timeAgoShort(e.occurredAt)}
              </span>
              <span
                style={{
                  color: 'var(--v3-text-muted)',
                  flex: 1,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {e.summary || e.eventType}
              </span>
            </li>
          ))}
        </ul>
      )}
      <button
        type='button'
        onClick={onShowAll}
        style={{
          fontSize: 11.5,
          color: 'var(--v3-brand-hover)',
          background: 'transparent',
          border: 0,
          cursor: 'pointer',
          fontFamily: 'inherit',
          padding: 0,
          marginTop: 8,
          display: 'inline-flex',
          alignItems: 'center',
          gap: 2,
          textDecoration: 'underline',
        }}
      >
        Show all <ArrowRight className='h-3 w-3' />
      </button>
    </div>
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
