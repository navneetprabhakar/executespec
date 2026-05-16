import {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, StatusPill, TabsStrip} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {ProjectTabs} from './ProjectTabs';
import {FileTree} from '@/signal/components/FileTree';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {parseCodemap, type CodemapSummary} from '@/signal/lib/codemapParse';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';
/* useProjectMeta delivers project name from cached project list quickly,
   and refreshes from /api/projects/{id} in the background. */

// 2026-05-16 (feedback) — added 'browse' as the FIRST sub-tab so users land
// on "see the actual code" by default. The codemap is the cheap+available
// source of truth here: parsed client-side via parseCodemap to avoid a new
// backend endpoint. When the codemap is missing, the panel prompts to
// regenerate architecture (which re-runs the codemap step).
type Sub = 'browse' | 'debt' | 'review' | 'health' | 'standards' | 'docs';

export default function ProjectCodePage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const [sub, setSub] = useState<Sub>('browse');
  // Per-repo codemap state for the Browse sub-tab. Loaded lazily so the
  // other sub-tabs don't pay for the fetch when they're the entry point.
  const [browseRepoId, setBrowseRepoId] = useState<string | null>(null);
  const [codemapByRepo, setCodemapByRepo] = useState<Record<string, CodemapSummary | 'missing'>>(
    {},
  );
  const [codemapLoading, setCodemapLoading] = useState<Record<string, boolean>>({});
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null);
  const [debt, setDebt] = useState<api.TechDebtReport | null>(null);
  const [docs, setDocs] = useState<api.GeneratedDocRow[] | null>(null);
  // ProjectCodePage no longer fetches the project itself — useProjectMeta
  // (called in render below) handles that. Repos are fetched here directly.
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [reviews, setReviews] = useState<Record<string, unknown> | null>(null);
  // P1 regression fixes: workspace health + org standards compliance.
  const [health, setHealth] = useState<api.WorkspaceHealthReport | null>(null);
  const [standards, setStandards] = useState<api.ComplianceReport | null>(null);
  const user = useAuthStore((s) => s.user);
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  async function refresh() {
    if (!id) return;
    const [d, rp] = await Promise.all([
      api.getTechDebt(id, 30).catch(() => null),
      api.getProjectRepos(id).catch(() => [] as unknown[] as Array<Record<string, unknown>>),
    ]);
    setDebt(d);
    setRepos(rp as unknown as Array<Record<string, unknown>>);
    try {
      const explanation = await api.getProjectDoc(id, 'EXPLANATION').catch(() => null);
      const setup = await api.getProjectDoc(id, 'SETUP').catch(() => null);
      const list: api.GeneratedDocRow[] = [];
      if (explanation)
        list.push({docType: 'EXPLANATION', ...(explanation as object)} as api.GeneratedDocRow);
      if (setup) list.push({docType: 'SETUP', ...(setup as object)} as api.GeneratedDocRow);
      setDocs(list);
    } catch {
      setDocs([]);
    }
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Workspace health — per-repo coverage/lint/build pass status. Lazy on tab open.
  useEffect(() => {
    if (sub !== 'health' || !id || health != null) return;
    api
      .getWorkspaceHealth(id)
      .then(setHealth)
      .catch(() => setHealth(null));
  }, [sub, id, health]);

  // Org standards compliance — TEAM+ feature. Loads on tab open.
  useEffect(() => {
    if (sub !== 'standards' || !id || standards != null) return;
    const orgId = user?.orgId;
    if (!orgId) return;
    api
      .getStandardsReport(id, orgId, 30)
      .then(setStandards)
      .catch(() => setStandards({violations: [], summary: {}} as unknown as api.ComplianceReport));
  }, [sub, id, standards, user?.orgId]);

  // Default-select the first repo when the user opens Browse so the panel
  // isn't empty on first load. Pure UI state — doesn't fetch anything yet.
  useEffect(() => {
    if (sub === 'browse' && !browseRepoId && repos.length > 0) {
      setBrowseRepoId(repoIdFor(repos[0]));
    }
  }, [sub, repos, browseRepoId]);

  // Lazy-fetch the codemap for whichever repo is selected. Cached per-repo
  // so flipping between repos in the same session pays once.
  useEffect(() => {
    if (sub !== 'browse' || !id || !browseRepoId) return;
    if (codemapByRepo[browseRepoId] || codemapLoading[browseRepoId]) return;
    setCodemapLoading((m) => ({...m, [browseRepoId]: true}));
    api
      .getRepoCodeMap(id, browseRepoId)
      .then((res) => {
        const parsed = parseCodemap(res.content);
        setCodemapByRepo((m) => ({...m, [browseRepoId]: parsed}));
      })
      .catch(() => {
        setCodemapByRepo((m) => ({...m, [browseRepoId]: 'missing'}));
      })
      .finally(() => {
        setCodemapLoading((m) => ({...m, [browseRepoId]: false}));
      });
  }, [sub, id, browseRepoId, codemapByRepo, codemapLoading]);

  // Reset selected file when the active repo changes — otherwise switching
  // repos leaves a stale path highlighted that doesn't exist in the new tree.
  useEffect(() => {
    setSelectedFilePath(null);
  }, [browseRepoId]);

  useEffect(() => {
    if (sub !== 'review' || !id || repos.length === 0) return;
    // Per-repo latest code review scan. We fetch each in parallel and key by
    // repoId so the panel can render side-by-side severity chips.
    let cancelled = false;
    Promise.all(
      repos.map(async (r) => {
        const repoId = repoIdFor(r);
        const repoName = repoDisplayName(r);
        try {
          // Backend's /api/code-reviews?repo= expects the GitHub slug (e.g.
          // owner/repo) when present; fall back to the same display we show
          // the user. The repoDisplayName helper already prefers slug.
          const review = await fetch(
            `/api/code-reviews?projectId=${id}&latest=true&repo=${encodeURIComponent(repoName)}`,
            {
              headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
            },
          ).then((res) => (res.ok ? res.json() : null));
          return [repoId, {repoName, review}];
        } catch {
          return [repoId, {repoName, review: null}];
        }
      }),
    ).then((entries) => {
      if (!cancelled) setReviews(Object.fromEntries(entries));
    });
    return () => {
      cancelled = true;
    };
  }, [sub, id, repos]);

  async function autofixCategory(category: string) {
    setBusy(category);
    setErr('');
    try {
      const res = await api.dispatchTechDebtFix(id, category);
      const runId = (res as unknown as {runId?: string}).runId;
      setMsg(runId ? `Autofix dispatched: R-${runId.slice(0, 6)}` : 'Autofix dispatched.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Autofix failed.');
    } finally {
      setBusy(null);
    }
  }

  async function rescanReview(repoId: string) {
    setBusy(`scan-${repoId}`);
    setErr('');
    setMsg('');
    try {
      const r = await fetch('/api/code-reviews/submit', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`,
        },
        body: JSON.stringify({projectId: id, repoId}),
      });
      if (!r.ok) throw new Error(`Scan failed (${r.status}).`);
      setMsg('Scan queued. Results appear here when the scan completes.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Scan failed.');
    } finally {
      setBusy(null);
    }
  }

  async function generateDoc(t: 'EXPLANATION' | 'SETUP') {
    setBusy(`gen-${t}`);
    setErr('');
    try {
      await api.generateProjectDoc(id, t);
      setMsg(`${t.toLowerCase()} doc regeneration started.`);
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Doc generation failed.');
    } finally {
      setBusy(null);
    }
  }

  async function downloadDoc(t: 'EXPLANATION' | 'SETUP') {
    setBusy(`dl-${t}`);
    try {
      await api.downloadProjectDoc(id, t);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Download failed.');
    } finally {
      setBusy(null);
    }
  }

  const cats =
    (
      debt as unknown as {
        categories?: Array<{
          name?: string;
          count?: number;
          criticalCount?: number;
          majorCount?: number;
          minorCount?: number;
          description?: string;
        }>;
      }
    )?.categories || [];
  const projectName = project?.name || 'project';

  return (
    <>
      <TopBar
        title='Code & quality'
        sub={`${projectName} · ${debt && (debt as unknown as {lastScannedAt?: string}).lastScannedAt ? `last scan ${new Date((debt as unknown as {lastScannedAt: string}).lastScannedAt).toLocaleString()}` : 'no scan yet'}`}
        back
        backTo={`/projects/${id}`}
      />
      <Feed>
        <ProjectTabs active='code' />
        <TabsStrip
          items={[
            // Browse comes first so "see the actual code" is the default
            // entry point — feedback 2026-05-16.
            {
              label: `Browse · ${repos.length}`,
              active: sub === 'browse',
              onClick: () => setSub('browse'),
            },
            {
              label: `Tech debt · ${cats.length}`,
              active: sub === 'debt',
              onClick: () => setSub('debt'),
            },
            {label: 'Code review', active: sub === 'review', onClick: () => setSub('review')},
            {label: 'Health', active: sub === 'health', onClick: () => setSub('health')},
            {label: 'Standards', active: sub === 'standards', onClick: () => setSub('standards')},
            {
              label: `Docs · ${docs?.length ?? 0}`,
              active: sub === 'docs',
              onClick: () => setSub('docs'),
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

        {sub === 'browse' && (
          <BrowseTab
            id={id}
            repos={repos}
            browseRepoId={browseRepoId}
            setBrowseRepoId={setBrowseRepoId}
            codemap={browseRepoId ? codemapByRepo[browseRepoId] : undefined}
            loading={browseRepoId ? !!codemapLoading[browseRepoId] : false}
            selectedFilePath={selectedFilePath}
            setSelectedFilePath={setSelectedFilePath}
          />
        )}

        {sub === 'debt' &&
          (debt == null ? (
            <LoadingState label='Loading tech debt…' />
          ) : cats.length === 0 ? (
            <Card>
              <p
                style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}
              >
                No tech debt detected. Run a code-review scan to surface issues.
              </p>
            </Card>
          ) : (
            cats.map((c, i) => {
              const name = c.name || `Category ${i + 1}`;
              const crit = c.criticalCount ?? 0;
              const maj = c.majorCount ?? 0;
              const min = c.minorCount ?? 0;
              const total = c.count ?? crit + maj + min;
              return (
                <Card key={name} style={{padding: 0}}>
                  <div
                    style={{
                      padding: '14px 16px',
                      borderBottom: '1px solid var(--signal-hairline)',
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      gap: 10,
                    }}
                  >
                    <div style={{minWidth: 0, flex: 1}}>
                      <div
                        style={{
                          fontSize: 14.5,
                          fontWeight: 600,
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                        }}
                      >
                        {name}
                      </div>
                      <div
                        style={{
                          fontSize: 12,
                          color: 'var(--signal-text-dim)',
                          marginTop: 2,
                          whiteSpace: 'nowrap',
                          overflow: 'hidden',
                          textOverflow: 'ellipsis',
                        }}
                      >
                        {c.description || `${total} issues`}
                      </div>
                    </div>
                    <div style={{display: 'flex', gap: 4, flexShrink: 0}}>
                      {crit > 0 && <StatusPill kind='failed'>{crit} CRIT</StatusPill>}
                      {maj > 0 && <StatusPill kind='needs'>{maj} MAJ</StatusPill>}
                      {min > 0 && <StatusPill>{min} MIN</StatusPill>}
                    </div>
                  </div>
                  <div style={{padding: '12px 16px'}}>
                    <Button
                      kind='primary'
                      sm
                      block
                      disabled={busy === name}
                      onClick={() => autofixCategory(name)}
                    >
                      {busy === name ? 'Dispatching…' : `Autofix ${total} →`}
                    </Button>
                  </div>
                </Card>
              );
            })
          ))}

        {sub === 'review' &&
          (repos.length === 0 ? (
            <Card>
              <p
                style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}
              >
                No repos linked.{' '}
                <a href={`/projects/${id}/repos`} style={{color: 'var(--signal-coral)'}}>
                  Link one →
                </a>{' '}
                to enable per-repo code review.
              </p>
            </Card>
          ) : reviews == null ? (
            <LoadingState label='Loading scan results…' />
          ) : (
            <>
              <SectionHeader>Per-repo scan</SectionHeader>
              {Object.entries(reviews).map(([repoId, val]) => {
                const v = val as {
                  repoName?: string;
                  review?: {
                    id?: string;
                    critical?: number;
                    major?: number;
                    minor?: number;
                    info?: number;
                    scannedAt?: string;
                    reportUrl?: string;
                  };
                };
                const r = v.review;
                return (
                  <Card key={repoId} style={{marginBottom: 10}}>
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'baseline',
                        marginBottom: 10,
                        gap: 10,
                      }}
                    >
                      <div style={{minWidth: 0, flex: 1}}>
                        <div
                          style={{
                            fontSize: 14.5,
                            fontWeight: 600,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {v.repoName}
                        </div>
                        <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
                          {r?.scannedAt
                            ? `Last scan ${new Date(r.scannedAt).toLocaleString()}`
                            : 'No scan yet'}
                        </div>
                      </div>
                      <Button
                        kind='secondary'
                        sm
                        disabled={busy === `scan-${repoId}`}
                        onClick={() => rescanReview(repoId)}
                      >
                        {busy === `scan-${repoId}` ? 'Queuing…' : 'New scan'}
                      </Button>
                    </div>
                    {r && (
                      <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                        {(r.critical ?? 0) > 0 && (
                          <StatusPill kind='failed'>{r.critical} CRITICAL</StatusPill>
                        )}
                        {(r.major ?? 0) > 0 && (
                          <StatusPill kind='needs'>{r.major} MAJOR</StatusPill>
                        )}
                        {(r.minor ?? 0) > 0 && <StatusPill>{r.minor} MINOR</StatusPill>}
                        {(r.info ?? 0) > 0 && <StatusPill>{r.info} INFO</StatusPill>}
                        {!r.critical && !r.major && !r.minor && !r.info && (
                          <StatusPill kind='shipped'>CLEAN</StatusPill>
                        )}
                      </div>
                    )}
                    {r?.reportUrl && (
                      <Button
                        kind='ghost'
                        sm
                        block
                        onClick={() => window.open(r.reportUrl, '_blank', 'noopener')}
                      >
                        Download report (Markdown)
                      </Button>
                    )}
                  </Card>
                );
              })}
            </>
          ))}

        {sub === 'health' &&
          (health == null ? (
            <LoadingState label='Loading workspace health…' />
          ) : (health.repos || []).length === 0 ? (
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                No repos linked.{' '}
                <a href={`/projects/${id}/repos`} style={{color: 'var(--signal-coral)'}}>
                  Link a repo →
                </a>
              </p>
            </Card>
          ) : (
            <>
              <SectionHeader>Per-repo workspace health</SectionHeader>
              {health.repos.map((r) => {
                const rAny = r as unknown as {
                  repoId?: string;
                  name?: string;
                  headSha?: string;
                  commitsBehind?: number;
                  webhookInstalled?: boolean;
                  openPrs?: number;
                  coveragePct?: number;
                  buildStatus?: string;
                  lintStatus?: string;
                  lastAnalysedAt?: string;
                };
                const cov = rAny.coveragePct != null ? `${rAny.coveragePct.toFixed(1)}%` : '—';
                return (
                  <Card key={rAny.repoId || rAny.name} style={{marginBottom: 10}}>
                    <div
                      style={{
                        display: 'flex',
                        justifyContent: 'space-between',
                        alignItems: 'baseline',
                        marginBottom: 10,
                        gap: 10,
                      }}
                    >
                      <div style={{minWidth: 0, flex: 1}}>
                        <div
                          style={{
                            fontSize: 14.5,
                            fontWeight: 600,
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                          }}
                        >
                          {rAny.name}
                        </div>
                        <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
                          {rAny.headSha?.slice(0, 7)}{' '}
                          {rAny.commitsBehind && rAny.commitsBehind > 0
                            ? `· ${rAny.commitsBehind} commits behind origin`
                            : '· in sync'}
                        </div>
                      </div>
                      <StatusPill kind={(rAny.commitsBehind ?? 0) > 0 ? 'needs' : 'shipped'}>
                        {(rAny.commitsBehind ?? 0) > 0 ? 'Drift' : 'Healthy'}
                      </StatusPill>
                    </div>
                    <div
                      style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(4, 1fr)',
                        gap: 10,
                        paddingTop: 10,
                        borderTop: '1px solid var(--signal-hairline)',
                        fontSize: 12,
                      }}
                    >
                      <div>
                        <div
                          style={{
                            color: 'var(--signal-text-dim)',
                            fontSize: 10,
                            textTransform: 'uppercase',
                            letterSpacing: '0.1em',
                          }}
                        >
                          Coverage
                        </div>
                        <div style={{fontWeight: 600, marginTop: 2}}>{cov}</div>
                      </div>
                      <div>
                        <div
                          style={{
                            color: 'var(--signal-text-dim)',
                            fontSize: 10,
                            textTransform: 'uppercase',
                            letterSpacing: '0.1em',
                          }}
                        >
                          Build
                        </div>
                        <div
                          style={{
                            fontWeight: 600,
                            marginTop: 2,
                            color:
                              rAny.buildStatus === 'PASSING'
                                ? 'var(--signal-mint-text-on-light)'
                                : 'var(--signal-coral-text)',
                          }}
                        >
                          {rAny.buildStatus ?? '—'}
                        </div>
                      </div>
                      <div>
                        <div
                          style={{
                            color: 'var(--signal-text-dim)',
                            fontSize: 10,
                            textTransform: 'uppercase',
                            letterSpacing: '0.1em',
                          }}
                        >
                          Lint
                        </div>
                        <div
                          style={{
                            fontWeight: 600,
                            marginTop: 2,
                            color:
                              rAny.lintStatus === 'CLEAN'
                                ? 'var(--signal-mint-text-on-light)'
                                : 'var(--signal-coral-text)',
                          }}
                        >
                          {rAny.lintStatus ?? '—'}
                        </div>
                      </div>
                      <div>
                        <div
                          style={{
                            color: 'var(--signal-text-dim)',
                            fontSize: 10,
                            textTransform: 'uppercase',
                            letterSpacing: '0.1em',
                          }}
                        >
                          Webhook
                        </div>
                        <div
                          style={{
                            fontWeight: 600,
                            marginTop: 2,
                            color: rAny.webhookInstalled
                              ? 'var(--signal-mint-text-on-light)'
                              : 'var(--signal-coral-text)',
                          }}
                        >
                          {rAny.webhookInstalled ? '✓' : '○'}
                        </div>
                      </div>
                    </div>
                  </Card>
                );
              })}
            </>
          ))}

        {sub === 'standards' &&
          (standards == null ? (
            <LoadingState label='Loading standards compliance…' />
          ) : (
            <>
              <Card
                style={{
                  background: 'var(--signal-lavender-soft)',
                  borderColor: 'transparent',
                  marginBottom: 12,
                }}
              >
                <div
                  style={{
                    fontSize: 11,
                    fontWeight: 700,
                    color: '#5443a8',
                    letterSpacing: '0.14em',
                    textTransform: 'uppercase',
                    marginBottom: 6,
                  }}
                >
                  Org standards · last 30 days
                </div>
                <div style={{fontSize: 13, color: '#5443a8', lineHeight: 1.5}}>
                  Naming conventions, lint rules, coverage SLAs and other policies your org has set.
                  PROJECT_ADMIN can request overrides per finding.
                </div>
              </Card>
              {(() => {
                const sAny = standards as unknown as {
                  violations?: api.ViolationEntry[];
                  summary?: Record<string, number>;
                  standardCount?: number;
                };
                const viols = sAny.violations || [];
                if (viols.length === 0) {
                  return (
                    <Card>
                      <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                        No standards violations in the last 30 days.
                      </p>
                    </Card>
                  );
                }
                return viols.map((v, i) => {
                  const vAny = v as unknown as {
                    id?: string;
                    standardId?: string;
                    standardName?: string;
                    severity?: string;
                    description?: string;
                    filePath?: string;
                    line?: number;
                    createdAt?: string;
                  };
                  const sev = (vAny.severity || 'MINOR').toUpperCase();
                  return (
                    <Row
                      key={vAny.id || i}
                      dot={sev === 'CRITICAL' ? 'coral' : sev === 'MAJOR' ? 'signal' : 'lavender'}
                      title={vAny.standardName || vAny.standardId || 'Standard violation'}
                      meta={
                        <>
                          {vAny.filePath && (
                            <span className='mono'>
                              {vAny.filePath}
                              {vAny.line ? `:${vAny.line}` : ''}
                            </span>
                          )}
                          {vAny.description && (
                            <>
                              <span>·</span>
                              <span>{vAny.description.slice(0, 60)}</span>
                            </>
                          )}
                        </>
                      }
                      trailing={sev}
                    />
                  );
                });
              })()}
            </>
          ))}

        {sub === 'docs' && (
          <>
            {(docs || []).length === 0 ? (
              <Card>
                <p
                  style={{
                    fontSize: 13.5,
                    color: 'var(--signal-text-dim)',
                    margin: 0,
                    lineHeight: 1.5,
                  }}
                >
                  No docs generated yet. Use the buttons below.
                </p>
              </Card>
            ) : (
              (docs || []).map((d) => {
                const dt = (d as unknown as {docType?: string}).docType;
                const updated = (d as unknown as {updatedAt?: string}).updatedAt;
                return (
                  <Row
                    key={dt}
                    dot='lavender'
                    title={`${dt}.md`}
                    meta={
                      updated ? (
                        <span>updated {new Date(updated).toLocaleDateString()}</span>
                      ) : (
                        <span>fresh</span>
                      )
                    }
                    trailing={
                      <div style={{display: 'flex', gap: 4}}>
                        <Button
                          kind='secondary'
                          sm
                          onClick={() => downloadDoc(dt as 'EXPLANATION' | 'SETUP')}
                        >
                          Download
                        </Button>
                        <Button
                          kind='ghost'
                          sm
                          onClick={() => generateDoc(dt as 'EXPLANATION' | 'SETUP')}
                        >
                          Regen
                        </Button>
                      </div>
                    }
                  />
                );
              })
            )}
            <div style={{display: 'flex', gap: 8, marginTop: 8}}>
              <Button
                kind='secondary'
                block
                sm
                onClick={() => generateDoc('EXPLANATION')}
                disabled={busy === 'gen-EXPLANATION'}
              >
                {busy === 'gen-EXPLANATION' ? 'Generating…' : 'Generate EXPLANATION.md'}
              </Button>
              <Button
                kind='secondary'
                block
                sm
                onClick={() => generateDoc('SETUP')}
                disabled={busy === 'gen-SETUP'}
              >
                {busy === 'gen-SETUP' ? 'Generating…' : 'Generate SETUP.md'}
              </Button>
            </div>
          </>
        )}
      </Feed>
    </>
  );
}

/**
 * Browse — surfaces the project's current code via the per-repo codemap. The
 * codemap is generated as part of the architecture pipeline and stored as a
 * markdown blob; we parse it client-side (see {@link parseCodemap}) to derive:
 *
 *   - a file tree (every `### \`path\`` heading in the codemap)
 *   - per-file metadata (functions extracted by the regex pass + an optional
 *     LLM-summarised "purpose" line)
 *   - top-level dir + route lists for the repo-level overview
 *
 * No new backend endpoint needed; the data is already there.
 *
 * Two-pane layout matches the Result page Code tab — file tree on the left,
 * file detail on the right. The detail panel renders the markdown directly
 * so the function list + purpose summary are formatted properly.
 */
function BrowseTab({
  id,
  repos,
  browseRepoId,
  setBrowseRepoId,
  codemap,
  loading,
  selectedFilePath,
  setSelectedFilePath,
}: {
  id: string;
  repos: Array<Record<string, unknown>>;
  browseRepoId: string | null;
  setBrowseRepoId: (s: string) => void;
  codemap: CodemapSummary | 'missing' | undefined;
  loading: boolean;
  selectedFilePath: string | null;
  setSelectedFilePath: (s: string | null) => void;
}) {
  // Memoise the file list so FileTree's deterministic-sort isn't re-running
  // every render. Falls back to an empty array when the codemap is missing
  // or still loading.
  const fileEntries = useMemo(() => {
    if (!codemap || codemap === 'missing') return [];
    return codemap.files.map((f) => ({path: f.path, status: 'BROWSE' as const}));
  }, [codemap]);

  const selectedFile = useMemo(() => {
    if (!codemap || codemap === 'missing' || !selectedFilePath) return null;
    return codemap.files.find((f) => f.path === selectedFilePath) ?? null;
  }, [codemap, selectedFilePath]);

  if (repos.length === 0) {
    return (
      <Card>
        <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
          No repos linked to this project.{' '}
          <a href={`/projects/${id}/repos`} style={{color: 'var(--signal-coral)'}}>
            Link a repo →
          </a>{' '}
          to browse its code here.
        </p>
      </Card>
    );
  }

  return (
    <>
      {/* Repo picker — chip row across the top so users can flip between
          linked repos without leaving the page. Single-repo projects don't
          need the row; collapse it in that case. */}
      {repos.length > 1 && (
        <TabsStrip
          items={repos.map((r) => {
            const rid = repoIdFor(r);
            return {
              label: repoDisplayName(r),
              active: rid === browseRepoId,
              onClick: () => setBrowseRepoId(rid),
            };
          })}
        />
      )}

      {loading || codemap == null ? (
        <LoadingState label='Loading codemap…' />
      ) : codemap === 'missing' ? (
        <Card>
          <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
            No codemap yet for this repo. Generate one from the{' '}
            <a href={`/projects/${id}/architecture`} style={{color: 'var(--signal-coral)'}}>
              Architecture page
            </a>{' '}
            (the codemap is a side-product of architecture analysis) and come back here.
          </p>
        </Card>
      ) : (
        <>
          {/* Repo-level overview card — files count, top-level dirs, routes. */}
          <Card style={{marginBottom: 12}}>
            <div style={{display: 'flex', gap: 18, flexWrap: 'wrap', fontSize: 12.5}}>
              <div>
                <div
                  style={{
                    fontSize: 10,
                    fontWeight: 700,
                    color: 'var(--signal-text-dim)',
                    letterSpacing: '0.1em',
                    textTransform: 'uppercase',
                    marginBottom: 4,
                  }}
                >
                  Files
                </div>
                <div style={{fontWeight: 600}}>{codemap.fileCount || codemap.files.length}</div>
              </div>
              {codemap.topLevelDirs.length > 0 && (
                <div style={{flex: 1, minWidth: 180}}>
                  <div
                    style={{
                      fontSize: 10,
                      fontWeight: 700,
                      color: 'var(--signal-text-dim)',
                      letterSpacing: '0.1em',
                      textTransform: 'uppercase',
                      marginBottom: 4,
                    }}
                  >
                    Top-level layout
                  </div>
                  <div
                    style={{
                      fontFamily: "'JetBrains Mono', monospace",
                      fontSize: 11.5,
                      color: 'var(--signal-ink)',
                    }}
                  >
                    {codemap.topLevelDirs.slice(0, 8).join(' · ')}
                    {codemap.topLevelDirs.length > 8 && ` +${codemap.topLevelDirs.length - 8}`}
                  </div>
                </div>
              )}
              {codemap.routes.length > 0 && (
                <div>
                  <div
                    style={{
                      fontSize: 10,
                      fontWeight: 700,
                      color: 'var(--signal-text-dim)',
                      letterSpacing: '0.1em',
                      textTransform: 'uppercase',
                      marginBottom: 4,
                    }}
                  >
                    Routes
                  </div>
                  <div style={{fontWeight: 600}}>{codemap.routes.length}</div>
                </div>
              )}
            </div>
          </Card>

          {/* Two-pane: file tree (left) + file detail (right). On narrow
              viewports the panes stack vertically so the tree stays usable
              on mobile. */}
          <div className='signal-split-runs'>
            <div className='split-list' aria-label='File tree'>
              {fileEntries.length === 0 ? (
                <Card>
                  <p
                    style={{
                      fontSize: 13,
                      color: 'var(--signal-text-dim)',
                      margin: 0,
                      lineHeight: 1.5,
                    }}
                  >
                    The codemap is present but didn't extract any source files — possibly because
                    the repo's file extensions aren't in the function-extraction allowlist (Java,
                    Python, TS/JS, Go, Rust, Ruby). Regenerate architecture to retry.
                  </p>
                </Card>
              ) : (
                <FileTree
                  files={fileEntries}
                  selectedPath={selectedFilePath}
                  onSelect={setSelectedFilePath}
                />
              )}
            </div>
            <div className='split-detail'>
              {selectedFile == null ? (
                <Card>
                  <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>
                    {fileEntries.length === 0
                      ? 'No files to browse — see the note on the left.'
                      : 'Select a file on the left to see its functions and purpose summary.'}
                  </p>
                </Card>
              ) : (
                <Card>
                  <div
                    style={{
                      fontSize: 13.5,
                      fontWeight: 600,
                      marginBottom: 6,
                      fontFamily: "'JetBrains Mono', monospace",
                    }}
                  >
                    {selectedFile.path}
                  </div>
                  {selectedFile.purpose && (
                    <p
                      style={{
                        fontSize: 13,
                        color: 'var(--signal-text-dim)',
                        margin: '0 0 12px 0',
                        lineHeight: 1.5,
                      }}
                    >
                      {selectedFile.purpose}
                    </p>
                  )}
                  {selectedFile.functions.length > 0 ? (
                    <>
                      <div
                        style={{
                          fontSize: 10,
                          fontWeight: 700,
                          color: 'var(--signal-text-dim)',
                          letterSpacing: '0.1em',
                          textTransform: 'uppercase',
                          marginBottom: 6,
                        }}
                      >
                        Functions · {selectedFile.functions.length}
                      </div>
                      <ul
                        style={{
                          margin: 0,
                          paddingLeft: 18,
                          fontFamily: "'JetBrains Mono', monospace",
                          fontSize: 12.5,
                          lineHeight: 1.7,
                        }}
                      >
                        {selectedFile.functions.map((fn) => (
                          <li key={fn}>{fn}</li>
                        ))}
                      </ul>
                    </>
                  ) : (
                    <p style={{fontSize: 13, color: 'var(--signal-text-dim)', margin: 0}}>
                      No functions extracted by the codemap regex pass — this file may be config /
                      static markup or use a syntax the extractor doesn't recognise.
                    </p>
                  )}
                  <div
                    style={{
                      marginTop: 16,
                      paddingTop: 12,
                      borderTop: '1px solid var(--signal-hairline)',
                    }}
                  >
                    {(() => {
                      // Build a GitHub deep-link from the codemap's repo
                      // slug — gives users a one-click "view full source"
                      // affordance since we don't ship raw file content here.
                      const slug = codemap.repoSlug;
                      const href = slug
                        ? `https://github.com/${slug}/blob/HEAD/${selectedFile.path}`
                        : null;
                      return href ? (
                        <Button
                          kind='ghost'
                          sm
                          onClick={() => window.open(href, '_blank', 'noopener')}
                        >
                          Open in GitHub →
                        </Button>
                      ) : null;
                    })()}
                  </div>
                </Card>
              )}
            </div>
          </div>
        </>
      )}
    </>
  );
}
