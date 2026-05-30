import {useCallback, useEffect, useMemo, useState} from 'react';
import {Link, useLocation, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeLoader, statusKind} from '@/ide/IdeKit';
import {PrReviewPanel} from '@/signal/components/PrReviewPanel';
import {ConfirmModal} from '@/signal/components/Modal';
import {runTitleFor, repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';

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

const LIVE = new Set(['CLASSIFYING', 'PLANNING', 'EXECUTING', 'AWAITING_APPROVAL', 'RUNNING']);
const isLive = (s?: string) => LIVE.has((s || '').toUpperCase());
const scoreOf = (r: unknown) => (r as {verifiedScore?: number}).verifiedScore;
const costOf = (r: unknown) =>
  (r as {actualCostUsd?: number; actualCost?: number; totalCost?: number}).actualCostUsd ??
  (r as {actualCost?: number}).actualCost ??
  (r as {totalCost?: number}).totalCost;

// Workbench tab strip — shown on every project sub-route. `count` keys a tab
// to the live-count map below; tabs without a key stay label-only.
const T: Array<{label: string; seg: string; count?: 'repos' | 'runs' | 'code' | 'members'}> = [
  {label: 'Compose', seg: ''},
  {label: 'Pulse', seg: '/pulse'},
  {label: 'Repos', seg: '/repos', count: 'repos'},
  {label: 'Runs', seg: '/runs', count: 'runs'},
  {label: 'Code', seg: '/code', count: 'code'},
  {label: 'Chat', seg: '/chat'},
  {label: 'Architecture', seg: '/architecture'},
  {label: 'Members', seg: '/members', count: 'members'},
  {label: 'Activity', seg: '/activity'},
  {label: 'Settings', seg: '/settings'},
];

type TabCounts = {repos?: number; runs?: number; code?: number; members?: number};

export function ProjectTabs({projectId}: {projectId: string}) {
  const {pathname} = useLocation();
  // Live counts from the project dashboard + companions, clamped >= 0 so a
  // garbage signed-int can never reach the badge. Repos comes from the repo
  // list (dashboard carries no repo count) and Code from the tech-debt total
  // (dashboard carries no findings count) — matching what each tab surfaces.
  const [counts, setCounts] = useState<TabCounts>({});
  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    Promise.allSettled([
      api.getProjectDashboard(projectId),
      api.getProjectRepos(projectId),
      api.getProjectMembers(projectId),
      api.getTechDebt(projectId),
    ]).then(([dash, repos, members, debt]) => {
      if (cancelled) return;
      const d = dash.status === 'fulfilled' ? dash.value : null;
      const repoArr = repos.status === 'fulfilled' && Array.isArray(repos.value) ? repos.value : [];
      const memberArr =
        members.status === 'fulfilled' && Array.isArray(members.value) ? members.value : [];
      const totalRuns = d
        ? (d.activeRuns ?? 0) + (d.completedRuns ?? 0) + (d.failedRuns ?? 0)
        : undefined;
      const debtTotal =
        debt.status === 'fulfilled'
          ? Object.values(debt.value.issuesBySeverity || {}).reduce((a, b) => a + (b || 0), 0)
          : undefined;
      const safe = (n?: number) => (typeof n === 'number' && n >= 0 ? n : undefined);
      setCounts({
        repos: safe(repoArr.length),
        runs: safe(totalRuns),
        code: safe(debtTotal),
        members: safe(memberArr.length),
      });
    });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  return (
    <div className='ide-rtabs' style={{marginBottom: 16}}>
      {T.map((t) => {
        const to = `/projects/${projectId}${t.seg}`;
        const active = t.seg === '' ? pathname === to : pathname.startsWith(to);
        const n = t.count ? counts[t.count] : undefined;
        return (
          <Link
            key={t.label}
            to={to}
            className={`ide-rtab${active ? ' active' : ''}`}
            style={{textDecoration: 'none'}}
          >
            {n != null ? `${t.label} · ${n}` : t.label}
          </Link>
        );
      })}
    </div>
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
  const {projectId = ''} = useParams();
  const [dash, setDash] = useState<Dash | null>(null);
  const [health, setHealth] = useState<Array<Record<string, unknown>>>([]);
  const [recent, setRecent] = useState<api.RunSummary[] | null>(null);

  // RL-2026-05-052 (#9): refresh recent runs via a callback so we can poll while a
  // run is live — the pulse fetched once on mount, so live project runs never updated.
  const refreshRuns = useCallback(() => {
    if (!projectId) return;
    api
      .getRuns({projectId, page: 0, size: 6})
      .then((r) => setRecent(r.content ?? []))
      .catch(() => setRecent([]));
  }, [projectId]);

  useEffect(() => {
    if (!projectId) return;
    api
      .getProjectDashboard(projectId)
      .then((d) => setDash(d as unknown as Dash))
      .catch(() => setDash({}));
    api
      .getWorkspaceHealth(projectId)
      .then((h) => {
        const arr = (h as unknown as {repos?: unknown[]}).repos || (Array.isArray(h) ? h : []);
        setHealth(arr as Array<Record<string, unknown>>);
      })
      .catch(() => setHealth([]));
    refreshRuns();
  }, [projectId, refreshRuns]);

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

  const avgScore = dash ? (numOf(dash, 'recencyAvgScore') ?? numOf(dash, 'avgScore')) : undefined;
  const dur = dash ? numOf(dash, 'avgDurationMs') : undefined;
  const runTime = dur ? `${Math.floor(dur / 60000)}m ${Math.floor((dur % 60000) / 1000)}s` : '—';
  const spent = dash ? (numOf(dash, 'totalSpent30d') ?? numOf(dash, 'totalCostUsd')) : undefined;
  // Signal uses `mergedPrs30d`; the IDE page previously read the wrong key
  // (`prsMerged30d`). Read both with a fallback, then a final fallback that
  // counts MERGED entries in the dashboard's recentPrs list.
  const merged = dash
    ? (numOf(dash, 'mergedPrs30d') ??
      numOf(dash, 'prsMerged30d') ??
      ((dash.recentPrs as unknown[]) || []).filter(
        (p) => ((p as {status?: string}).status || '').toUpperCase() === 'MERGED',
      ).length)
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
      <div className='ide-h1'>Pulse</div>
      {!dash ? (
        <IdeLoader label='Loading pulse…' />
      ) : (
        <>
          <div style={{display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 8}}>
            <Metric label='Avg score' value={avgScore != null ? `${Math.round(avgScore)}%` : '—'} />
            <Metric label='Avg run time' value={runTime} />
            <Metric label='Spent · 30d' value={spent != null ? `$${spent.toFixed(2)}` : '—'} />
            <Metric label='PRs merged · 30d' value={merged != null ? String(merged) : '—'} />
          </div>

          <div className='ide-h2'>Workspace health</div>
          {health.length === 0 ? (
            <div className='ide-empty'>No linked repos.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {health.map((r, i) => {
                const drift = numOf(r, 'commitsBehind') ?? numOf(r, 'behindBy') ?? 0;
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
                    <IdeBadge kind={drift > 0 ? 'warn' : 'ok'}>
                      {drift > 0 ? `drift +${drift}` : 'in sync'}
                    </IdeBadge>
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
            <span>Recent runs</span>
            <span style={{flex: 1}} />
            <Link
              to={`/projects/${projectId}/runs`}
              style={{fontSize: 12, color: 'var(--ide-accent)', textDecoration: 'none'}}
            >
              View all
            </Link>
          </div>
          {recent === null ? (
            <IdeLoader label='Loading runs…' />
          ) : recent.length === 0 ? (
            <div className='ide-empty'>No runs yet in this project.</div>
          ) : (
            <div className='ide-card' style={{padding: 0}}>
              {recent.slice(0, 5).map((r, i) => {
                const sc = scoreOf(r);
                const cost = costOf(r);
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
                    <span style={{fontSize: 12, color: 'var(--ide-fg-muted)', flexShrink: 0}}>
                      {sc != null ? `${Math.round(sc)}%` : live ? 'live' : '—'}
                    </span>
                    {cost != null && (
                      <span
                        className='ide-mono'
                        style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}
                      >
                        ${cost.toFixed(2)}
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
  const score =
    scoreOf(run) ?? (run as unknown as {accuracyScore?: number}).accuracyScore ?? undefined;
  const verdict =
    score == null ? '—' : score >= 85 ? 'Shippable' : score >= 70 ? 'Needs review' : 'Failed';
  const cost = (run as unknown as {actualCostUsd?: number}).actualCostUsd ?? 0;
  const prUrl =
    (run as unknown as {scmPrUrl?: string}).scmPrUrl ||
    (run as unknown as {githubPrUrl?: string}).githubPrUrl;
  const tasks = run.tasks || [];
  const testsLabel =
    report?.testsTotal != null ? `${report.testsPassed ?? 0}/${report.testsTotal}` : '—';

  return (
    <div className='ide-card'>
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <div style={{fontSize: 28, fontWeight: 800, color: 'var(--ide-fg-bright)'}}>
          {score != null ? `${Math.round(score)}%` : '—'}
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
      <div className='ide-kv'>
        <span>Spent</span>
        <span>${cost.toFixed(2)}</span>
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
    </div>
  );
}

export function IdeProjectRuns() {
  const {projectId = ''} = useParams();
  const [sub, setSub] = useState<Sub>('runs');
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [filter, setFilter] = useState<RunFilter>('all');
  const [prs, setPrs] = useState<Array<Record<string, unknown>> | null>(null);
  const [prBusy, setPrBusy] = useState<string | null>(null);
  const [reviewOpen, setReviewOpen] = useState<string | null>(null);
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);

  useEffect(() => {
    if (!projectId) return;
    api
      .getRuns({projectId, page: 0, size: 50})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]));
  }, [projectId]);

  // Load PR list lazily when the PRs sub-tab is opened. The dashboard endpoint
  // already returns openPrs[] — cheaper than per-repo PR walks.
  useEffect(() => {
    if (!projectId || sub !== 'prs') return;
    let cancelled = false;
    if (prs === null) {
      api
        .getProjectDashboard(projectId)
        .then((d) => {
          if (cancelled) return;
          setPrs(
            ((d as unknown as {openPrs?: unknown[]}).openPrs || []) as Array<
              Record<string, unknown>
            >,
          );
        })
        .catch(() => {
          if (!cancelled) setPrs([]);
        });
    }
    return () => {
      cancelled = true;
    };
  }, [projectId, sub, prs]);

  // Selected run → fetch detail + validation report for the split-pane.
  useEffect(() => {
    if (!selectedRunId) {
      setSelectedRun(null);
      setReport(null);
      return;
    }
    let cancelled = false;
    setSelectedRun(null);
    setReport(null);
    Promise.allSettled([api.getRun(selectedRunId), api.getValidationReport(selectedRunId)]).then(
      ([r, vr]) => {
        if (cancelled) return;
        if (r.status === 'fulfilled') setSelectedRun(r.value);
        if (vr.status === 'fulfilled') setReport(vr.value);
      },
    );
    return () => {
      cancelled = true;
    };
  }, [selectedRunId]);

  async function refreshPrs() {
    if (!projectId) return;
    setPrBusy('refresh');
    setErr('');
    setMsg('');
    try {
      await api.refreshProjectPrs(projectId);
      const d = await api.getProjectDashboard(projectId);
      setPrs(
        ((d as unknown as {openPrs?: unknown[]}).openPrs || []) as Array<Record<string, unknown>>,
      );
      setMsg('PR snapshot refreshed.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'PR refresh failed.');
    } finally {
      setPrBusy(null);
    }
  }

  async function autofix(prNumber: number, repoFullName: string) {
    if (!projectId || !repoFullName) return;
    const key = `${repoFullName}#${prNumber}`;
    setPrBusy(key);
    setErr('');
    setMsg('');
    try {
      // Pull PR review comments; the response is { comments: string }.
      const res = await api.fetchPrReviewComments(projectId, prNumber, repoFullName);
      const text = (res?.comments || '').trim();
      setMsg(
        text
          ? 'PR review comments found — autofix will be dispatched.'
          : 'No actionable PR comments.',
      );
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to fetch PR comments.');
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
      else if (s === 'COMPLETED' && (score == null || score >= 85)) ship++;
      else if (s === 'COMPLETED' || s === 'PARTIAL') needs++;
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
      if (filter === 'shipped') return s === 'COMPLETED' && (score == null || score >= 85);
      if (filter === 'needs')
        return (s === 'COMPLETED' && score != null && score < 85) || s === 'PARTIAL';
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
      <div className='ide-h1'>Runs</div>

      <div className='ide-rtabs' style={{marginBottom: 12}}>
        <button
          className={`ide-rtab${sub === 'runs' ? ' active' : ''}`}
          onClick={() => setSub('runs')}
        >
          Runs <span className='ide-rtab-badge'>{runs?.length ?? 0}</span>
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

          {runs === null ? (
            <IdeLoader label='Loading runs…' />
          ) : filtered.length === 0 ? (
            <div className='ide-empty'>No runs match this filter.</div>
          ) : (
            <div
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
                  const cost = costOf(r);
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
                      <span style={{fontSize: 12, color: 'var(--ide-fg-muted)', flexShrink: 0}}>
                        {sc != null ? `${Math.round(sc)}%` : live ? 'live' : ''}
                        {cost != null && (
                          <span className='ide-mono' style={{display: 'block', textAlign: 'right'}}>
                            ${cost.toFixed(2)}
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
                      {repo && num > 0 && (
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
                        disabled={prBusy === key}
                        onClick={() => autofix(num, repo)}
                      >
                        {prBusy === key ? 'Loading…' : 'Autofix review'}
                      </button>
                    </div>
                    {reviewOpen === key && repo && num > 0 && (
                      <PrReviewPanel
                        projectId={projectId}
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
    </div>
  );
}

export function IdeProjectRepos() {
  const {projectId = ''} = useParams();
  const [repos, setRepos] = useState<Array<Record<string, unknown>> | null>(null);
  const [health, setHealth] = useState<Record<string, Record<string, unknown>>>({});
  const [available, setAvailable] = useState<Array<Record<string, unknown>> | null>(null);
  const [showLink, setShowLink] = useState(false);
  const [busy, setBusy] = useState('');
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');
  const [unlinkFor, setUnlinkFor] = useState<{repoId: string; name: string} | null>(null);

  function load() {
    api
      .getProjectRepos(projectId)
      .then((l) => setRepos(l as unknown as Array<Record<string, unknown>>))
      .catch(() => setRepos([]));
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
  }
  useEffect(() => {
    if (projectId) load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  async function openLinkPanel() {
    setShowLink(true);
    setErr('');
    if (!available) {
      try {
        const r = await api.listRepos('ALL');
        setAvailable(r as unknown as Array<Record<string, unknown>>);
      } catch (e: unknown) {
        setErr(e instanceof Error ? e.message : 'Failed to load repos.');
      }
    }
  }

  async function act(key: string, fn: () => Promise<unknown>, done = 'Done.') {
    setBusy(key);
    setMsg('');
    setErr('');
    try {
      await fn();
      setMsg(done);
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed.');
    } finally {
      setBusy('');
    }
  }

  async function link(repoId: string) {
    setBusy(`link${repoId}`);
    setMsg('');
    setErr('');
    try {
      await api.linkProjectRepo(projectId, repoId);
      setMsg('Repo linked.');
      setShowLink(false);
      load();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Link failed.');
    } finally {
      setBusy('');
    }
  }

  function confirmUnlink(repoId: string, name: string) {
    setUnlinkFor({repoId, name});
  }

  return (
    <div className='ide-pane'>
      <ProjectTabs projectId={projectId} />
      <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
        <div className='ide-h1' style={{margin: 0}}>
          Repos
        </div>
        <span style={{flex: 1}} />
        <button className='ide-btn' onClick={openLinkPanel}>
          + Link repo
        </button>
      </div>

      {msg && <Notice msg={msg} kind='info' />}
      {err && <Notice msg={err} kind='bad' />}

      {showLink && (
        <div className='ide-card' style={{margin: '12px 0'}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Available repos
          </div>
          {available === null ? (
            <IdeLoader label='Loading repos…' />
          ) : available.length === 0 ? (
            <div className='ide-empty'>
              No connected repos yet. Connect GitHub from{' '}
              <Link to='/integrations' style={{color: 'var(--ide-accent)'}}>
                Integrations
              </Link>
              .
            </div>
          ) : (
            <div style={{display: 'flex', flexDirection: 'column', gap: 6}}>
              {available.map((r) => {
                const rId = repoIdFor(r);
                const linked = (repos || []).some((p) => repoIdFor(p) === rId);
                return (
                  <div
                    key={rId}
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
                      {repoDisplayName(r)}
                    </span>
                    <button
                      className='ide-btn ghost'
                      style={{padding: '3px 12px'}}
                      disabled={linked || busy === `link${rId}`}
                      onClick={() => link(rId)}
                    >
                      {linked ? 'Linked' : busy === `link${rId}` ? '…' : 'Link'}
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

      {repos === null ? (
        <IdeLoader label='Loading repos…' />
      ) : repos.length === 0 ? (
        <div className='ide-empty'>No repos linked. Click “Link repo” above to add one.</div>
      ) : (
        <div className='ide-grid'>
          {repos.map((r) => {
            const id = repoIdFor(r);
            const name = repoDisplayName(r);
            const h = health[id] || health[name] || {};
            const drift =
              numOf(h, 'commitsBehind') ?? numOf(h, 'behindBy') ?? numOf(r, 'commitsBehind') ?? 0;
            const wh = (h as {webhookInstalled?: boolean}).webhookInstalled;
            const open = numOf(h, 'openPrCount') ?? numOf(h, 'openPrs') ?? 0;
            const language = stackLanguage(r);
            const sizeKb = numOf(r, 'sizeKb');
            const fullName = (r as {githubRepoFullName?: string}).githubRepoFullName || name;
            const head =
              (r as {headSha?: string}).headSha ||
              (h as {currentOriginSha?: string}).currentOriginSha;
            const branch =
              (r as {defaultBranch?: string}).defaultBranch ||
              (h as {defaultBranch?: string}).defaultBranch ||
              'main';
            const archStatus =
              (r as {architectureStatus?: string}).architectureStatus ||
              (h as {architectureStatus?: string}).architectureStatus ||
              ((h as {isStale?: boolean}).isStale === true ? 'STALE' : undefined);
            return (
              <div key={id} className='ide-card'>
                <div style={{fontWeight: 600, color: 'var(--ide-fg-bright)', marginBottom: 8}}>
                  ▤ {name}
                </div>
                <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                  <IdeBadge kind={drift > 0 ? 'warn' : 'ok'}>
                    {drift > 0 ? `drift +${drift}` : 'in sync'}
                  </IdeBadge>
                  <IdeBadge kind={wh ? 'ok' : 'warn'}>{wh ? 'webhook ✓' : 'no webhook'}</IdeBadge>
                  {fullName.includes('/') ? (
                    <a
                      href={`https://github.com/${fullName}/pulls`}
                      target='_blank'
                      rel='noopener noreferrer'
                      style={{textDecoration: 'none'}}
                      title='View pull requests on GitHub'
                    >
                      <IdeBadge kind='ok'>{open} open PR ↗</IdeBadge>
                    </a>
                  ) : (
                    <IdeBadge kind='ok'>{open} open PR</IdeBadge>
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
                <div className='ide-kv'>
                  <span>HEAD</span>
                  <span className='ide-mono'>
                    {head ? `${String(head).slice(0, 7)} · ${branch}` : branch}
                  </span>
                </div>
                {archStatus && (
                  <div className='ide-kv'>
                    <span>Architecture</span>
                    <span
                      style={{
                        color: String(archStatus).includes('STALE')
                          ? 'var(--ide-yellow)'
                          : 'var(--ide-green)',
                      }}
                    >
                      {String(archStatus)}
                    </span>
                  </div>
                )}
                <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 10}}>
                  <Link
                    to={`/projects/${projectId}/architecture`}
                    className='ide-btn ghost'
                    style={{padding: '4px 10px', textDecoration: 'none'}}
                  >
                    View architecture
                  </Link>
                  <button
                    className='ide-btn ghost'
                    style={{padding: '4px 10px'}}
                    disabled={busy === `sync${id}`}
                    title='Fast metadata refresh (stack, HEAD SHA) — no code regeneration'
                    onClick={() => act(`sync${id}`, () => api.syncRepo(id), 'Synced.')}
                  >
                    {busy === `sync${id}` ? '…' : 'Sync'}
                  </button>
                  <button
                    className='ide-btn ghost'
                    style={{padding: '4px 10px'}}
                    disabled={busy === `regen${id}`}
                    title='Regenerate this repo codemap + architecture (LLM call — can take a minute)'
                    onClick={() =>
                      act(
                        `regen${id}`,
                        () => api.regenerateRepoArchitecture(projectId, id),
                        'Architecture regenerated.',
                      )
                    }
                  >
                    {busy === `regen${id}` ? '…' : 'Regenerate'}
                  </button>
                  {!wh && (
                    <button
                      className='ide-btn ghost'
                      style={{padding: '4px 10px'}}
                      disabled={busy === `wh${id}`}
                      onClick={() =>
                        act(`wh${id}`, () => api.installRepoWebhook(id), 'Webhook installed.')
                      }
                    >
                      {busy === `wh${id}` ? '…' : 'Install webhook'}
                    </button>
                  )}
                  <button
                    className='ide-btn ghost'
                    style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                    disabled={busy === `unlink${id}`}
                    onClick={() => confirmUnlink(id, name)}
                  >
                    {busy === `unlink${id}` ? '…' : 'Unlink'}
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
        busy={!!unlinkFor && busy === `unlink${unlinkFor.repoId}`}
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
