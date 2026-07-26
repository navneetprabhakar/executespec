import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {Link, useLocation, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import type {
  ProjectCodeIndexRefreshAccepted,
  ProjectCodeIndexStatus,
  ProjectRepo,
  ProjectRepoLink,
} from '@/types/project';
import Crumbs from '@/ide/Crumbs';
import {useDocumentTitle} from '@/hooks/useDocumentTitle';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {LoadError} from '@/ide/LoadError';
import {toLoadFailure, type LoadFailure} from '@/lib/loadState';
import {ScoreBadge} from '@/ide/ScoreBadge';
import {PrReviewPanel} from '@/signal/components/PrReviewPanel';
import {ConfirmModal} from '@/signal/components/Modal';
import {runTitleFor, repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {
  codeIndexRefreshNotice,
  normalizeScmRepoQuery,
  repoSupports,
  sourceReadinessBadge,
  syncBadge,
} from '@/ide/projectRepoUi';
import {openPrsFromDashboard} from '@/ide/projectMoreUtils';

type Dash = Record<string, unknown>;
const numOf = (s: Dash, k: string): number | undefined =>
  typeof s[k] === 'number' ? (s[k] as number) : undefined;
// Primary language from the repo's detectedStack JSON ({language, framework, version}); null-safe.
const stackLanguage = (r: Dash): string | undefined => {
  const ds = r.detectedStack;
  if (typeof ds !== 'string' || !ds) return undefined;
  try {
    return (JSON.parse(ds) as {language?: string}).language || undefined;
  } catch {
    return undefined;
  }
};
// Repo size: KB → human (e.g. 1.2 MB / 840 KB).
const fmtKb = (kb: number): string =>
  kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${Math.round(kb)} KB`;

const codeIndexByRepo = (rows: ProjectCodeIndexStatus[]) => {
  const byRepo: Record<string, ProjectCodeIndexStatus> = {};
  rows.forEach((row) => {
    if (row.readiness.repoId) byRepo[row.readiness.repoId] = row;
  });
  return byRepo;
};
function relTime(input?: string | null): string {
  if (!input) return '—';
  const t = new Date(input).getTime();
  if (!t) return '—';
  const s = Math.round((Date.now() - t) / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return `${d}d ago`;
}

/**
 * Colour for the per-repo architecture badge (RR-1657).
 *
 * Only `GENERATED` is an affirmative "verified current" claim and earns green. `STALE` is a known
 * negative (yellow). `UNVERIFIED` — the server could not probe the repositories, or the project has
 * only LOCAL/non-GitHub sources — is neither, and must not be rendered as a pass. The previous
 * `includes('STALE') ? yellow : green` painted every unknown state green.
 */
const archStatusColor = (status: string): string => {
  const s = status.toUpperCase();
  if (s.includes('STALE')) return 'var(--ide-yellow)';
  if (s === 'GENERATED') return 'var(--ide-green)';
  return 'var(--ide-fg-muted)';
};

const LIVE = new Set(['CLASSIFYING', 'PLANNING', 'EXECUTING', 'AWAITING_APPROVAL', 'RUNNING']);
const isLive = (s?: string) => LIVE.has((s || '').toUpperCase());
const scoreOf = (r: unknown) => (r as {verifiedScore?: number}).verifiedScore;

// RR-748 — the canonical terminal-success run status is COMPLETE; the workflow counters recognized
// only COMPLETED, so real production runs vanished from Shipped/Needs.
const TERMINAL_SUCCESS = new Set(['COMPLETE', 'COMPLETED']);
// RR-749 — a missing verified score is NOT evidence a run is shippable. Shipped requires terminal
// success AND a score of at least 85; an unscored terminal-success run is Needs-review (unverified),
// not Shipped, and a PARTIAL run is always Needs.
const isShipped = (s: string, score?: number) =>
  TERMINAL_SUCCESS.has(s) && score != null && score >= 85;
const isNeeds = (s: string, score?: number) =>
  (TERMINAL_SUCCESS.has(s) && (score == null || score < 85)) || s === 'PARTIAL';
const costOf = (r: unknown) =>
  (r as {actualCostUsd?: number; actualCost?: number; totalCost?: number}).actualCostUsd ??
  (r as {actualCost?: number}).actualCost ??
  (r as {totalCost?: number}).totalCost;
// Project workbench IA. Keep every deep link alive, but stop presenting ten
// routes as equal peers. Primary tabs are the frequent workflow surfaces;
// secondary operational surfaces live under project Setup.
type ProjectTab = {
  label: string;
  seg: string;
  count?: 'repos' | 'runs' | 'code' | 'members';
};
const PRIMARY_TABS: ProjectTab[] = [
  {label: 'Start', seg: ''},
  {label: 'Overview', seg: '/pulse'},
  {label: 'Work', seg: '/runs', count: 'runs'},
  {label: 'Code', seg: '/code'},
  {label: 'Chat', seg: '/agent'},
  {label: 'Settings', seg: '/settings'},
];
const SECONDARY_TABS: ProjectTab[] = [
  {label: 'Setup', seg: '/setup'},
  {label: 'Repos', seg: '/repos', count: 'repos'},
  {label: 'System map', seg: '/system-map'},
  {label: 'Members', seg: '/members', count: 'members'},
  {label: 'Activity', seg: '/activity'},
];

type TabCounts = {repos?: number; runs?: number; code?: number; members?: number};

export function ProjectTabs({projectId}: {projectId: string}) {
  const {pathname} = useLocation();
  const activeTabRef = useRef<HTMLAnchorElement | null>(null);
  const activeMoreRef = useRef<HTMLAnchorElement | null>(null);
  // Live counts from the project dashboard + companions, clamped >= 0 so a
  // garbage signed-int can never reach the badge. Repos comes from the repo
  // list (dashboard carries no repo count) and Code from the tech-debt total
  // (dashboard carries no findings count) — matching what each tab surfaces.
  const [counts, setCounts] = useState<TabCounts>({});
  const [mobileOpen, setMobileOpen] = useState(false);
  // C2/C3 — project name for the browser-tab title + the breadcrumb trail.
  const [projectName, setProjectName] = useState<string | null>(null);
  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    Promise.allSettled([
      api.getProjectDashboard(projectId),
      api.getProjectRepos(projectId),
      api.getProjectMembers(projectId),
      api.getTechDebt(projectId),
      api.getProject(projectId),
    ]).then(([dash, repos, members, debt, proj]) => {
      if (cancelled) return;
      if (proj.status === 'fulfilled') setProjectName(proj.value.name || null);
      const d = dash.status === 'fulfilled' ? dash.value : null;
      // RR-753 — a rejected repo/member read must stay UNKNOWN (undefined → no
      // badge), not collapse to a confident `0`. Zeroing during an outage or a
      // 403 tells the user there are no repositories/members when we simply
      // could not read them, inviting duplicate setup or wrong access calls.
      const repoCount =
        repos.status === 'fulfilled' && Array.isArray(repos.value) ? repos.value.length : undefined;
      const memberCount =
        members.status === 'fulfilled' && Array.isArray(members.value)
          ? members.value.length
          : undefined;
      const totalRuns = d
        ? (d.activeRuns ?? 0) + (d.completedRuns ?? 0) + (d.failedRuns ?? 0)
        : undefined;
      const debtTotal =
        debt.status === 'fulfilled'
          ? Object.values(debt.value.issuesBySeverity || {}).reduce((a, b) => a + (b || 0), 0)
          : undefined;
      const safe = (n?: number) => (typeof n === 'number' && n >= 0 ? n : undefined);
      setCounts({
        repos: safe(repoCount),
        runs: safe(totalRuns),
        code: safe(debtTotal),
        members: safe(memberCount),
      });
    });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  // C2 — "<project> · ExecuteSpec" on every project sub-route (no-op until the
  // name loads, so there's no placeholder flash).
  useDocumentTitle(projectName);

  useEffect(() => {
    (activeTabRef.current ?? activeMoreRef.current)?.scrollIntoView({
      block: 'nearest',
      inline: 'center',
    });
  }, [pathname]);

  useEffect(() => {
    setMobileOpen(false);
  }, [pathname]);

  const tabTo = (t: ProjectTab) => `/projects/${projectId}${t.seg}`;
  const tabActive = (t: ProjectTab) => {
    const to = tabTo(t);
    if (t.seg === '/system-map') {
      return pathname.startsWith(to) || pathname.startsWith(`/projects/${projectId}/architecture`);
    }
    return t.seg === '' ? pathname === to : pathname.startsWith(to);
  };
  const tabLabel = (t: ProjectTab) => {
    const n = t.count ? counts[t.count] : undefined;
    return n != null ? `${t.label} · ${n}` : t.label;
  };
  const setupTab = SECONDARY_TABS[0];
  const secondaryActive = SECONDARY_TABS.find(tabActive);
  const allTabs = [...PRIMARY_TABS, ...SECONDARY_TABS];
  const activeProjectTab = PRIMARY_TABS.find(tabActive) ?? secondaryActive ?? PRIMARY_TABS[0];

  return (
    <>
      {/* C3 — one breadcrumb trail above the strip; every sub-tab inherits it. */}
      <Crumbs items={[{label: 'Projects', to: '/projects'}, {label: projectName ?? '…'}]} />
      <div className='ide-project-mobile-nav'>
        <button
          type='button'
          className='ide-project-mobile-trigger'
          aria-haspopup='menu'
          aria-expanded={mobileOpen}
          onClick={() => setMobileOpen((v) => !v)}
        >
          <span>{tabLabel(activeProjectTab)}</span>
          <span aria-hidden='true'>⌄</span>
        </button>
        {mobileOpen && (
          <div className='ide-project-mobile-menu' role='menu'>
            {allTabs.map((t) => (
              <Link
                key={t.label}
                to={tabTo(t)}
                state={t.seg === '' ? {compose: true} : undefined}
                className={`ide-project-mobile-item${tabActive(t) ? ' active' : ''}`}
                role='menuitem'
              >
                {tabLabel(t)}
              </Link>
            ))}
          </div>
        )}
      </div>
      <div className='ide-rtabs ide-project-tabs-strip' style={{marginBottom: 16}}>
        {PRIMARY_TABS.map((t) => {
          const active = tabActive(t);
          return (
            <Link
              key={t.label}
              to={tabTo(t)}
              // §3.4 — Start tab clicks carry explicit intent so IdeProject's
              // landing→Overview redirect doesn't bounce them straight back.
              state={t.seg === '' ? {compose: true} : undefined}
              className={`ide-rtab${active ? ' active' : ''}`}
              ref={active ? activeTabRef : undefined}
              style={{textDecoration: 'none'}}
            >
              {tabLabel(t)}
            </Link>
          );
        })}
        <div className='ide-project-more-tab'>
          <Link
            ref={secondaryActive ? activeMoreRef : undefined}
            className={`ide-rtab${secondaryActive ? ' active' : ''}`}
            to={tabTo(setupTab)}
            style={{textDecoration: 'none'}}
          >
            Setup
          </Link>
        </div>
      </div>
    </>
  );
}

function Metric({label, value}: {label: string; value: string}) {
  return (
    <div className='ide-card' style={{flex: 1, minWidth: 130}}>
      <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>{label}</div>
      <div style={{fontSize: 22, fontWeight: 700, color: 'var(--ide-fg-bright)', marginTop: 4}}>
        {value}
      </div>
    </div>
  );
}

function Notice({msg, kind}: {msg: string; kind: 'info' | 'bad'}) {
  return (
    <div
      className='ide-card'
      style={{marginBottom: 12, color: kind === 'bad' ? 'var(--ide-red)' : 'var(--ide-blue)'}}
    >
      {msg}
    </div>
  );
}

export function IdeProjectPulse() {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const {projectId = ''} = useParams();
  const [dash, setDash] = useState<Dash | null>(null);
  const [dashFailure, setDashFailure] = useState<LoadFailure | null>(null);
  const [health, setHealth] = useState<Array<Record<string, unknown>>>([]);
  const [recent, setRecent] = useState<api.RunSummary[] | null>(null);
  const [recentFailure, setRecentFailure] = useState<LoadFailure | null>(null);
  const [healthFailure, setHealthFailure] = useState<LoadFailure | null>(null);

  // RR-752 — a failed dashboard read must not masquerade as an empty-but-valid
  // dashboard (`setDash({})`), which renders blank metrics and no open-PR
  // section as though the server answered. Keep it as a retryable failure.
  const loadDash = useCallback(() => {
    if (!projectId) return;
    api
      .getProjectDashboard(projectId)
      .then((d) => {
        setDash(d as unknown as Dash);
        setDashFailure(null);
      })
      .catch((e: unknown) => setDashFailure(toLoadFailure(e)));
  }, [projectId]);

  // RL-2026-05-052 (#9): refresh recent runs via a callback so we can poll while a
  // run is live — the pulse fetched once on mount, so live project runs never updated.
  const refreshRuns = useCallback(() => {
    if (!projectId) return;
    api
      .getRuns({projectId, page: 0, size: 6})
      .then((r) => {
        setRecent(r.content ?? []);
        setRecentFailure(null);
      })
      // D4: "No work yet in this project." is a claim about the user's history.
      .catch((e: unknown) => setRecentFailure(toLoadFailure(e)));
  }, [projectId]);

  useEffect(() => {
    if (!projectId) return;
    loadDash();
    api
      .getWorkspaceHealth(projectId)
      .then((h) => {
        const arr = (h as unknown as {repos?: unknown[]}).repos || (Array.isArray(h) ? h : []);
        setHealth(arr as Array<Record<string, unknown>>);
        setHealthFailure(null);
      })
      // D4: an empty health list renders as "No linked repos."
      .catch((e: unknown) => setHealthFailure(toLoadFailure(e)));
    refreshRuns();
  }, [projectId, refreshRuns, loadDash]);

  // Live tracking (#9): poll recent runs every 12s while any is non-terminal
  // (skipped when the tab is hidden); stops once all runs are terminal.
  const hasLiveRun = (recent ?? []).some(
    (r) =>
      !['COMPLETE', 'COMPLETED', 'FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'].includes(
        (r.status || '').toUpperCase(),
      ),
  );
  useEffect(() => {
    if (!hasLiveRun) return;
    const id = window.setInterval(() => {
      if (!document.hidden) refreshRuns();
    }, 12000);
    return () => window.clearInterval(id);
  }, [hasLiveRun, refreshRuns]);

  // RR-1483 — read only fields DashboardDto actually returns. `recencyAvgScore`, `avgDurationMs`,
  // `totalSpent30d`, `mergedPrs30d` and `prsMerged30d` are all phantom keys the server never sends,
  // so the old fallbacks either produced a permanently dead tile or a headline number under a label
  // (`· 30d`) the data does not support. avgScore shows `—` for an unscored project rather than 0%;
  // spend is the all-time `totalCostUsd` (relabelled below); merged PRs are counted from the
  // recent-PR list (relabelled "recent", since the list is capped, not a 30-day window).
  const completedRuns = dash ? (numOf(dash, 'completedRuns') ?? 0) : 0;
  const rawAvgScore = dash ? numOf(dash, 'avgScore') : undefined;
  const avgScore = completedRuns > 0 ? rawAvgScore : undefined;
  const excludedScoreReports = dash ? (numOf(dash, 'excludedScoreReports') ?? 0) : 0;
  const spent = dash ? numOf(dash, 'totalCostUsd') : undefined;
  const merged = dash
    ? ((dash.recentPrs as unknown[]) || []).filter(
        (p) => ((p as {status?: string}).status || '').toUpperCase() === 'MERGED',
      ).length
    : undefined;
  // BUG-FIX (RL-2026-05-038): the dashboard returns `recentPrs`, not `openPrs` — so the
  // "Open pull requests" section was permanently empty. Derive open PRs from recentPrs.
  const openPrs = dash
    ? (((dash.recentPrs as unknown[]) || []) as Array<Record<string, unknown>>).filter(
        (p) =>
          String(
            (p as {status?: string}).status || (p as {state?: string}).state || '',
          ).toUpperCase() === 'OPEN',
      )
    : [];

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <h1 className='ide-h1'>Overview</h1>
      {/* §3.4 — the project landing redirects here once work exists; keep the
              composer one click away. {compose:true} skips the redirect. */}
      <div style={{margin: '4px 0 12px'}}>
        <Link
          to={`/projects/${projectId}`}
          state={{compose: true}}
          className='ide-btn ghost'
          style={{textDecoration: 'none', display: 'inline-block'}}
        >
          ＋ Start work in this project
        </Link>
      </div>
      {dashFailure ? (
        <LoadError failure={dashFailure} what='project overview' onRetry={loadDash} />
      ) : !dash ? (
        <IdeLoader label='Loading pulse…' />
      ) : (
        <>
          <div style={{display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 8}}>
            <Metric
              label='Avg score · internal'
              value={avgScore != null ? `${Math.round(avgScore)}% · review evidence` : '—'}
            />
            {isSuperAdmin && (
              <Metric
                label='Model spend · all-time'
                value={spent != null ? `$${spent.toFixed(4)}` : '—'}
              />
            )}
            <Metric label='PRs merged · recent' value={merged != null ? String(merged) : '—'} />
          </div>
          {excludedScoreReports > 0 && (
            <div style={{marginTop: 6, fontSize: 12, color: 'var(--ide-fg-muted)'}}>
              {excludedScoreReports} invalid validation{' '}
              {excludedScoreReports === 1 ? 'report was' : 'reports were'} excluded from the score.
            </div>
          )}

          <div className='ide-h2'>Workspace health</div>
          {healthFailure ? (
            <LoadError failure={healthFailure} what='linked repositories' />
          ) : health.length === 0 ? (
            <div className='ide-empty'>No linked repos.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {health.map((r, i) => {
                const sync = syncBadge(String(r.syncStatus || 'UNKNOWN'));
                const wh = (r as {webhookInstalled?: boolean}).webhookInstalled;
                const open = numOf(r, 'openPrCount') ?? numOf(r, 'openPrs') ?? 0;
                return (
                  <div
                    key={i}
                    style={{
                      display: 'flex',
                      gap: 10,
                      alignItems: 'center',
                      padding: '10px 16px',
                      borderBottom: i < health.length - 1 ? '1px solid var(--ide-border)' : 'none',
                    }}
                  >
                    <span style={{flex: 1, fontWeight: 500}}>{repoDisplayName(r)}</span>
                    <IdeBadge kind={sync.kind}>{sync.label}</IdeBadge>
                    <IdeBadge kind={wh ? 'ok' : 'warn'}>{wh ? 'webhook' : 'no webhook'}</IdeBadge>
                    <span style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}>{open} open PR</span>
                  </div>
                );
              })}
            </div>
          )}

          {openPrs.length > 0 && (
            <>
              <div className='ide-h2'>Open pull requests</div>
              <div className='ide-grid'>
                {openPrs.slice(0, 6).map((pr, i) => {
                  const status = String(pr.status || pr.state || 'OPEN').toUpperCase();
                  const repo = String(pr.repoFullName || '');
                  const num = pr.prNumber;
                  const add = numOf(pr, 'additions');
                  const rem = numOf(pr, 'deletions');
                  const url = (pr.prUrl as string) || (pr.htmlUrl as string) || '';
                  return (
                    <div key={`${repo}-${num}-${i}`} className='ide-card'>
                      <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6}}>
                        <IdeBadge kind={statusKind(status)}>PR · {status}</IdeBadge>
                        <span style={{flex: 1}} />
                        {(add != null || rem != null) && (
                          <span
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                          >
                            {add != null ? `+${add}` : ''} {rem != null ? `−${rem}` : ''}
                          </span>
                        )}
                      </div>
                      <div
                        style={{
                          fontWeight: 600,
                          color: 'var(--ide-fg-bright)',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                          whiteSpace: 'nowrap',
                        }}
                      >
                        {String(pr.title || `PR #${num}`)}
                      </div>
                      <div
                        className='ide-mono'
                        style={{fontSize: 11.5, color: 'var(--ide-fg-muted)', marginTop: 4}}
                      >
                        {repo}#{String(num ?? '')}
                      </div>
                      {url && (
                        <button
                          className='ide-btn ghost'
                          style={{marginTop: 10, padding: '4px 10px'}}
                          onClick={() => window.open(url, '_blank', 'noopener')}
                        >
                          Open on GitHub
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            </>
          )}

          <div
            style={{display: 'flex', alignItems: 'baseline', gap: 10, marginTop: 4}}
            className='ide-h2'
          >
            <span>Recent work</span>
            <span style={{flex: 1}} />
            <Link
              to={`/projects/${projectId}/runs`}
              style={{fontSize: 12, color: 'var(--ide-accent)', textDecoration: 'none'}}
            >
              View all
            </Link>
          </div>
          {recentFailure ? (
            <LoadError failure={recentFailure} what='work' onRetry={refreshRuns} />
          ) : recent === null ? (
            <IdeLoader label='Loading work…' />
          ) : recent.length === 0 ? (
            <div className='ide-empty'>No work yet in this project.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {recent.slice(0, 5).map((r, i) => {
                const sc = scoreOf(r);
                const cost = isSuperAdmin ? costOf(r) : undefined;
                const live = isLive(r.status);
                return (
                  <Link
                    key={r.runId}
                    to={live ? `/live/${r.runId}` : `/result/${r.runId}`}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 12,
                      padding: '11px 16px',
                      textDecoration: 'none',
                      color: 'var(--ide-fg)',
                      borderBottom:
                        i < recent.slice(0, 5).length - 1 ? '1px solid var(--ide-border)' : 'none',
                    }}
                  >
                    <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                    <span
                      style={{
                        flex: 1,
                        minWidth: 0,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        fontWeight: 500,
                      }}
                    >
                      {runTitleFor(r)}
                    </span>
                    {/* C5 — score badge (verdict-banded) + muted cost, null-guarded. */}
                    {typeof sc === 'number' ? (
                      <ScoreBadge score={sc} compact />
                    ) : (
                      <span style={{fontSize: 12, color: 'var(--ide-fg-muted)', flexShrink: 0}}>
                        {live ? 'live' : '—'}
                      </span>
                    )}
                    {typeof cost === 'number' && cost > 0 && (
                      <span
                        className='ide-mono'
                        style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}
                      >
                        ${cost.toFixed(4)}
                      </span>
                    )}
                  </Link>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}

type Sub = 'runs' | 'prs';
type RunFilter = 'all' | 'live' | 'shipped' | 'needs' | 'failed';

// Selected-run detail panel — ScoreCard-equivalent (score + verdict + tests +
// cost + PR), task list and PR open-link, mirroring Signal's InlineRunDetailPane.
function RunDetailPanel({
  run,
  report,
}: {
  run: api.RunDetail;
  report: api.ValidationReportData | null;
}) {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const [downloadBusy, setDownloadBusy] = useState(false);
  const [downloadMsg, setDownloadMsg] = useState('');
  // Legacy accuracyScore is an LLM assessment, not the verified score. Never
  // present it as the headline correctness metric in the project surface.
  const score = scoreOf(run);
  // RR-749 — the verdict must respect terminal run status, not derive from score alone. A
  // failed/cancelled run with a stale score is never "Shippable"; a terminal-success run with no
  // score is Unverified, not a positive shipping recommendation.
  const runStatus = (run.status || '').toUpperCase();
  const verdict =
    runStatus === 'FAILED' || runStatus === 'CANCELLED'
      ? 'Failed'
      : score == null
        ? TERMINAL_SUCCESS.has(runStatus)
          ? 'Unverified'
          : '—'
        : score >= 85
          ? 'Shippable'
          : score >= 70
            ? 'Needs review'
            : 'Failed';
  const cost = (run as unknown as {actualCostUsd?: number}).actualCostUsd ?? 0;
  const prUrl =
    (run as unknown as {scmPrUrl?: string}).scmPrUrl ||
    (run as unknown as {githubPrUrl?: string}).githubPrUrl;
  const tasks = run.tasks || [];
  const testsLabel =
    report?.testsTotal != null ? `${report.testsPassed ?? 0}/${report.testsTotal}` : '—';
  const filesGenerated =
    report?.filesGenerated ??
    (run as unknown as {filesGenerated?: number | null}).filesGenerated ??
    null;

  async function downloadCode() {
    setDownloadBusy(true);
    setDownloadMsg('');
    try {
      await api.downloadRunCode(run.runId, 'repo');
      setDownloadMsg('Code downloaded.');
    } catch (e: unknown) {
      setDownloadMsg(e instanceof Error ? e.message : 'Download failed.');
    } finally {
      setDownloadBusy(false);
    }
  }

  return (
    <div className='ide-card'>
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <div style={{fontSize: 28, fontWeight: 800, color: 'var(--ide-fg-bright)'}}>
          {score != null ? (
            <ScoreBadge score={score} verificationLedger={report?.verificationLedger} />
          ) : (
            '—'
          )}
        </div>
        <div>
          <IdeBadge kind={statusKind(run.status)}>{verdict}</IdeBadge>
          <div
            className='ide-mono'
            style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}
          >
            R-{run.runId.slice(0, 6)} · {run.status?.toLowerCase() || '—'}
          </div>
        </div>
      </div>
      <div className='ide-kv' style={{marginTop: 12}}>
        <span>Tests</span>
        <span>{testsLabel}</span>
      </div>
      <div className='ide-kv'>
        <span>Build</span>
        <span>
          {report?.finalBuildPassed == null ? '—' : report.finalBuildPassed ? 'passed' : 'failed'}
        </span>
      </div>
      <div className='ide-kv'>
        <span>Reviewer</span>
        <span>{report?.reviewerVerdict || '—'}</span>
      </div>
      {isSuperAdmin && (
        <div className='ide-kv'>
          <span>Model spend</span>
          <span>${cost.toFixed(4)}</span>
        </div>
      )}
      <div className='ide-kv'>
        <span>Files</span>
        <span>{filesGenerated ?? '—'}</span>
      </div>
      <div className='ide-kv'>
        <span>PR</span>
        <span>{prUrl ? 'open' : '—'}</span>
      </div>

      {tasks.length > 0 && (
        <>
          <div className='ide-h2' style={{fontSize: 13}}>
            Tasks
          </div>
          <div style={{display: 'flex', flexDirection: 'column', gap: 4}}>
            {tasks.slice(0, 8).map((t, i) => {
              const ts = (t.status || '').toUpperCase();
              const k =
                ts === 'COMPLETED' || ts === 'DONE'
                  ? 'ok'
                  : ts === 'EXECUTING' || ts === 'RUNNING'
                    ? 'warn'
                    : ts === 'FAILED' || ts === 'BLOCKED'
                      ? 'bad'
                      : 'warn';
              return (
                <div
                  key={t.taskId || i}
                  style={{display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5}}
                >
                  <IdeBadge kind={k}>{ts || 'TODO'}</IdeBadge>
                  <span
                    style={{
                      flex: 1,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                  >
                    {t.title || `Task ${i + 1}`}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}

      <div style={{display: 'flex', gap: 6, marginTop: 14, flexWrap: 'wrap'}}>
        <Link
          to={`/result/${run.runId}`}
          className='ide-btn ghost'
          style={{textDecoration: 'none', padding: '5px 12px'}}
        >
          Open full page
        </Link>
        <button
          className='ide-btn ghost'
          style={{padding: '5px 12px'}}
          disabled={downloadBusy || filesGenerated === 0}
          title={
            filesGenerated === 0
              ? 'No generated code is available for this run.'
              : 'Download the generated code as a clean repository zip.'
          }
          onClick={downloadCode}
        >
          {downloadBusy ? 'Downloading…' : 'Download code'}
        </button>
        {isLive(run.status) && (
          <Link
            to={`/live/${run.runId}`}
            className='ide-btn'
            style={{textDecoration: 'none', padding: '5px 12px'}}
          >
            Watch live
          </Link>
        )}
        {prUrl && (
          <button
            className='ide-btn ghost'
            style={{padding: '5px 12px'}}
            onClick={() => window.open(prUrl, '_blank', 'noopener')}
          >
            Open PR on GitHub
          </button>
        )}
      </div>
      {downloadMsg && (
        <div style={{marginTop: 8, color: 'var(--ide-fg-muted)', fontSize: 12}}>{downloadMsg}</div>
      )}
    </div>
  );
}

// RR-1484 — React Router reuses one <IdeProjectRuns /> element across
// /projects/A/runs and /projects/B/runs, so project-scoped state (selected run,
// PR list, report) leaked from project A into project B's route. Keying the
// inner component by projectId forces a clean remount on project change, which
// resets every project-scoped slice at once instead of relying on each effect's
// dependency array (several of which — e.g. the `prs === null` guard and the
// selection effect keyed only on `[selectedRunId]` — did not refetch on change).
export function IdeProjectRuns() {
  const {projectId = ''} = useParams();
  return <IdeProjectRunsInner key={projectId} />;
}

function IdeProjectRunsInner() {
  const isSuperAdmin = useAuthStore(selectIsSuperAdmin);
  const navigate = useNavigate();
  const {projectId = ''} = useParams();
  const [sub, setSub] = useState<Sub>('runs');
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [runsFailure, setRunsFailure] = useState<LoadFailure | null>(null);
  const [filter, setFilter] = useState<RunFilter>('all');
  const [prs, setPrs] = useState<Array<Record<string, unknown>> | null>(null);
  const [linkedRepos, setLinkedRepos] = useState<ProjectRepoLink[]>([]);
  const [prBusy, setPrBusy] = useState<string | null>(null);
  // C8 — credit-spending dispatch awaiting confirmation (PR-card "Autofix review").
  const [autofixFor, setAutofixFor] = useState<{
    prNumber: number;
    repo: string;
    repoId: string;
  } | null>(null);
  const [reviewOpen, setReviewOpen] = useState<string | null>(null);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  // RR-751 — a rejected getRun() left selectedRun=null, which the split pane
  // rendered as a permanent "Loading run…" spinner. Track the failure so the
  // pane can offer a diagnosis + retry instead of spinning forever.
  const [selectedRunFailure, setSelectedRunFailure] = useState<LoadFailure | null>(null);
  const [selectedRunNonce, setSelectedRunNonce] = useState(0);

  useEffect(() => {
    if (!projectId) return;
    api
      .getRuns({projectId, page: 0, size: 50})
      .then((r) => {
        setRuns(r.content ?? []);
        setRunsFailure(null);
      })
      // D4: a failure here rendered "No work matches this filter." plus zeroed
      // filter-tab badges, i.e. a confident count of work the user may well have.
      .catch((e: unknown) => setRunsFailure(toLoadFailure(e)));
  }, [projectId]);

  // Load PR list lazily when the PRs sub-tab is opened. Derive open PRs from the dashboard's
  // recentPrs (RR-747) rather than the non-existent openPrs field.
  useEffect(() => {
    if (!projectId || sub !== 'prs') return;
    let cancelled = false;
    if (prs === null) {
      api
        .getProjectDashboard(projectId)
        .then((d) => {
          if (cancelled) return;
          setPrs(openPrsFromDashboard(d));
        })
        .catch(() => {
          if (!cancelled) setPrs([]);
        });
    }
    return () => {
      cancelled = true;
    };
  }, [projectId, sub, prs]);

  // PR review admission is repository-id based. Resolve the dashboard's display-only
  // repoFullName through the project's linked repo rows before rendering a paid review action.
  useEffect(() => {
    if (!projectId || sub !== 'prs') return;
    let cancelled = false;
    api
      .getProjectRepos(projectId)
      .then((rows) => {
        if (!cancelled) setLinkedRepos(rows ?? []);
      })
      .catch(() => {
        if (!cancelled) setLinkedRepos([]);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId, sub]);

  // Selected run → fetch detail + validation report for the split-pane.
  useEffect(() => {
    if (!selectedRunId) {
      setSelectedRun(null);
      setReport(null);
      setSelectedRunFailure(null);
      return;
    }
    let cancelled = false;
    setSelectedRun(null);
    setReport(null);
    setSelectedRunFailure(null);
    Promise.allSettled([api.getRun(selectedRunId), api.getValidationReport(selectedRunId)]).then(
      ([r, vr]) => {
        if (cancelled) return;
        if (r.status === 'fulfilled') {
          setSelectedRun(r.value);
        } else {
          // RR-751 — the run detail is the primary content; a failed read is a
          // real failure, not a missing run. (A missing/ungenerated report is a
          // legitimate absence and is handled by RunDetailPanel.)
          setSelectedRunFailure(toLoadFailure(r.reason));
        }
        if (vr.status === 'fulfilled') setReport(vr.value);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [selectedRunId, selectedRunNonce]);

  async function refreshPrs() {
    if (!projectId) return;
    setPrBusy('refresh');
    setErr('');
    setMsg('');
    try {
      await api.refreshProjectPrs(projectId);
      const d = await api.getProjectDashboard(projectId);
      setPrs(openPrsFromDashboard(d));
      setMsg('PR snapshot refreshed.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'PR refresh failed.');
    } finally {
      setPrBusy(null);
    }
  }

  async function autofix(prNumber: number, repoFullName: string, repoId: string) {
    if (!projectId || !repoFullName || !repoId) return;
    const key = `${repoFullName}#${prNumber}`;
    setPrBusy(key);
    setErr('');
    setMsg('');
    try {
      // RR-746 — the old flow only fetched read-only comments and falsely claimed "autofix will be
      // dispatched" without creating a run. Resolve a COMPLETE PrReview for this PR and call the
      // canonical autofix POST, which actually dispatches a patch-mode run. If no completed review
      // exists, say so instead of confirming a dispatch that never happens.
      const reviews = await api.listPrReviewsForPr(projectId, repoId, repoFullName, prNumber);
      const completed = reviews
        .filter((r) => r.status === 'COMPLETE')
        .sort((a, b) => (b.finishedAt || '').localeCompare(a.finishedAt || ''))[0];
      if (!completed) {
        setErr('No completed PR review yet. Run a PR review first, then dispatch autofix.');
        return;
      }
      const res = await api.dispatchPrReviewAutofix(completed.id);
      setMsg(
        res.outcome === 'REUSED_ACTIVE'
          ? `Autofix already running — R-${res.runId.slice(0, 6)}.`
          : `Autofix run dispatched — R-${res.runId.slice(0, 6)}.`,
      );
      navigate(`/runs/${res.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to dispatch autofix.');
    } finally {
      setPrBusy(null);
    }
  }

  const counts = useMemo(() => {
    const list = runs || [];
    let live = 0,
      ship = 0,
      needs = 0,
      fail = 0;
    for (const r of list) {
      const s = (r.status || '').toUpperCase();
      const score = scoreOf(r);
      if (LIVE.has(s)) live++;
      else if (isShipped(s, score)) ship++;
      else if (isNeeds(s, score)) needs++;
      else if (s === 'FAILED' || s === 'CANCELLED') fail++;
    }
    return {live, ship, needs, fail};
  }, [runs]);

  const filtered = useMemo(() => {
    const list = runs || [];
    return list.filter((r) => {
      const s = (r.status || '').toUpperCase();
      const score = scoreOf(r);
      if (filter === 'all') return true;
      if (filter === 'live') return LIVE.has(s);
      if (filter === 'shipped') return isShipped(s, score);
      if (filter === 'needs') return isNeeds(s, score);
      if (filter === 'failed') return s === 'FAILED' || s === 'CANCELLED';
      return true;
    });
  }, [runs, filter]);

  const FILTERS: Array<{key: RunFilter; label: string; n: number}> = [
    {key: 'all', label: 'All', n: runs?.length ?? 0},
    {key: 'live', label: 'Live', n: counts.live},
    {key: 'shipped', label: 'Shipped', n: counts.ship},
    {key: 'needs', label: 'Needs', n: counts.needs},
    {key: 'failed', label: 'Failed', n: counts.fail},
  ];

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <h1 className='ide-h1'>Work</h1>

      <div className='ide-rtabs' style={{marginBottom: 12}}>
        <button
          className={`ide-rtab${sub === 'runs' ? ' active' : ''}`}
          onClick={() => setSub('runs')}
        >
          Work <span className='ide-rtab-badge'>{runs?.length ?? 0}</span>
        </button>
        <button
          className={`ide-rtab${sub === 'prs' ? ' active' : ''}`}
          onClick={() => setSub('prs')}
        >
          PRs <span className='ide-rtab-badge'>{prs?.length ?? '—'}</span>
        </button>
      </div>

      {msg && <Notice msg={msg} kind='info' />}
      {err && <Notice msg={err} kind='bad' />}

      {sub === 'runs' && (
        <>
          <div className='ide-rtabs' style={{marginBottom: 12}}>
            {FILTERS.map((f) => (
              <button
                key={f.key}
                className={`ide-rtab${filter === f.key ? ' active' : ''}`}
                onClick={() => setFilter(f.key)}
              >
                {f.label} <span className='ide-rtab-badge'>{f.n}</span>
              </button>
            ))}
          </div>

          {runsFailure ? (
            <LoadError failure={runsFailure} what='work' />
          ) : runs === null ? (
            <IdeLoader label='Loading work…' />
          ) : filtered.length === 0 ? (
            <div className='ide-empty'>No work matches this filter.</div>
          ) : (
            <div
              // C9.3 — stacks to one column under 600px (ide-stack-mobile in ide.css).
              className='ide-stack-mobile'
              style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(280px, 1fr) minmax(0, 1.2fr)',
                gap: 12,
                alignItems: 'start',
              }}
            >
              <div className='ide-card' style={{padding: 0, alignSelf: 'start'}}>
                {filtered.map((r, i) => {
                  const sc = scoreOf(r);
                  const cost = isSuperAdmin ? costOf(r) : undefined;
                  const live = isLive(r.status);
                  const selected = selectedRunId === r.runId;
                  return (
                    <button
                      key={r.runId}
                      onClick={() => setSelectedRunId(r.runId)}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                        width: '100%',
                        textAlign: 'left',
                        padding: '11px 14px',
                        background: selected ? 'var(--ide-bg-sidebar)' : 'transparent',
                        border: 'none',
                        borderLeft: `2px solid ${selected ? 'var(--ide-accent)' : 'transparent'}`,
                        color: 'var(--ide-fg)',
                        cursor: 'pointer',
                        borderBottom:
                          i < filtered.length - 1 ? '1px solid var(--ide-border)' : 'none',
                      }}
                    >
                      <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                      <span style={{flex: 1, minWidth: 0}}>
                        <span
                          style={{
                            display: 'block',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            whiteSpace: 'nowrap',
                            fontWeight: 500,
                          }}
                        >
                          {runTitleFor(r)}
                        </span>
                        <span
                          className='ide-mono'
                          style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                        >
                          R-{r.runId.slice(0, 6)} · {relTime(r.startedAt)}
                        </span>
                      </span>
                      {/* C5 — score badge (verdict-banded) + muted cost, null-guarded. */}
                      <span
                        style={{
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'flex-end',
                          gap: 2,
                          fontSize: 12,
                          color: 'var(--ide-fg-muted)',
                          flexShrink: 0,
                        }}
                      >
                        {typeof sc === 'number' ? (
                          <ScoreBadge score={sc} compact />
                        ) : live ? (
                          <span>live</span>
                        ) : null}
                        {typeof cost === 'number' && cost > 0 && (
                          <span className='ide-mono' style={{fontSize: 11}}>
                            ${cost.toFixed(4)}
                          </span>
                        )}
                      </span>
                    </button>
                  );
                })}
              </div>
              <div>
                {!selectedRunId ? (
                  <div className='ide-empty'>
                    Pick a run on the left to see its score, tasks, and PR.
                  </div>
                ) : selectedRunFailure ? (
                  <LoadError
                    failure={selectedRunFailure}
                    what='run details'
                    onRetry={() => setSelectedRunNonce((n) => n + 1)}
                  />
                ) : !selectedRun ? (
                  <IdeLoader label='Loading run…' />
                ) : (
                  <RunDetailPanel run={selectedRun} report={report} />
                )}
              </div>
            </div>
          )}
        </>
      )}

      {sub === 'prs' && (
        <>
          <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 10}}>
            <button className='ide-btn ghost' disabled={prBusy === 'refresh'} onClick={refreshPrs}>
              {prBusy === 'refresh' ? 'Refreshing…' : 'Refresh PRs'}
            </button>
          </div>
          {prs === null ? (
            <IdeLoader label='Loading pull requests…' />
          ) : prs.length === 0 ? (
            <div className='ide-empty'>
              No open pull requests. PRs appear here when a run pushes its branch.
            </div>
          ) : (
            <div className='ide-grid'>
              {prs.map((pr, i) => {
                const status = String(pr.status || pr.state || 'OPEN').toUpperCase();
                const repo = String(pr.repoFullName || '');
                const num = (pr.prNumber as number) ?? 0;
                const url = (pr.prUrl as string) || (pr.htmlUrl as string) || '';
                const add = numOf(pr, 'additions');
                const rem = numOf(pr, 'deletions');
                const ci = pr.checksStatus ? String(pr.checksStatus).toUpperCase() : '';
                const verdict = pr.reviewVerdict ? String(pr.reviewVerdict) : '';
                const comments = numOf(pr, 'commentsCount') ?? 0;
                const key = `${repo}#${num}`;
                const linkedRepo = linkedRepos.find(
                  (link) =>
                    repoDisplayName(link).toLowerCase() === repo.toLowerCase() &&
                    repoSupports(link, 'PR_REVIEW'),
                );
                const linkedRepoId = linkedRepo?.repoId ?? '';
                return (
                  <div key={`${key}-${i}`} className='ide-card'>
                    <div style={{display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6}}>
                      <IdeBadge kind={statusKind(status)}>PR · {status}</IdeBadge>
                      <span style={{flex: 1}} />
                      {!!(pr.mergedAt || pr.openedAt) && (
                        <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                          {relTime(String(pr.mergedAt || pr.openedAt))}
                        </span>
                      )}
                    </div>
                    <div
                      style={{
                        fontWeight: 600,
                        color: 'var(--ide-fg-bright)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {String(pr.title || `PR #${num}`)}
                    </div>
                    <div
                      className='ide-mono'
                      style={{fontSize: 11.5, color: 'var(--ide-fg-muted)', marginTop: 4}}
                    >
                      {repo}#{num}
                    </div>
                    <div
                      style={{
                        display: 'flex',
                        gap: 6,
                        flexWrap: 'wrap',
                        alignItems: 'center',
                        margin: '10px 0',
                      }}
                    >
                      {add != null && (
                        <span
                          className='ide-mono'
                          style={{fontSize: 11, color: 'var(--ide-green)'}}
                        >
                          +{add}
                        </span>
                      )}
                      {rem != null && (
                        <span className='ide-mono' style={{fontSize: 11, color: 'var(--ide-red)'}}>
                          −{rem}
                        </span>
                      )}
                      {ci && (
                        <IdeBadge
                          kind={ci === 'SUCCESS' ? 'ok' : ci === 'FAILURE' ? 'bad' : 'warn'}
                        >
                          CI {ci.toLowerCase()}
                        </IdeBadge>
                      )}
                      {verdict && <IdeBadge kind='warn'>{verdict}</IdeBadge>}
                      {comments > 0 && (
                        <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                          {comments} comment{comments === 1 ? '' : 's'}
                        </span>
                      )}
                    </div>
                    <div style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
                      {url && (
                        <button
                          className='ide-btn ghost'
                          style={{padding: '4px 10px'}}
                          onClick={() => window.open(url, '_blank', 'noopener')}
                        >
                          Open on GitHub
                        </button>
                      )}
                      {repo && num > 0 && linkedRepoId && (
                        <button
                          className='ide-btn ghost'
                          style={{padding: '4px 10px'}}
                          onClick={() => setReviewOpen((k) => (k === key ? null : key))}
                        >
                          {reviewOpen === key ? 'Hide AI review' : 'Review with AI'}
                        </button>
                      )}
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px'}}
                        disabled={prBusy === key || !linkedRepoId}
                        // C8 — credit-spending dispatch goes through a confirm modal.
                        onClick={() => setAutofixFor({prNumber: num, repo, repoId: linkedRepoId})}
                      >
                        {prBusy === key ? 'Loading…' : 'Autofix review'}
                      </button>
                    </div>
                    {reviewOpen === key && repo && num > 0 && linkedRepoId && (
                      <PrReviewPanel
                        projectId={projectId}
                        repoId={linkedRepoId}
                        repoFullName={repo}
                        prNumber={num}
                        variant='ide'
                      />
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </>
      )}

      {/* C8 — confirm before the paid autofix dispatch; the existing success
          message + run link flow inside autofix() is untouched. */}
      <ConfirmModal
        open={!!autofixFor}
        title='Dispatch autofix run'
        body='This dispatches a new run to fix the review comments. It consumes credits and opens a commit on the PR branch. Continue?'
        confirmLabel='Dispatch run'
        busy={!!autofixFor && prBusy === `${autofixFor.repo}#${autofixFor.prNumber}`}
        onConfirm={async () => {
          const a = autofixFor;
          if (a) await autofix(a.prNumber, a.repo, a.repoId);
        }}
        onClose={() => setAutofixFor(null)}
      />
    </div>
  );
}

export function IdeProjectRepos() {
  const {projectId = ''} = useParams();
  const authUser = useAuthStore((state) => state.user);
  const [repos, setRepos] = useState<Array<Record<string, unknown>> | null>(null);
  const [reposFailure, setReposFailure] = useState<LoadFailure | null>(null);
  const [health, setHealth] = useState<Record<string, Record<string, unknown>>>({});
  const [codeIndex, setCodeIndex] = useState<Record<string, ProjectCodeIndexStatus>>({});
  const [codeIndexFailure, setCodeIndexFailure] = useState<LoadFailure | null>(null);
  const [provider, setProvider] = useState<'GITHUB' | 'BITBUCKET'>('GITHUB');
  const [available, setAvailable] = useState<api.ScmRemoteRepository[] | null>(null);
  const [registered, setRegistered] = useState<ProjectRepo[]>([]);
  const [repoQuery, setRepoQuery] = useState('');
  const [showLink, setShowLink] = useState(false);
  // RR-755 — one scalar busy string let a second sync/regenerate/index/webhook/
  // unlink op replace the first's key while both ran, and either `finally` cleared
  // the state for both. Track every in-flight op by its stable key so pending
  // feedback is per-op and conflicting per-repo work is serialized.
  const [busyKeys, setBusyKeys] = useState<Set<string>>(new Set());
  const isBusy = (key: string) => busyKeys.has(key);
  const anyBusy = busyKeys.size > 0;
  const startBusy = (key: string) => setBusyKeys((prev) => new Set(prev).add(key));
  const endBusy = (key: string) =>
    setBusyKeys((prev) => {
      const next = new Set(prev);
      next.delete(key);
      return next;
    });
  // Only one mutation may run against a given repository at a time; ops on
  // different repos may still proceed in parallel.
  const repoBusy = (id: string) =>
    isBusy(`sync${id}`) ||
    isBusy(`regen${id}`) ||
    isBusy(`index${id}`) ||
    isBusy(`wh${id}`) ||
    isBusy(`unlink${id}`);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [unlinkFor, setUnlinkFor] = useState<{repoId: string; name: string} | null>(null);

  const normalizedQuery = normalizeScmRepoQuery(repoQuery, provider);
  const filteredAvailable =
    available?.filter((repo) =>
      repo.fullName.toLowerCase().includes(normalizedQuery.toLowerCase()),
    ) ?? [];
  const attachableTypedSlug =
    /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(normalizedQuery) &&
    !available?.some((repo) => repo.fullName.toLowerCase() === normalizedQuery.toLowerCase())
      ? normalizedQuery
      : '';

  function load() {
    api
      .getProjectRepos(projectId)
      .then((l) => {
        setRepos(l as unknown as Array<Record<string, unknown>>);
        setReposFailure(null);
      })
      // D4: "No repos linked. Click 'Link repo' above to add one." invited the
      // user to duplicate a link for a repo that may already exist.
      .catch((e: unknown) => setReposFailure(toLoadFailure(e)));
    api
      .getWorkspaceHealth(projectId)
      .then((h) => {
        const arr = (h as unknown as {repos?: unknown[]}).repos || (Array.isArray(h) ? h : []);
        const map: Record<string, Record<string, unknown>> = {};
        (arr as Array<Record<string, unknown>>).forEach((r) => {
          // Key by both repoId and display name so either shape resolves.
          map[repoDisplayName(r)] = r;
          const rid = repoIdFor(r);
          if (rid) map[rid] = r;
        });
        setHealth(map);
      })
      .catch(() => {});
    api
      .getProjectCodeIndex(projectId)
      .then((rows) => {
        setCodeIndex(codeIndexByRepo(rows));
        setCodeIndexFailure(null);
      })
      // An unavailable readiness endpoint is not evidence that semantic indexing is absent.
      // Preserve the previous projection and make the unknown state retryable.
      .catch((e: unknown) => setCodeIndexFailure(toLoadFailure(e)));
  }
  useEffect(() => {
    if (projectId) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  const semanticIndexRunning = Object.values(codeIndex).some(
    (row) => row.readiness.status === 'INDEXING',
  );
  useEffect(() => {
    if (!projectId || !semanticIndexRunning) return;
    const timer = window.setInterval(() => {
      api
        .getProjectCodeIndex(projectId)
        .then((rows) => setCodeIndex(codeIndexByRepo(rows)))
        .catch(() => {});
    }, 3000);
    return () => window.clearInterval(timer);
  }, [projectId, semanticIndexRunning]);

  async function openLinkPanel() {
    setShowLink(true);
    setErr('');
    if (!available) {
      try {
        const [remoteRepos, registeredRepos] = await Promise.all([
          api.listScmRepos(
            provider,
            provider === 'BITBUCKET' ? authUser?.bitbucketWorkspace || undefined : undefined,
          ),
          api.listRepos('ALL'),
        ]);
        setAvailable(remoteRepos);
        setRegistered(registeredRepos);
      } catch (e: unknown) {
        setErr(e instanceof Error ? e.message : 'Failed to load repos.');
      }
    }
  }

  async function act(
    key: string,
    fn: () => Promise<unknown>,
    done: string | ((result: unknown) => string) = 'Done.',
  ) {
    // RR-755 — refuse a duplicate of the same op, and (for per-repo keys) a
    // conflicting op on the same repository while one is already running.
    if (isBusy(key)) return;
    const repoId = key.replace(/^(sync|regen|index|wh|unlink)/, '');
    if (repoId !== key && repoBusy(repoId)) return;
    startBusy(key);
    setMsg('');
    setErr('');
    try {
      const result = await fn();
      setMsg(typeof done === 'function' ? done(result) : done);
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed.');
    } finally {
      endBusy(key);
    }
  }

  async function link(remote: {
    fullName: string;
    defaultBranch?: string;
    immutableId?: string;
    provider?: 'GITHUB' | 'BITBUCKET';
  }) {
    const key = remote.fullName;
    if (isBusy(`link${key}`)) return;
    startBusy(`link${key}`);
    setMsg('');
    setErr('');
    try {
      const selectedProvider = remote.provider || provider;
      let repo = registered.find(
        (r) =>
          String(r.scmProvider || 'GITHUB') === selectedProvider &&
          ((remote.immutableId && r.scmRepositoryId === remote.immutableId) ||
            r.sourceKey === remote.fullName ||
            r.githubRepoFullName === remote.fullName),
      );
      if (!repo) {
        repo = await api.createRepo({
          scope: 'USER',
          scmProvider: selectedProvider,
          repoFullName: remote.fullName,
          scmRepositoryId: remote.immutableId,
          defaultBranch: remote.defaultBranch,
          attachAnyway: false,
        });
        const createdRepo = repo;
        setRegistered((current) => [...current, createdRepo]);
      }
      await api.linkProjectRepo(projectId, repo.id);
      setMsg('Repo linked.');
      setShowLink(false);
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Link failed.');
    } finally {
      endBusy(`link${key}`);
    }
  }

  function confirmUnlink(repoId: string, name: string) {
    setUnlinkFor({repoId, name});
  }

  async function selectProvider(candidate: 'GITHUB' | 'BITBUCKET') {
    const key = `provider:${candidate}`;
    if (anyBusy) return;
    startBusy(key);
    setProvider(candidate);
    setRepoQuery('');
    setAvailable(null);
    setErr('');
    try {
      setAvailable(
        await api.listScmRepos(
          candidate,
          candidate === 'BITBUCKET' ? authUser?.bitbucketWorkspace || undefined : undefined,
        ),
      );
    } catch (e: unknown) {
      setAvailable([]);
      setErr(e instanceof Error ? e.message : `Failed to load ${candidate} repos.`);
    } finally {
      endBusy(key);
    }
  }

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <h1 className='ide-h1' style={{margin: 0}}>
          Repos
        </h1>
        <span style={{flex: 1}} />
        <button className='ide-btn' onClick={openLinkPanel}>
          + Link repo
        </button>
      </div>

      {msg && <Notice msg={msg} kind='info' />}
      {err && <Notice msg={err} kind='bad' />}
      {codeIndexFailure && (
        <LoadError failure={codeIndexFailure} what='semantic index status' onRetry={load} />
      )}

      {showLink && (
        <div className='ide-card' style={{margin: '12px 0'}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Available repos
          </div>
          <div style={{display: 'flex', gap: 6, marginBottom: 10}}>
            {(['GITHUB', 'BITBUCKET'] as const).map((candidate) => {
              const configured =
                candidate === 'GITHUB'
                  ? !!authUser?.hasGithubPat
                  : !!authUser?.hasBitbucketAppPassword;
              return (
                <button
                  key={candidate}
                  className={`ide-btn ${provider === candidate ? '' : 'ghost'}`}
                  disabled={!configured || anyBusy}
                  title={configured ? undefined : `Connect ${candidate} in Integrations first`}
                  onClick={() => void selectProvider(candidate)}
                >
                  {isBusy(`provider:${candidate}`)
                    ? 'Loading…'
                    : candidate === 'GITHUB'
                      ? 'GitHub'
                      : 'Bitbucket Cloud'}
                </button>
              );
            })}
          </div>
          <input
            className='ide-input'
            value={repoQuery}
            onChange={(event) => setRepoQuery(event.target.value)}
            placeholder={`Search repos or paste https://${provider === 'GITHUB' ? 'github.com' : 'bitbucket.org'}/workspace/repo`}
            style={{marginBottom: 10}}
          />
          {available === null ? (
            <IdeLoader label='Loading repos…' />
          ) : available.length === 0 && !attachableTypedSlug ? (
            <div className='ide-empty'>
              No connected repos yet. Connect {provider === 'GITHUB' ? 'GitHub' : 'Bitbucket'} from{' '}
              <Link to='/integrations' style={{color: 'var(--ide-accent)'}}>
                Integrations
              </Link>
              .
            </div>
          ) : (
            <div style={{display: 'flex', flexDirection: 'column', gap: 6}}>
              {attachableTypedSlug && (
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '8px 12px',
                    background: 'var(--ide-bg-sidebar)',
                    borderRadius: 6,
                  }}
                >
                  <span className='ide-mono' style={{flex: 1, fontSize: 12.5}}>
                    {attachableTypedSlug}
                  </span>
                  <button
                    className='ide-btn ghost'
                    style={{padding: '3px 12px'}}
                    disabled={isBusy(`link${attachableTypedSlug}`)}
                    onClick={() =>
                      link({
                        fullName: attachableTypedSlug,
                        provider,
                      })
                    }
                  >
                    {isBusy(`link${attachableTypedSlug}`) ? '…' : 'Attach by URL'}
                  </button>
                </div>
              )}
              {filteredAvailable.map((r) => {
                const registeredRepo = registered.find(
                  (candidate) =>
                    String(candidate.scmProvider || 'GITHUB') === r.provider &&
                    ((candidate.scmRepositoryId && candidate.scmRepositoryId === r.immutableId) ||
                      candidate.sourceKey === r.fullName ||
                      candidate.githubRepoFullName === r.fullName),
                );
                const linked = (repos || []).some(
                  (p) => repoDisplayName(p) === r.fullName || repoIdFor(p) === registeredRepo?.id,
                );
                return (
                  <div
                    key={r.fullName}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 10,
                      padding: '8px 12px',
                      background: 'var(--ide-bg-sidebar)',
                      borderRadius: 6,
                    }}
                  >
                    <span className='ide-mono' style={{flex: 1, fontSize: 12.5}}>
                      {r.fullName}
                    </span>
                    <button
                      className='ide-btn ghost'
                      style={{padding: '3px 12px'}}
                      disabled={linked || isBusy(`link${r.fullName}`)}
                      onClick={() => link(r)}
                    >
                      {linked ? 'Linked' : isBusy(`link${r.fullName}`) ? '…' : 'Link'}
                    </button>
                  </div>
                );
              })}
            </div>
          )}
          <button
            className='ide-btn ghost'
            style={{marginTop: 10}}
            onClick={() => setShowLink(false)}
          >
            Close
          </button>
        </div>
      )}

      {reposFailure ? (
        <LoadError failure={reposFailure} what='linked repositories' onRetry={load} />
      ) : repos === null ? (
        <IdeLoader label='Loading repos…' />
      ) : repos.length === 0 ? (
        <div className='ide-empty'>No repos linked. Click “Link repo” above to add one.</div>
      ) : (
        <div className='ide-grid'>
          {repos.map((r) => {
            const id = repoIdFor(r);
            const name = repoDisplayName(r);
            const h = health[id] || health[name] || {};
            const repo =
              ((r as {repo?: Record<string, unknown>}).repo as
                | Record<string, unknown>
                | undefined) || r;
            // V278 — LOCAL folder sources have no GitHub identity; connection is lease-derived.
            const isLocal = String((repo as {sourceType?: string}).sourceType || 'SCM') === 'LOCAL';
            const scmProvider = String((repo as {scmProvider?: string}).scmProvider || 'GITHUB');
            const canArchitecture = repoSupports(repo, 'ARCHITECTURE');
            const canSync = repoSupports(repo, 'SYNC');
            const canWebhook = repoSupports(repo, 'WEBHOOK');
            const canPrLink = repoSupports(repo, 'PR_LINK');
            const localOnline =
              (repo as {localConnectionStatus?: string}).localConnectionStatus === 'ONLINE';
            const lastSeen = (repo as {sourceLastSeenAt?: string}).sourceLastSeenAt;
            const revision = (repo as {sourceRevision?: string}).sourceRevision;
            const sync = syncBadge(String(h.syncStatus || 'UNKNOWN'));
            const wh = (h as {webhookInstalled?: boolean}).webhookInstalled;
            const open = numOf(h, 'openPrCount') ?? numOf(h, 'openPrs') ?? 0;
            const language = stackLanguage(repo);
            const sizeKb = numOf(repo, 'sizeKb');
            const fullName = (repo as {githubRepoFullName?: string}).githubRepoFullName || name;
            const head =
              (repo as {lastSyncedSha?: string}).lastSyncedSha ||
              (h as {currentOriginSha?: string}).currentOriginSha;
            const branch =
              (repo as {defaultBranch?: string}).defaultBranch ||
              (h as {defaultBranch?: string}).defaultBranch ||
              'main';
            const source = sourceReadinessBadge(
              String(repo.sourceReadinessStatus || 'UNKNOWN'),
              String(repo.sourceReadinessReason || ''),
              numOf(repo, 'sourceFileCount'),
            );
            const semanticIndex = codeIndex[id]?.readiness;
            const semanticKind =
              semanticIndex?.status === 'READY'
                ? 'ok'
                : semanticIndex?.status === 'FAILED'
                  ? 'bad'
                  : semanticIndex?.status === 'INDEXING'
                    ? 'warn'
                    : 'warn';
            const folderStatus = String(repo.executespecFolderStatus || 'NOT_BOOTSTRAPPED');
            const archStatus =
              (repo as {architectureStatus?: string}).architectureStatus ||
              (h as {architectureStatus?: string}).architectureStatus ||
              ((h as {isStale?: boolean}).isStale === true ? 'STALE' : undefined);
            return (
              <div key={id} className='ide-card'>
                <div style={{fontWeight: 600, color: 'var(--ide-fg-bright)', marginBottom: 8}}>
                  {isLocal ? '⌂' : '▤'} {name}
                </div>
                <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                  {isLocal ? (
                    <>
                      <IdeBadge kind='ok'>Local folder</IdeBadge>
                      <IdeBadge kind={localOnline ? 'ok' : 'warn'}>
                        {localOnline ? 'connector online' : 'connector offline'}
                      </IdeBadge>
                      <IdeBadge kind={source.kind}>{source.label}</IdeBadge>
                      {semanticIndex && (
                        <IdeBadge kind={semanticKind}>
                          semantic {semanticIndex.status.toLowerCase()} ·{' '}
                          {semanticIndex.coveragePercent.toFixed(0)}%
                        </IdeBadge>
                      )}
                    </>
                  ) : (
                    <>
                      <IdeBadge kind='ok'>
                        {scmProvider === 'BITBUCKET' ? 'Bitbucket Cloud' : 'GitHub'}
                      </IdeBadge>
                      <IdeBadge kind={sync.kind}>{sync.label}</IdeBadge>
                      <IdeBadge kind={source.kind}>{source.label}</IdeBadge>
                      {semanticIndex && (
                        <IdeBadge kind={semanticKind}>
                          semantic {semanticIndex.status.toLowerCase()} ·{' '}
                          {semanticIndex.coveragePercent.toFixed(0)}%
                        </IdeBadge>
                      )}
                      {repoSupports(repo, 'BOOTSTRAP') && (
                        <IdeBadge kind={folderStatus === 'BOOTSTRAPPED' ? 'ok' : 'warn'}>
                          {folderStatus === 'BOOTSTRAPPED'
                            ? '.executespec ready'
                            : folderStatus === 'BOOTSTRAP_PR_OPEN'
                              ? 'setup PR open'
                              : folderStatus === 'BOOTSTRAP_FAILED'
                                ? 'setup failed'
                                : 'setup optional'}
                        </IdeBadge>
                      )}
                      {canWebhook && (
                        <IdeBadge kind={wh ? 'ok' : 'warn'}>
                          {wh ? 'webhook ✓' : 'no webhook'}
                        </IdeBadge>
                      )}
                      {canPrLink && fullName.includes('/') ? (
                        <a
                          href={`https://github.com/${fullName}/pulls`}
                          target='_blank'
                          rel='noopener noreferrer'
                          style={{textDecoration: 'none'}}
                          title='View pull requests on GitHub'
                        >
                          <IdeBadge kind='ok'>{open} open PR ↗</IdeBadge>
                        </a>
                      ) : canPrLink ? (
                        <IdeBadge kind='ok'>{open} open PR</IdeBadge>
                      ) : null}
                    </>
                  )}
                </div>
                <div className='ide-kv'>
                  <span>Language</span>
                  <span>{language || '—'}</span>
                </div>
                <div className='ide-kv'>
                  <span>Size</span>
                  <span>{sizeKb != null ? fmtKb(sizeKb) : '—'}</span>
                </div>
                {isLocal ? (
                  <>
                    <div className='ide-kv'>
                      <span>Revision</span>
                      <span className='ide-mono'>
                        {revision ? String(revision).slice(0, 24) : '—'}
                      </span>
                    </div>
                    <div className='ide-kv'>
                      <span>Last seen</span>
                      <span>{lastSeen ? new Date(lastSeen).toLocaleString() : '—'}</span>
                    </div>
                  </>
                ) : (
                  <div className='ide-kv'>
                    <span>HEAD</span>
                    <span className='ide-mono'>
                      {head ? `${String(head).slice(0, 7)} · ${branch}` : branch}
                    </span>
                  </div>
                )}
                {archStatus && (
                  <div className='ide-kv'>
                    <span>Architecture</span>
                    <span
                      style={{
                        // RR-1657: green means "verified current". UNVERIFIED (probe failed, or the
                        // project has only LOCAL/non-GitHub sources) is not a pass — colouring
                        // anything-that-isn't-STALE green was the front half of the same fail-open.
                        color: archStatusColor(String(archStatus)),
                      }}
                    >
                      {String(archStatus)}
                    </span>
                  </div>
                )}
                {semanticIndex && (
                  <>
                    <div className='ide-kv'>
                      <span>Semantic source coverage</span>
                      <span>
                        {semanticIndex.indexedFiles}/{semanticIndex.eligibleFiles} files ·{' '}
                        {semanticIndex.coveragePercent.toFixed(1)}%
                      </span>
                    </div>
                    <div className='ide-kv'>
                      <span>Semantic status</span>
                      <span>
                        {semanticIndex.status.toLowerCase()}
                        {semanticIndex.reason ? ` · ${semanticIndex.reason}` : ''}
                      </span>
                    </div>
                    <div className='ide-kv'>
                      <span>Search fallback</span>
                      <span>ripgrep available · relationship graph not available</span>
                    </div>
                  </>
                )}
                <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10}}>
                  {canArchitecture && (
                    <Link
                      to={`/projects/${projectId}/architecture`}
                      className='ide-btn ghost'
                      style={{padding: '4px 10px', textDecoration: 'none'}}
                    >
                      View architecture
                    </Link>
                  )}
                  {!isLocal && canSync && (
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={repoBusy(id)}
                      title='Fast metadata refresh (stack, HEAD SHA) — no code regeneration'
                      onClick={() => act(`sync${id}`, () => api.syncRepo(id), 'Synced.')}
                    >
                      {isBusy(`sync${id}`) ? '…' : 'Sync'}
                    </button>
                  )}
                  {!isLocal && canArchitecture && (
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={repoBusy(id)}
                      title='Regenerate this repo codemap + architecture (LLM call — can take a minute)'
                      onClick={() =>
                        act(
                          `regen${id}`,
                          () => api.regenerateRepoArchitecture(projectId, id),
                          'Architecture regenerated.',
                        )
                      }
                    >
                      {isBusy(`regen${id}`) ? '…' : 'Regenerate'}
                    </button>
                  )}
                  {!isLocal && semanticIndex?.status !== 'READY' && (
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={repoBusy(id) || semanticIndex?.status === 'INDEXING'}
                      title='Resume the source-SHA-bound semantic chunk index. Exact ripgrep search remains available.'
                      onClick={() =>
                        act(
                          `index${id}`,
                          () => api.refreshProjectCodeIndex(projectId, id),
                          (result) =>
                            codeIndexRefreshNotice(result as ProjectCodeIndexRefreshAccepted),
                        )
                      }
                    >
                      {isBusy(`index${id}`) ? '…' : 'Resume semantic index'}
                    </button>
                  )}
                  {!isLocal && canWebhook && !wh && (
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={repoBusy(id)}
                      title='Installs a pull_request webhook so PR status + CI results update automatically'
                      onClick={() =>
                        act(`wh${id}`, () => api.installRepoWebhook(id), 'Webhook installed.')
                      }
                    >
                      {isBusy(`wh${id}`) ? '…' : 'Install webhook'}
                    </button>
                  )}
                  <button
                    className='ide-btn ghost'
                    style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                    disabled={repoBusy(id)}
                    onClick={() => confirmUnlink(id, name)}
                  >
                    {isBusy(`unlink${id}`) ? '…' : 'Unlink'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <ConfirmModal
        open={!!unlinkFor}
        title='Unlink repo'
        body={
          unlinkFor
            ? `Unlink "${unlinkFor.name}"? It will no longer be cloned or referenced by future runs. Existing runs and PRs are unaffected.`
            : ''
        }
        confirmLabel='Unlink'
        danger
        busy={!!unlinkFor && isBusy(`unlink${unlinkFor.repoId}`)}
        onConfirm={async () => {
          const u = unlinkFor;
          if (!u) return;
          await act(
            `unlink${u.repoId}`,
            () => api.unlinkProjectRepo(projectId, u.repoId),
            'Repo unlinked.',
          );
        }}
        onClose={() => setUnlinkFor(null)}
      />
    </div>
  );
}
