import {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar} from '@/signal/components/Chrome';
import {Feed, Row, Card, TabsStrip, Button, Chip, StatusPill} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {ScoreCard, PrCard, TaskLine} from '@/signal/components/Compose';
import {runTitleFor} from '@/signal/lib/runUtils';
import * as api from '@/lib/api';

type Sub = 'runs' | 'prs';
type RunFilter = 'all' | 'live' | 'shipped' | 'needs' | 'failed';

function relTime(input?: string): string {
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

function InlineRunDetailPane({
  run,
  report,
}: {
  run: api.RunDetail;
  report: api.ValidationReportData | null;
}) {
  const score =
    (run as unknown as {verifiedScore?: number}).verifiedScore ??
    (run as unknown as {accuracyScore?: number}).accuracyScore ??
    0;
  const verdict = score >= 90 ? 'Shippable' : score >= 70 ? 'Needs review' : 'Failed';
  const cost = (run as unknown as {actualCostUsd?: number}).actualCostUsd ?? 0;
  const prUrl =
    (run as unknown as {scmPrUrl?: string; githubPrUrl?: string}).scmPrUrl ||
    (run as unknown as {githubPrUrl?: string}).githubPrUrl;
  const tasks = run.tasks || [];
  const reportAny = (report || {}) as unknown as {
    buildStatus?: string;
    testsPassed?: number;
    testsTotal?: number;
    reviewerStatus?: string;
    securityStatus?: string;
  };

  return (
    <>
      <ScoreCard
        verdict={verdict}
        score={Math.round(score)}
        label={`R-${run.runId.slice(0, 6)} · ${run.status?.toLowerCase() || '—'}`}
        cells={[
          {
            v:
              reportAny.testsTotal != null
                ? `${reportAny.testsPassed ?? 0}/${reportAny.testsTotal}`
                : '—',
            l: 'Tests',
          },
          {v: `$${cost.toFixed(2)}`, l: 'Spent'},
          {v: prUrl ? 'open' : '—', l: 'PR'},
        ]}
      />
      {tasks.length > 0 && (
        <Card style={{padding: 0, marginTop: 10}}>
          {tasks.slice(0, 6).map((t, i) => {
            const tAny = t as unknown as {
              taskId?: string;
              title?: string;
              status?: string;
              complexity?: string;
            };
            return (
              <TaskLine
                key={tAny.taskId || i}
                status={
                  tAny.status === 'COMPLETED' || tAny.status === 'DONE'
                    ? 'done'
                    : tAny.status === 'EXECUTING' || tAny.status === 'RUNNING'
                      ? 'live'
                      : 'todo'
                }
                title={tAny.title || `Task ${i + 1}`}
                tag={tAny.complexity || 'AUTO'}
              />
            );
          })}
        </Card>
      )}
      {prUrl && (
        <PrCard
          label='PR · OPEN'
          title={run.userRequest?.slice(0, 80) || 'Pull request'}
          repo={prUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/pull\//, '#')}
          ciStatus={<span className='ci-ok'>● open</span>}
          actions={
            <button type='button' onClick={() => window.open(prUrl, '_blank', 'noopener')}>
              Open on GitHub
            </button>
          }
        />
      )}
      <div style={{display: 'flex', gap: 6, marginTop: 10}}>
        <Button kind='secondary' block sm to={`/result/${run.runId}`}>
          Open full page
        </Button>
        {(run.status === 'CLASSIFYING' ||
          run.status === 'PLANNING' ||
          run.status === 'EXECUTING' ||
          run.status === 'AWAITING_APPROVAL' ||
          run.status === 'RUNNING') && (
          <Button kind='primary' block sm to={`/live/${run.runId}`}>
            Watch live
          </Button>
        )}
      </div>
    </>
  );
}

function dotFor(s?: string): 'live' | 'mint' | 'coral' | 'signal' | 'lavender' {
  const x = (s || '').toUpperCase();
  if (
    x === 'CLASSIFYING' ||
    x === 'PLANNING' ||
    x === 'EXECUTING' ||
    x === 'AWAITING_APPROVAL' ||
    x === 'RUNNING'
  )
    return 'live';
  if (x === 'COMPLETED' || x === 'MERGED' || x === 'OPEN') return 'mint';
  if (x === 'FAILED' || x === 'CANCELLED' || x === 'CLOSED') return 'coral';
  if (x === 'PARTIAL') return 'signal';
  return 'lavender';
}

export default function ProjectRunsPage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const [sub, setSub] = useState<Sub>('runs');
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [filter, setFilter] = useState<RunFilter>('all');
  const [prs, setPrs] = useState<unknown[] | null>(null);
  const [prBusy, setPrBusy] = useState<string | null>(null);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');
  // Inline run detail (split-pane) — v3 parity. Clicking a run in the list
  // selects it and shows its detail to the right of the list instead of
  // navigating away. We still expose "Open full page" so the user can
  // jump to /result/{id} for the full run-detail experience.
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [selectedRun, setSelectedRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);

  useEffect(() => {
    if (!selectedRunId) {
      setSelectedRun(null);
      setReport(null);
      return;
    }
    let cancelled = false;
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

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api
      .getRuns({projectId: id, page: 0, size: 50})
      .then((p) => {
        if (!cancelled) setRuns(p.content);
      })
      .catch(() => {
        if (!cancelled) setErr('Failed to load runs.');
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  useEffect(() => {
    if (!id || sub !== 'prs') return;
    let cancelled = false;
    // Use the dashboard endpoint — it returns openPrs[] + recent PR activity.
    // Cheaper than per-repo PR walks and matches what v3 uses.
    api
      .getProjectDashboard(id)
      .then((d) => {
        if (cancelled) return;
        const list = ((d as unknown as {openPrs?: unknown[]}).openPrs || []) as unknown[];
        setPrs(list);
      })
      .catch(() => {
        if (!cancelled) setPrs([]);
      });
    return () => {
      cancelled = true;
    };
  }, [id, sub]);

  async function refreshPrs() {
    if (!id) return;
    setPrBusy('refresh');
    setErr('');
    setMsg('');
    try {
      await api.refreshProjectPrs(id);
      const d = await api.getProjectDashboard(id);
      setPrs(((d as unknown as {openPrs?: unknown[]}).openPrs || []) as unknown[]);
      setMsg('PR snapshot refreshed.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'PR refresh failed.');
    } finally {
      setPrBusy(null);
    }
  }

  async function autofix(prNumber: number, repoFullName: string) {
    if (!id) return;
    const key = `${repoFullName}#${prNumber}`;
    setPrBusy(key);
    setErr('');
    try {
      // Pull PR review comments and trigger autofix per dispatch endpoint.
      // The v3 pattern: GET comments → POST autofix with reviewId.
      const comments = await api.fetchPrReviewComments(id, prNumber, repoFullName);
      const count = Array.isArray(comments) ? comments.length : 0;
      setMsg(
        count > 0
          ? `${count} PR comment${count === 1 ? '' : 's'} found — autofix will be dispatched.`
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
      const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
      if (
        s === 'CLASSIFYING' ||
        s === 'PLANNING' ||
        s === 'EXECUTING' ||
        s === 'AWAITING_APPROVAL' ||
        s === 'RUNNING'
      )
        live++;
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
      const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
      if (filter === 'all') return true;
      if (filter === 'live')
        return (
          s === 'CLASSIFYING' ||
          s === 'PLANNING' ||
          s === 'EXECUTING' ||
          s === 'AWAITING_APPROVAL' ||
          s === 'RUNNING'
        );
      if (filter === 'shipped') return s === 'COMPLETED' && (score == null || score >= 85);
      if (filter === 'needs')
        return (s === 'COMPLETED' && score != null && score < 85) || s === 'PARTIAL';
      if (filter === 'failed') return s === 'FAILED' || s === 'CANCELLED';
      return true;
    });
  }, [runs, filter]);

  const projectName = project?.name || 'project';

  return (
    <>
      <TopBar
        title='Runs'
        sub={`${projectName} · ${runs?.length ?? 0} ${(runs?.length ?? 0) === 1 ? 'run' : 'runs'}`}
        back
        backTo={`/projects/${id}`}
      />
      <Feed>
        <ProjectTabs active='runs' />
        <TabsStrip
          items={[
            {
              label: `Runs · ${runs?.length ?? 0}`,
              active: sub === 'runs',
              onClick: () => setSub('runs'),
            },
            {
              label: `PRs · ${prs?.length ?? '—'}`,
              active: sub === 'prs',
              onClick: () => setSub('prs'),
            },
          ]}
        />

        {err && (
          <div
            style={{
              background: 'var(--signal-coral-soft)',
              color: 'var(--signal-coral-text)',
              border: '1px solid var(--signal-coral)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {err}
          </div>
        )}
        {msg && (
          <div
            style={{
              background: 'var(--signal-mint-soft)',
              color: 'var(--signal-mint-text-on-light)',
              border: '1px solid var(--signal-mint)',
              borderRadius: 10,
              padding: '10px 14px',
              marginBottom: 12,
              fontSize: 13,
            }}
          >
            {msg}
          </div>
        )}

        {sub === 'runs' && (
          <>
            <TabsStrip
              items={[
                {
                  label: `All · ${runs?.length ?? 0}`,
                  active: filter === 'all',
                  onClick: () => setFilter('all'),
                },
                {
                  label: `Live · ${counts.live}`,
                  active: filter === 'live',
                  onClick: () => setFilter('live'),
                },
                {
                  label: `Shipped · ${counts.ship}`,
                  active: filter === 'shipped',
                  onClick: () => setFilter('shipped'),
                },
                {
                  label: `Needs · ${counts.needs}`,
                  active: filter === 'needs',
                  onClick: () => setFilter('needs'),
                },
                {
                  label: `Failed · ${counts.fail}`,
                  active: filter === 'failed',
                  onClick: () => setFilter('failed'),
                },
              ]}
            />

            {runs === null ? (
              <LoadingState label='Loading runs…' />
            ) : filtered.length === 0 ? (
              <Card>
                <p
                  style={{
                    fontSize: 14,
                    color: 'var(--signal-text-dim)',
                    margin: 0,
                    lineHeight: 1.5,
                  }}
                >
                  No runs match this filter.{' '}
                  <Button kind='ghost' sm onClick={() => setFilter('all')}>
                    Show all
                  </Button>
                </p>
              </Card>
            ) : (
              <div className='signal-grid' style={{gridTemplateColumns: 'minmax(0, 1fr)', gap: 12}}>
                {/* On wide screens, switch to a 2-column split (list left, detail right).
                    On mobile we keep the list above and the detail below to avoid
                    horizontal scroll. CSS in signal.css :: `.signal-grid.split-runs` */}
                <div className='signal-split-runs'>
                  <div className='split-list'>
                    {filtered.map((r) => {
                      const score = (r as unknown as {verifiedScore?: number}).verifiedScore;
                      const cost = (r as unknown as {actualCostUsd?: number}).actualCostUsd;
                      const s = (r.status || '').toUpperCase();
                      const isLive =
                        s === 'CLASSIFYING' ||
                        s === 'PLANNING' ||
                        s === 'EXECUTING' ||
                        s === 'AWAITING_APPROVAL' ||
                        s === 'RUNNING';
                      const isSelected = selectedRunId === r.runId;
                      return (
                        <div
                          key={r.runId}
                          className='signal-row'
                          style={{
                            cursor: 'pointer',
                            borderColor: isSelected ? 'var(--signal-ink)' : undefined,
                          }}
                          onClick={() => setSelectedRunId(r.runId)}
                        >
                          <div className={`pill-dot ${dotFor(s)}`} />
                          <div style={{minWidth: 0}}>
                            <div className='row-title'>
                              {/* SDET 2026-05-16 (BUG-022) — shared runTitleFor helper trims
                                  empty / whitespace-only runTitle before falling back. */}
                              {runTitleFor(r)}
                            </div>
                            <div className='row-meta'>
                              <span className='mono'>R-{r.runId.slice(0, 6)}</span>
                              <span>·</span>
                              <span>
                                {relTime((r as unknown as {startedAt?: string}).startedAt)}
                              </span>
                            </div>
                          </div>
                          <div
                            className={`trailing ${score == null ? '' : score >= 85 ? 'good' : score >= 70 ? 'warn' : 'fail'}`}
                          >
                            {score != null
                              ? `${Math.round(score)}%`
                              : isLive
                                ? 'live'
                                : s.toLowerCase()}
                            {cost != null && <span className='lbl'>${cost.toFixed(2)}</span>}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                  <div className='split-detail'>
                    {!selectedRunId ? (
                      <Card>
                        <p
                          style={{
                            fontSize: 13.5,
                            color: 'var(--signal-text-dim)',
                            margin: 0,
                            lineHeight: 1.5,
                          }}
                        >
                          Pick a run on the left to see its score, tasks, validation report, and PR.
                        </p>
                      </Card>
                    ) : !selectedRun ? (
                      <LoadingState label='Loading run…' />
                    ) : (
                      <InlineRunDetailPane run={selectedRun} report={report} />
                    )}
                  </div>
                </div>
              </div>
            )}
          </>
        )}

        {sub === 'prs' && (
          <>
            <div style={{display: 'flex', justifyContent: 'flex-end', marginBottom: 10}}>
              <Button kind='secondary' sm onClick={refreshPrs} disabled={prBusy === 'refresh'}>
                {prBusy === 'refresh' ? 'Refreshing…' : 'Refresh PR snapshot'}
              </Button>
            </div>
            {prs === null ? (
              <LoadingState label='Loading pull requests…' />
            ) : prs.length === 0 ? (
              <Card>
                <p
                  style={{
                    fontSize: 14,
                    color: 'var(--signal-text-dim)',
                    margin: 0,
                    lineHeight: 1.5,
                  }}
                >
                  No open pull requests in this project. PRs auto-appear here when a run pushes its
                  branch.
                </p>
              </Card>
            ) : (
              prs.map((pr, i) => {
                const p = pr as {
                  prNumber?: number;
                  repoFullName?: string;
                  title?: string;
                  status?: string;
                  state?: string;
                  prUrl?: string;
                  htmlUrl?: string;
                  additions?: number;
                  deletions?: number;
                  checksStatus?: string;
                  reviewVerdict?: string;
                  commentsCount?: number;
                  mergedAt?: string;
                  openedAt?: string;
                };
                const status = (p.status || p.state || 'OPEN').toUpperCase();
                const url = p.prUrl || p.htmlUrl;
                const repo = p.repoFullName || '';
                const key = `${repo}#${p.prNumber}`;
                return (
                  <Card key={`${key}-${i}`}>
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'flex-start',
                        gap: 12,
                        marginBottom: 10,
                      }}
                    >
                      <div style={{flex: 1, minWidth: 0}}>
                        <div
                          style={{
                            fontSize: 11,
                            fontWeight: 700,
                            letterSpacing: '0.12em',
                            color:
                              status === 'MERGED'
                                ? 'var(--signal-mint-text-on-light)'
                                : 'var(--signal-coral)',
                            marginBottom: 4,
                            textTransform: 'uppercase',
                          }}
                        >
                          PR · {status}
                          {p.mergedAt
                            ? ` · ${relTime(p.mergedAt)}`
                            : p.openedAt
                              ? ` · ${relTime(p.openedAt)}`
                              : ''}
                        </div>
                        <div
                          style={{
                            fontSize: 15,
                            fontWeight: 600,
                            marginBottom: 4,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {p.title || `PR #${p.prNumber}`}
                        </div>
                        <div
                          style={{
                            fontFamily: "'JetBrains Mono', monospace",
                            fontSize: 11.5,
                            color: 'var(--signal-text-dim)',
                          }}
                        >
                          {repo}#{p.prNumber}
                        </div>
                      </div>
                      <StatusPill
                        kind={
                          status === 'OPEN' ? 'live' : status === 'MERGED' ? 'shipped' : 'failed'
                        }
                      >
                        {status}
                      </StatusPill>
                    </div>
                    <div
                      style={{
                        display: 'flex',
                        gap: 8,
                        alignItems: 'center',
                        flexWrap: 'wrap',
                        marginBottom: 10,
                        fontSize: 12,
                        color: 'var(--signal-text-dim)',
                      }}
                    >
                      {p.additions != null && <Chip>+{p.additions}</Chip>}
                      {p.deletions != null && <Chip>−{p.deletions}</Chip>}
                      {p.checksStatus && (
                        <Chip
                          kind={
                            p.checksStatus === 'SUCCESS'
                              ? 'mint'
                              : p.checksStatus === 'FAILURE'
                                ? 'coral'
                                : 'default'
                          }
                        >
                          ● CI {p.checksStatus.toLowerCase()}
                        </Chip>
                      )}
                      {p.reviewVerdict && <Chip>{p.reviewVerdict}</Chip>}
                      {p.commentsCount != null && p.commentsCount > 0 && (
                        <Chip>
                          {p.commentsCount} comment{p.commentsCount === 1 ? '' : 's'}
                        </Chip>
                      )}
                    </div>
                    <div style={{display: 'flex', gap: 6}}>
                      {url && (
                        <Button
                          kind='secondary'
                          sm
                          onClick={() => window.open(url, '_blank', 'noopener')}
                        >
                          Open on GitHub
                        </Button>
                      )}
                      <Button
                        kind='secondary'
                        sm
                        disabled={prBusy === key}
                        onClick={() => autofix(p.prNumber || 0, repo)}
                      >
                        {prBusy === key ? 'Loading…' : 'Autofix review'}
                      </Button>
                    </div>
                  </Card>
                );
              })
            )}
          </>
        )}
      </Feed>
    </>
  );
}
