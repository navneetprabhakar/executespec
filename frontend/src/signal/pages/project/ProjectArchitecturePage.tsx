import {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, StatusPill, TabsStrip} from '@/signal/components/Primitives';
import {LoadingState, SkeletonRow} from '@/signal/components/Loaders';
import {Markdown} from '@/signal/components/Markdown';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import * as api from '@/lib/api';

type ArchTab = 'current' | 'versions' | 'invariants' | 'adrs' | 'contracts';

export default function ProjectArchitecturePage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const [arch, setArch] = useState<unknown>(null);
  const [versions, setVersions] = useState<unknown[]>([]);
  const [invariants, setInvariants] = useState<api.InvariantSummary[]>([]);
  const [violations, setViolations] = useState<api.InvariantViolation[]>([]);
  const [adrs, setAdrs] = useState<api.AdrSummary[]>([]);
  const [staleness, setStaleness] = useState<api.StalenessReport | null>(null);
  const [coverage, setCoverage] = useState<Record<
    string,
    api.ContractCoverageResponse | null
  > | null>(null);
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [contractBusy, setContractBusy] = useState<string | null>(null);
  // Diff between two arch versions — wired to /api/projects/{id}/architecture/diff?from=&to=.
  const [diffFrom, setDiffFrom] = useState<number | null>(null);
  const [diffTo, setDiffTo] = useState<number | null>(null);
  const [diffReport, setDiffReport] = useState<api.ArchDiffReport | null>(null);
  const [diffBusy, setDiffBusy] = useState(false);
  const project = useProjectMeta(id);
  const [tab, setTab] = useState<ArchTab>('current');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  async function refresh() {
    if (!id) return;
    Promise.allSettled([
      api.getArchitectureDoc(id),
      api.getArchitectureVersions(id),
      api.listProjectInvariants(id),
      api.listProjectInvariantViolations(id),
      api.listProjectAdrs(id, false),
      api.getArchitectureStaleness(id),
      api.getProjectRepos(id),
    ]).then(async ([a, v, inv, viol, adr, st, rp]) => {
      if (a.status === 'fulfilled') setArch(a.value);
      if (v.status === 'fulfilled') setVersions(v.value as unknown[]);
      if (inv.status === 'fulfilled') setInvariants(inv.value);
      if (viol.status === 'fulfilled') setViolations(viol.value);
      if (adr.status === 'fulfilled') setAdrs(adr.value);
      if (st.status === 'fulfilled') setStaleness(st.value);
      if (rp.status === 'fulfilled') {
        const repoList = rp.value as unknown as Array<Record<string, unknown>>;
        setRepos(repoList);
        // Per-repo contract coverage fetch — parallel, fail-soft.
        const entries: Array<[string, api.ContractCoverageResponse | null]> = await Promise.all(
          repoList.map(async (r) => {
            const rid = String(
              (r as {repoId?: string; id?: string}).repoId || (r as {id?: string}).id || '',
            );
            try {
              return [rid, await api.getContractCoverage(id, rid)] as [
                string,
                api.ContractCoverageResponse,
              ];
            } catch {
              return [rid, null];
            }
          }),
        );
        setCoverage(Object.fromEntries(entries));
      }
    });
  }

  async function rescanCoverage(repoId: string) {
    setContractBusy(`scan-${repoId}`);
    setErr('');
    setMsg('');
    try {
      await api.rescanContractCoverage(id, repoId);
      const next = await api.getContractCoverage(id, repoId);
      setCoverage((prev) => (prev ? {...prev, [repoId]: next} : {[repoId]: next}));
      setMsg('Coverage rescan complete.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Rescan failed.');
    } finally {
      setContractBusy(null);
    }
  }

  async function checkDrift(repoId: string) {
    setContractBusy(`drift-${repoId}`);
    setErr('');
    setMsg('');
    try {
      const r = await api.checkRepoDrift(id, repoId);
      const driftCount =
        (r as unknown as {driftCount?: number; results?: Array<unknown>}).driftCount ??
        (r as unknown as {results?: Array<unknown>}).results?.length ??
        0;
      setMsg(
        driftCount > 0
          ? `${driftCount} drift result${driftCount === 1 ? '' : 's'} detected.`
          : 'No drift detected.',
      );
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Drift check failed.');
    } finally {
      setContractBusy(null);
    }
  }

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Repos + coverage are loaded independently of the bundled architecture
  // fetch so the Contracts tab doesn't sit on "Link repos" while a slower
  // sibling (architecture/versions/staleness) is still pending.
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    api
      .getProjectRepos(id)
      .then(async (rp) => {
        if (cancelled) return;
        const list = rp as unknown as Array<Record<string, unknown>>;
        setRepos(list);
        const entries: Array<[string, api.ContractCoverageResponse | null]> = await Promise.all(
          list.map(async (r) => {
            const rid = String(
              (r as {repoId?: string; id?: string}).repoId || (r as {id?: string}).id || '',
            );
            try {
              return [rid, await api.getContractCoverage(id, rid)] as [
                string,
                api.ContractCoverageResponse,
              ];
            } catch {
              return [rid, null];
            }
          }),
        );
        if (!cancelled) setCoverage(Object.fromEntries(entries));
      })
      .catch(() => {
        /* keep empty state */
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  async function regenerate() {
    setBusy(true);
    setErr('');
    try {
      await api.generateArchitecture(id);
      setMsg('Architecture regeneration started.');
      await refresh();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Regenerate failed.');
    } finally {
      setBusy(false);
    }
  }

  const archAny = (arch || {}) as unknown as {
    version?: number;
    updatedAt?: string;
    content?: string;
    markdown?: string;
    summary?: string;
  };
  const docMarkdown = archAny.content || archAny.markdown || archAny.summary || '';
  const violationsByInvariant = useMemo(() => {
    const map = new Map<string, number>();
    for (const v of violations) {
      const vAny = v as unknown as {invariantId?: string};
      if (vAny.invariantId) map.set(vAny.invariantId, (map.get(vAny.invariantId) ?? 0) + 1);
    }
    return map;
  }, [violations]);

  return (
    <>
      <TopBar
        title='Architecture'
        sub={`${project?.name || 'project'}${archAny.version ? ` · v${archAny.version}` : ''}${archAny.updatedAt ? ` · ${new Date(archAny.updatedAt).toLocaleDateString()}` : ''}`}
        back
        backTo={`/projects/${id}`}
        actions={
          <IconButton ariaLabel='Regenerate' onClick={regenerate}>
            <svg
              viewBox='0 0 24 24'
              style={{width: 18, height: 18}}
              fill='none'
              stroke='currentColor'
              strokeWidth={2}
              strokeLinecap='round'
            >
              <polyline points='23 4 23 10 17 10' />
              <path d='M20.49 15a9 9 0 1 1-2.12-9.36L23 10' />
            </svg>
          </IconButton>
        }
      />
      <Feed>
        <ProjectTabs active='architecture' />
        <TabsStrip
          items={[
            {label: 'Current', active: tab === 'current', onClick: () => setTab('current')},
            {
              label: `Versions · ${versions.length}`,
              active: tab === 'versions',
              onClick: () => setTab('versions'),
            },
            {
              label: `Invariants · ${invariants.length}`,
              active: tab === 'invariants',
              onClick: () => setTab('invariants'),
            },
            {label: `ADRs · ${adrs.length}`, active: tab === 'adrs', onClick: () => setTab('adrs')},
            {label: 'Contracts', active: tab === 'contracts', onClick: () => setTab('contracts')},
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

        {tab === 'current' && (
          <>
            <Card>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  marginBottom: 12,
                }}
              >
                <div>
                  <div
                    style={{
                      fontSize: 11,
                      fontWeight: 700,
                      color: 'var(--signal-text-mute)',
                      letterSpacing: '0.14em',
                      textTransform: 'uppercase',
                      marginBottom: 4,
                    }}
                  >
                    Architecture · {project?.name || 'project'}
                  </div>
                  <div style={{fontSize: 15, fontWeight: 600}}>
                    {archAny.version ? `v${archAny.version}` : 'Latest'}
                  </div>
                </div>
                <StatusPill
                  kind={
                    staleness && (staleness as unknown as {anyStale?: boolean}).anyStale
                      ? 'needs'
                      : 'shipped'
                  }
                >
                  {staleness && (staleness as unknown as {anyStale?: boolean}).anyStale
                    ? 'Stale'
                    : 'Up to date'}
                </StatusPill>
              </div>
              {docMarkdown ? (
                <div
                  style={{
                    background: 'var(--signal-card-elev)',
                    padding: '14px 16px',
                    borderRadius: 10,
                    maxHeight: 480,
                    overflow: 'auto',
                  }}
                >
                  <Markdown>{docMarkdown.slice(0, 8000)}</Markdown>
                </div>
              ) : (
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  No architecture doc yet. Click Regenerate to build one from your repos.
                </p>
              )}
            </Card>
            <Button
              kind='secondary'
              block
              style={{marginTop: 12}}
              onClick={regenerate}
              disabled={busy}
            >
              {busy ? 'Regenerating…' : 'Regenerate codemap + arch doc'}
            </Button>
          </>
        )}

        {tab === 'versions' &&
          (versions.length === 0 ? (
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                No previous versions yet.
              </p>
            </Card>
          ) : (
            <>
              {/* Diff picker — pick two versions, hit Compare, see the source-file changes. */}
              <Card style={{marginBottom: 12, padding: 14}}>
                <div
                  style={{
                    fontSize: 11,
                    fontWeight: 700,
                    letterSpacing: '0.14em',
                    textTransform: 'uppercase',
                    color: 'var(--signal-text-mute)',
                    marginBottom: 10,
                  }}
                >
                  Compare versions
                </div>
                <div style={{display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap'}}>
                  <select
                    value={diffFrom ?? ''}
                    onChange={(e) => setDiffFrom(e.target.value ? Number(e.target.value) : null)}
                    style={{
                      background: 'var(--signal-card-elev)',
                      border: '1px solid var(--signal-hairline)',
                      borderRadius: 8,
                      padding: '8px 10px',
                      fontSize: 13,
                      color: 'var(--signal-ink)',
                    }}
                  >
                    <option value=''>From version…</option>
                    {versions.map((v, i) => {
                      const ver = (v as unknown as {version?: number}).version ?? i + 1;
                      return (
                        <option key={ver} value={ver}>
                          v{ver}
                        </option>
                      );
                    })}
                  </select>
                  <span style={{color: 'var(--signal-text-mute)'}}>→</span>
                  <select
                    value={diffTo ?? ''}
                    onChange={(e) => setDiffTo(e.target.value ? Number(e.target.value) : null)}
                    style={{
                      background: 'var(--signal-card-elev)',
                      border: '1px solid var(--signal-hairline)',
                      borderRadius: 8,
                      padding: '8px 10px',
                      fontSize: 13,
                      color: 'var(--signal-ink)',
                    }}
                  >
                    <option value=''>To version…</option>
                    {versions.map((v, i) => {
                      const ver = (v as unknown as {version?: number}).version ?? i + 1;
                      return (
                        <option key={ver} value={ver}>
                          v{ver}
                        </option>
                      );
                    })}
                  </select>
                  <Button
                    kind='primary'
                    sm
                    disabled={diffBusy || diffFrom == null || diffTo == null}
                    onClick={async () => {
                      if (diffFrom == null || diffTo == null) return;
                      setDiffBusy(true);
                      setErr('');
                      try {
                        const r = await api.getArchitectureDiff(id, diffFrom, diffTo);
                        setDiffReport(r);
                      } catch (e: unknown) {
                        setErr(e instanceof Error ? e.message : 'Diff failed.');
                      } finally {
                        setDiffBusy(false);
                      }
                    }}
                  >
                    {diffBusy ? 'Comparing…' : 'Compare'}
                  </Button>
                </div>
                {diffReport && (
                  <div style={{marginTop: 12, fontSize: 13, color: 'var(--signal-text-dim)'}}>
                    <div style={{fontWeight: 600, color: 'var(--signal-ink)', marginBottom: 6}}>
                      v{(diffReport as unknown as {fromVersion?: number}).fromVersion} → v
                      {(diffReport as unknown as {toVersion?: number}).toVersion}
                    </div>
                    {(
                      (diffReport as unknown as {changedFiles?: api.ArchChangedFile[]})
                        .changedFiles || []
                    ).length === 0 ? (
                      <span>No source-file changes.</span>
                    ) : (
                      <ul
                        style={{
                          listStyle: 'none',
                          padding: 0,
                          margin: 0,
                          maxHeight: 240,
                          overflowY: 'auto',
                          fontFamily: "'JetBrains Mono', monospace",
                          fontSize: 12,
                        }}
                      >
                        {(
                          (diffReport as unknown as {changedFiles?: api.ArchChangedFile[]})
                            .changedFiles || []
                        ).map((f, i) => {
                          const fAny = f as unknown as {path?: string; status?: string};
                          const status = (fAny.status || 'MODIFIED').toUpperCase();
                          const color =
                            status === 'ADDED'
                              ? 'var(--signal-mint-text-on-light)'
                              : status === 'REMOVED'
                                ? 'var(--signal-coral-text)'
                                : 'var(--signal-y-text)';
                          return (
                            <li
                              key={i}
                              style={{
                                padding: '4px 0',
                                whiteSpace: 'nowrap',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                              }}
                            >
                              <span style={{color, fontWeight: 600, marginRight: 8}}>
                                {status[0]}
                              </span>
                              {fAny.path}
                            </li>
                          );
                        })}
                      </ul>
                    )}
                  </div>
                )}
              </Card>
              {versions.map((v, i) => {
                const vAny = v as unknown as {
                  version?: number;
                  createdAt?: string;
                  commitSha?: string;
                };
                return (
                  <Row
                    key={vAny.version ?? i}
                    dot='lavender'
                    title={`v${vAny.version ?? i + 1}`}
                    meta={
                      <>
                        {vAny.createdAt && <span>{new Date(vAny.createdAt).toLocaleString()}</span>}
                        {vAny.commitSha && (
                          <>
                            <span>·</span>
                            <span className='mono'>{vAny.commitSha.slice(0, 7)}</span>
                          </>
                        )}
                      </>
                    }
                    showChev
                  />
                );
              })}
            </>
          ))}

        {tab === 'invariants' && (
          <>
            <SectionHeader>Invariants · {invariants.length}</SectionHeader>
            {invariants.length === 0 ? (
              <Card>
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  No invariants declared yet.
                </p>
              </Card>
            ) : (
              invariants.map((inv) => {
                const iAny = inv as unknown as {
                  id?: string;
                  title?: string;
                  name?: string;
                  description?: string;
                };
                const violCount = violationsByInvariant.get(iAny.id || '') ?? 0;
                return (
                  <Row
                    key={iAny.id}
                    dot={violCount > 0 ? 'coral' : 'mint'}
                    title={iAny.title || iAny.name || 'invariant'}
                    meta={<span>{iAny.description || ''}</span>}
                    trailing={
                      <StatusPill kind={violCount > 0 ? 'needs' : 'shipped'}>
                        {violCount > 0 ? `${violCount} FAIL` : 'PASS'}
                      </StatusPill>
                    }
                  />
                );
              })
            )}
          </>
        )}

        {tab === 'adrs' && (
          <>
            <SectionHeader>ADRs · {adrs.length}</SectionHeader>
            {adrs.length === 0 ? (
              <Card>
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>
                  No ADRs yet.
                </p>
              </Card>
            ) : (
              adrs.map((adr) => {
                const aAny = adr as unknown as {
                  id?: string;
                  number?: number;
                  title?: string;
                  status?: string;
                  createdAt?: string;
                  author?: string;
                };
                const status = (aAny.status || 'PROPOSED').toUpperCase();
                return (
                  <Row
                    key={aAny.id}
                    dot='lavender'
                    title={`ADR ${String(aAny.number ?? '').padStart(4, '0')} · ${aAny.title || 'untitled'}`}
                    meta={
                      <>
                        {aAny.author && <span>by {aAny.author}</span>}
                        {aAny.createdAt && (
                          <>
                            {aAny.author && <span>·</span>}
                            <span>{new Date(aAny.createdAt).toLocaleDateString()}</span>
                          </>
                        )}
                      </>
                    }
                    trailing={
                      <StatusPill
                        kind={
                          status === 'ACCEPTED'
                            ? 'shipped'
                            : status === 'REJECTED'
                              ? 'failed'
                              : 'needs'
                        }
                      >
                        {status}
                      </StatusPill>
                    }
                  />
                );
              })
            )}
          </>
        )}

        {tab === 'contracts' &&
          (repos.length === 0 ? (
            <Card>
              <p
                style={{
                  fontSize: 13.5,
                  color: 'var(--signal-text-dim)',
                  margin: 0,
                  lineHeight: 1.5,
                }}
              >
                Link repos to detect contract schemas (OpenAPI / GraphQL / gRPC).{' '}
                <a href={`/projects/${id}/repos`} style={{color: 'var(--signal-coral)'}}>
                  Manage repos →
                </a>
              </p>
            </Card>
          ) : coverage == null ? (
            <LoadingState label='Loading coverage…' />
          ) : (
            <>
              <SectionHeader>Per-repo coverage</SectionHeader>
              {repos.map((rep) => {
                const repoId = String(
                  (rep as {repoId?: string; id?: string}).repoId || (rep as {id?: string}).id || '',
                );
                const repoName = String(
                  (rep as {repoName?: string; name?: string}).repoName ||
                    (rep as {name?: string}).name ||
                    repoId,
                );
                const cov = coverage[repoId];
                const detectors = (cov?.scans || []) as api.DetectorSummary[];
                const knownGaps = (cov?.knownGaps || []) as api.KnownGap[];
                const totalContracts = detectors.reduce(
                  (a, d) => a + ((d as unknown as {contractCount?: number}).contractCount ?? 0),
                  0,
                );
                const scanned = detectors.filter((d) => d.status === 'SCANNED').length;
                const failed = detectors.filter((d) => d.status === 'FAILED').length;
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
                          {repoName}
                        </div>
                        <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
                          {cov == null
                            ? 'No coverage data — run a scan'
                            : `${scanned} detector${scanned === 1 ? '' : 's'} scanned${failed > 0 ? ` · ${failed} failed` : ''} · ${totalContracts} contract${totalContracts === 1 ? '' : 's'}`}
                        </div>
                      </div>
                      <div style={{display: 'flex', gap: 4, flexShrink: 0}}>
                        <Button
                          kind='secondary'
                          sm
                          disabled={contractBusy === `scan-${repoId}`}
                          onClick={() => rescanCoverage(repoId)}
                        >
                          {contractBusy === `scan-${repoId}` ? 'Scanning…' : 'Rescan'}
                        </Button>
                        <Button
                          kind='ghost'
                          sm
                          disabled={contractBusy === `drift-${repoId}`}
                          onClick={() => checkDrift(repoId)}
                        >
                          {contractBusy === `drift-${repoId}` ? 'Checking…' : 'Check drift'}
                        </Button>
                      </div>
                    </div>
                    {detectors.length > 0 && (
                      <div
                        style={{
                          display: 'flex',
                          gap: 6,
                          flexWrap: 'wrap',
                          marginBottom: knownGaps.length > 0 ? 10 : 0,
                        }}
                      >
                        {detectors.map((d, i) => {
                          const dAny = d as unknown as {
                            format?: string;
                            type?: string;
                            status?: string;
                            contractCount?: number;
                          };
                          const fmt = dAny.format || dAny.type || 'detector';
                          const st = dAny.status || 'SCANNED';
                          return (
                            <StatusPill
                              key={`${repoId}-${fmt}-${i}`}
                              kind={
                                st === 'SCANNED' ? 'shipped' : st === 'FAILED' ? 'failed' : 'needs'
                              }
                            >
                              {fmt}
                              {dAny.contractCount != null ? ` · ${dAny.contractCount}` : ''}
                            </StatusPill>
                          );
                        })}
                      </div>
                    )}
                    {knownGaps.length > 0 && (
                      <div
                        style={{
                          borderTop: '1px solid var(--signal-hairline)',
                          paddingTop: 10,
                          fontSize: 12,
                          color: 'var(--signal-text-dim)',
                        }}
                      >
                        <div style={{fontWeight: 600, color: 'var(--signal-ink)', marginBottom: 4}}>
                          {knownGaps.length} known gap{knownGaps.length === 1 ? '' : 's'}
                        </div>
                        {knownGaps.slice(0, 3).map((g, i) => {
                          const gAny = g as unknown as {description?: string; format?: string};
                          return <div key={i}>· {gAny.description || gAny.format || 'gap'}</div>;
                        })}
                      </div>
                    )}
                  </Card>
                );
              })}
              <Button
                kind='secondary'
                block
                style={{marginTop: 8}}
                onClick={async () => {
                  setContractBusy('all');
                  try {
                    await api.checkProjectDrift(id);
                    setMsg('Project-wide drift check started.');
                  } catch (e: unknown) {
                    setErr(e instanceof Error ? e.message : 'Drift check failed.');
                  } finally {
                    setContractBusy(null);
                  }
                }}
                disabled={contractBusy === 'all'}
              >
                {contractBusy === 'all' ? 'Checking…' : 'Check drift across all repos'}
              </Button>
            </>
          ))}
      </Feed>
    </>
  );
}
