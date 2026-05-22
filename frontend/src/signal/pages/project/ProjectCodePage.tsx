import {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, StatusPill, TabsStrip} from '@/signal/components/Primitives';
import {LoadingState} from '@/signal/components/Loaders';
import {ProjectTabs} from './ProjectTabs';
import {FileTree} from '@/signal/components/FileTree';
import {CodeView} from '@/signal/components/CodeView';
import {CodeReviewPanel} from '@/signal/components/CodeReviewPanel';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import {repoDisplayName, repoIdFor} from '@/signal/lib/runUtils';
import {useAuthStore} from '@/stores/useAuthStore';
import * as api from '@/lib/api';
/* useProjectMeta delivers project name from cached project list quickly,
   and refreshes from /api/projects/{id} in the background. */

// 2026-05-16 (feedback) — Browse is the FIRST sub-tab so users land on
// "see the actual code" by default. Data source is the GitHub-proxied
// /api/projects/{id}/repos/{repoId}/files + /file endpoints, NOT the codemap
// — the user explicitly asked for actual source content rather than codemap-
// derived function lists / purpose summaries.
type Sub = 'browse' | 'debt' | 'review' | 'health' | 'standards' | 'docs';

export default function ProjectCodePage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const [sub, setSub] = useState<Sub>('browse');
  // Browse sub-tab — fetches the live file list via the new
  // /api/projects/{id}/repos/{repoId}/files endpoint (GitHub-proxied through
  // the user's PAT) so users see the actual current code, not codemap
  // metadata. Per-repo cache + per-file content cache so flipping between
  // files doesn't re-fetch.
  const [browseRepoId, setBrowseRepoId] = useState<string | null>(null);
  const [filesByRepo, setFilesByRepo] = useState<
    Record<string, api.RepoFileList | 'error' | undefined>
  >({});
  const [filesLoading, setFilesLoading] = useState<Record<string, boolean>>({});
  const [fileContentCache, setFileContentCache] = useState<
    Record<string, api.RepoFile | 'error' | undefined>
  >({});
  const [fileLoading, setFileLoading] = useState<Record<string, boolean>>({});
  const [selectedFilePath, setSelectedFilePath] = useState<string | null>(null);
  const [debt, setDebt] = useState<api.TechDebtReport | null>(null);
  const [docs, setDocs] = useState<api.GeneratedDocRow[] | null>(null);
  // ProjectCodePage no longer fetches the project itself — useProjectMeta
  // (called in render below) handles that. Repos are fetched here directly.
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  // Code-review target — the repo selected in the per-repo picker on the Code
  // review sub-tab (defaults to the first linked repo). The full whole-repo
  // review surface is rendered per-repo by <CodeReviewPanel>.
  const [reviewRepoId, setReviewRepoId] = useState<string | null>(null);
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

  // Lazy-fetch the file LIST for whichever repo is selected. Cached per-repo
  // so flipping between repos in the same session pays once.
  useEffect(() => {
    if (sub !== 'browse' || !id || !browseRepoId) return;
    if (filesByRepo[browseRepoId] != null || filesLoading[browseRepoId]) return;
    setFilesLoading((m) => ({...m, [browseRepoId]: true}));
    api
      .listRepoFiles(id, browseRepoId)
      .then((res) => {
        setFilesByRepo((m) => ({...m, [browseRepoId]: res}));
      })
      .catch(() => {
        setFilesByRepo((m) => ({...m, [browseRepoId]: 'error'}));
      })
      .finally(() => {
        setFilesLoading((m) => ({...m, [browseRepoId]: false}));
      });
  }, [sub, id, browseRepoId, filesByRepo, filesLoading]);

  // Lazy-fetch the CONTENT of the selected file. Keyed by repoId:path so the
  // cache is unambiguous when the same path exists in multiple repos.
  useEffect(() => {
    if (sub !== 'browse' || !id || !browseRepoId || !selectedFilePath) return;
    const key = `${browseRepoId}:${selectedFilePath}`;
    if (fileContentCache[key] != null || fileLoading[key]) return;
    setFileLoading((m) => ({...m, [key]: true}));
    api
      .readRepoFile(id, browseRepoId, selectedFilePath)
      .then((res) => {
        setFileContentCache((m) => ({...m, [key]: res}));
      })
      .catch(() => {
        setFileContentCache((m) => ({...m, [key]: 'error'}));
      })
      .finally(() => {
        setFileLoading((m) => ({...m, [key]: false}));
      });
  }, [sub, id, browseRepoId, selectedFilePath, fileContentCache, fileLoading]);

  // Reset selected file when the active repo changes — otherwise switching
  // repos leaves a stale path highlighted that doesn't exist in the new tree.
  useEffect(() => {
    setSelectedFilePath(null);
  }, [browseRepoId]);

  // Default-select the first repo when the user opens Code review so the panel
  // has a target on first load.
  useEffect(() => {
    if (sub === 'review' && !reviewRepoId && repos.length > 0) {
      setReviewRepoId(repoIdFor(repos[0]));
    }
  }, [sub, repos, reviewRepoId]);

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
            fileList={browseRepoId ? filesByRepo[browseRepoId] : undefined}
            fileListLoading={browseRepoId ? !!filesLoading[browseRepoId] : false}
            selectedFilePath={selectedFilePath}
            setSelectedFilePath={setSelectedFilePath}
            selectedFileContent={
              browseRepoId && selectedFilePath
                ? fileContentCache[`${browseRepoId}:${selectedFilePath}`]
                : undefined
            }
            selectedFileLoading={
              browseRepoId && selectedFilePath
                ? !!fileLoading[`${browseRepoId}:${selectedFilePath}`]
                : false
            }
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
          ) : (
            <>
              {/* Repo picker — single-repo projects collapse the row. */}
              {repos.length > 1 && (
                <TabsStrip
                  items={repos.map((r) => {
                    const rid = repoIdFor(r);
                    return {
                      label: repoDisplayName(r),
                      active: rid === reviewRepoId,
                      onClick: () => setReviewRepoId(rid),
                    };
                  })}
                />
              )}
              {(() => {
                const rid = reviewRepoId || (repos[0] ? repoIdFor(repos[0]) : '');
                const repo = repos.find((r) => repoIdFor(r) === rid) ?? repos[0];
                if (!repo) return <LoadingState label='Loading…' />;
                return (
                  <CodeReviewPanel
                    key={rid}
                    projectId={id}
                    repoId={rid}
                    repoFullName={repoDisplayName(repo)}
                    variant='signal'
                  />
                );
              })()}
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
 * Browse — shows the project's *actual current code*.
 *
 * Data source: `/api/projects/{id}/repos/{repoId}/files` (file list) +
 * `/api/projects/{id}/repos/{repoId}/file?path=…` (file content), both
 * GitHub-proxied through the user's stored PAT. The codemap is not used
 * here — users explicitly asked for the actual source, not codemap-derived
 * summaries / function lists / purpose strings.
 *
 * Two-pane layout matches the Result page Code tab — file tree on the left,
 * syntax-highlighted file content on the right via `CodeView`.
 */
function BrowseTab({
  id,
  repos,
  browseRepoId,
  setBrowseRepoId,
  fileList,
  fileListLoading,
  selectedFilePath,
  setSelectedFilePath,
  selectedFileContent,
  selectedFileLoading,
}: {
  id: string;
  repos: Array<Record<string, unknown>>;
  browseRepoId: string | null;
  setBrowseRepoId: (s: string) => void;
  fileList: api.RepoFileList | 'error' | undefined;
  fileListLoading: boolean;
  selectedFilePath: string | null;
  setSelectedFilePath: (s: string | null) => void;
  selectedFileContent: api.RepoFile | 'error' | undefined;
  selectedFileLoading: boolean;
}) {
  // Memoise the file entries so FileTree's deterministic-sort isn't re-running
  // every render. We only render the FileTree once the file list has arrived.
  const fileEntries = useMemo(() => {
    if (!fileList || fileList === 'error') return [];
    return fileList.files.map((p) => ({path: p, status: 'BROWSE' as const}));
  }, [fileList]);

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

      {fileListLoading || fileList == null ? (
        <LoadingState label='Loading file list…' />
      ) : fileList === 'error' ? (
        <Card>
          <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
            Couldn&apos;t list files for this repo. Usually means the connected GitHub PAT is
            missing, expired, or lacks read access. Reconnect GitHub from{' '}
            <a href='/integrations' style={{color: 'var(--signal-coral)'}}>
              Integrations
            </a>{' '}
            and try again.
          </p>
        </Card>
      ) : (
        <>
          {/* Repo-level overview card — file count + branch info. */}
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
                <div style={{fontWeight: 600}}>{fileList.files.length}</div>
              </div>
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
                  Branch
                </div>
                <div
                  style={{
                    fontWeight: 600,
                    fontFamily: "'JetBrains Mono', monospace",
                    fontSize: 12,
                  }}
                >
                  {fileList.defaultBranch}
                </div>
              </div>
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
                  Repo
                </div>
                <div
                  style={{
                    fontWeight: 600,
                    fontFamily: "'JetBrains Mono', monospace",
                    fontSize: 12,
                  }}
                >
                  {fileList.repoFullName}
                </div>
              </div>
            </div>
          </Card>

          {/* Two-pane: file tree (left) + file content (right). On narrow
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
                    GitHub returned an empty file list for this repo. Verify the repo isn&apos;t
                    archived / empty, and that the connected PAT has access.
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
              {selectedFilePath == null ? (
                <Card>
                  <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>
                    {fileEntries.length === 0
                      ? 'No files to browse — see the note on the left.'
                      : 'Select a file on the left to view its content.'}
                  </p>
                </Card>
              ) : selectedFileLoading || selectedFileContent == null ? (
                <Card>
                  <LoadingState label={`Loading ${selectedFilePath}…`} />
                </Card>
              ) : selectedFileContent === 'error' ? (
                <Card>
                  <p style={{fontSize: 13, color: 'var(--signal-coral-text)', margin: 0}}>
                    Couldn&apos;t read this file from GitHub. The file may have been deleted, moved,
                    or the PAT may have lost access since the listing was fetched.
                  </p>
                </Card>
              ) : (
                <>
                  <CodeView
                    path={selectedFileContent.path}
                    content={selectedFileContent.content}
                    maxHeight={620}
                  />
                  <div
                    style={{
                      marginTop: 10,
                      display: 'flex',
                      gap: 8,
                      alignItems: 'center',
                      fontSize: 11.5,
                      color: 'var(--signal-text-dim)',
                    }}
                  >
                    <span>{selectedFileContent.sizeBytes.toLocaleString()} bytes</span>
                    <span>·</span>
                    <Button
                      kind='ghost'
                      sm
                      onClick={() =>
                        window.open(
                          `https://github.com/${fileList.repoFullName}/blob/${fileList.defaultBranch}/${selectedFileContent.path}`,
                          '_blank',
                          'noopener',
                        )
                      }
                    >
                      Open in GitHub →
                    </Button>
                  </div>
                </>
              )}
            </div>
          </div>
        </>
      )}
    </>
  );
}
