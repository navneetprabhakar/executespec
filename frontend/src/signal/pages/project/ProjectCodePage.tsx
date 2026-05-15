import {useEffect, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader} from '@/signal/components/Chrome';
import {Feed, Row, Card, Button, StatusPill, TabsStrip} from '@/signal/components/Primitives';
import {ProjectTabs} from './ProjectTabs';
import {useProjectMeta} from '@/signal/hooks/useProjectMeta';
import * as api from '@/lib/api';
/* useProjectMeta delivers project name from cached project list quickly,
   and refreshes from /api/projects/{id} in the background. */

type Sub = 'debt' | 'review' | 'docs';

export default function ProjectCodePage() {
  const {projectId} = useParams();
  const id = projectId || '';
  const project = useProjectMeta(id);
  const [sub, setSub] = useState<Sub>('debt');
  const [debt, setDebt] = useState<api.TechDebtReport | null>(null);
  const [docs, setDocs] = useState<api.GeneratedDocRow[] | null>(null);
  // ProjectCodePage no longer fetches the project itself — useProjectMeta
  // (called in render below) handles that. Repos are fetched here directly.
  const [repos, setRepos] = useState<Array<Record<string, unknown>>>([]);
  const [reviews, setReviews] = useState<Record<string, unknown> | null>(null);
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
      if (explanation) list.push({docType: 'EXPLANATION', ...(explanation as object)} as api.GeneratedDocRow);
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

  useEffect(() => {
    if (sub !== 'review' || !id || repos.length === 0) return;
    // Per-repo latest code review scan. We fetch each in parallel and key by
    // repoId so the panel can render side-by-side severity chips.
    let cancelled = false;
    Promise.all(
      repos.map(async (r) => {
        const repoId = String((r as {repoId?: string; id?: string}).repoId || (r as {id?: string}).id || '');
        const repoName = String((r as {repoName?: string; name?: string}).repoName || (r as {name?: string}).name || repoId);
        try {
          const review = await fetch(`/api/code-reviews?projectId=${id}&latest=true&repo=${encodeURIComponent(repoName)}`, {
            headers: {Authorization: `Bearer ${localStorage.getItem('access_token') ?? ''}`},
          }).then((res) => res.ok ? res.json() : null);
          return [repoId, {repoName, review}];
        } catch {
          return [repoId, {repoName, review: null}];
        }
      }),
    ).then((entries) => {
      if (!cancelled) setReviews(Object.fromEntries(entries));
    });
    return () => { cancelled = true; };
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

  const cats = (debt as unknown as {categories?: Array<{name?: string; count?: number; criticalCount?: number; majorCount?: number; minorCount?: number; description?: string}>})?.categories || [];
  const projectName = project?.name || 'project';

  return (
    <>
      <TopBar title='Code & quality' sub={`${projectName} · last scan ${debt && (debt as unknown as {lastScannedAt?: string}).lastScannedAt ? new Date((debt as unknown as {lastScannedAt: string}).lastScannedAt).toLocaleString() : 'unknown'}`} back backTo={`/projects/${id}`} />
      <Feed>
        <ProjectTabs active='code' />
        <TabsStrip
          items={[
            {label: `Tech debt · ${cats.length}`, active: sub === 'debt', onClick: () => setSub('debt')},
            {label: 'Code review', active: sub === 'review', onClick: () => setSub('review')},
            {label: `Docs · ${docs?.length ?? 0}`, active: sub === 'docs', onClick: () => setSub('docs')},
          ]}
        />

        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}
        {msg && <div style={{background: 'var(--signal-mint-soft)', color: 'var(--signal-mint-text-on-light)', border: '1px solid var(--signal-mint)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{msg}</div>}

        {sub === 'debt' && (
          debt == null ? (
            <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading tech debt…</p></Card>
          ) : cats.length === 0 ? (
            <Card>
              <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
                No tech debt detected. Run a code-review scan to surface issues.
              </p>
            </Card>
          ) : (
            cats.map((c, i) => {
              const name = c.name || `Category ${i + 1}`;
              const crit = c.criticalCount ?? 0;
              const maj = c.majorCount ?? 0;
              const min = c.minorCount ?? 0;
              const total = c.count ?? (crit + maj + min);
              return (
                <Card key={name} style={{padding: 0}}>
                  <div style={{padding: '14px 16px', borderBottom: '1px solid var(--signal-hairline)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10}}>
                    <div style={{minWidth: 0, flex: 1}}>
                      <div style={{fontSize: 14.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{name}</div>
                      <div style={{fontSize: 12, color: 'var(--signal-text-dim)', marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{c.description || `${total} issues`}</div>
                    </div>
                    <div style={{display: 'flex', gap: 4, flexShrink: 0}}>
                      {crit > 0 && <StatusPill kind='failed'>{crit} CRIT</StatusPill>}
                      {maj > 0 && <StatusPill kind='needs'>{maj} MAJ</StatusPill>}
                      {min > 0 && <StatusPill>{min} MIN</StatusPill>}
                    </div>
                  </div>
                  <div style={{padding: '12px 16px'}}>
                    <Button kind='primary' sm block disabled={busy === name} onClick={() => autofixCategory(name)}>
                      {busy === name ? 'Dispatching…' : `Autofix ${total} →`}
                    </Button>
                  </div>
                </Card>
              );
            })
          )
        )}

        {sub === 'review' && (
          repos.length === 0 ? (
            <Card>
              <p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
                No repos linked. <a href={`/projects/${id}/repos`} style={{color: 'var(--signal-coral)'}}>Link one →</a> to enable per-repo code review.
              </p>
            </Card>
          ) : reviews == null ? (
            <Card><p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>Loading scan results…</p></Card>
          ) : (
            <>
              <SectionHeader>Per-repo scan</SectionHeader>
              {Object.entries(reviews).map(([repoId, val]) => {
                const v = val as {repoName?: string; review?: {id?: string; critical?: number; major?: number; minor?: number; info?: number; scannedAt?: string; reportUrl?: string}};
                const r = v.review;
                return (
                  <Card key={repoId} style={{marginBottom: 10}}>
                    <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 10, gap: 10}}>
                      <div style={{minWidth: 0, flex: 1}}>
                        <div style={{fontSize: 14.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{v.repoName}</div>
                        <div style={{fontSize: 12, color: 'var(--signal-text-dim)'}}>
                          {r?.scannedAt ? `Last scan ${new Date(r.scannedAt).toLocaleString()}` : 'No scan yet'}
                        </div>
                      </div>
                      <Button kind='secondary' sm disabled={busy === `scan-${repoId}`} onClick={() => rescanReview(repoId)}>
                        {busy === `scan-${repoId}` ? 'Queuing…' : 'New scan'}
                      </Button>
                    </div>
                    {r && (
                      <div style={{display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 10}}>
                        {(r.critical ?? 0) > 0 && <StatusPill kind='failed'>{r.critical} CRITICAL</StatusPill>}
                        {(r.major ?? 0) > 0 && <StatusPill kind='needs'>{r.major} MAJOR</StatusPill>}
                        {(r.minor ?? 0) > 0 && <StatusPill>{r.minor} MINOR</StatusPill>}
                        {(r.info ?? 0) > 0 && <StatusPill>{r.info} INFO</StatusPill>}
                        {!r.critical && !r.major && !r.minor && !r.info && <StatusPill kind='shipped'>CLEAN</StatusPill>}
                      </div>
                    )}
                    {r?.reportUrl && (
                      <Button kind='ghost' sm block onClick={() => window.open(r.reportUrl, '_blank', 'noopener')}>
                        Download report (Markdown)
                      </Button>
                    )}
                  </Card>
                );
              })}
            </>
          )
        )}

        {sub === 'docs' && (
          <>
            {(docs || []).length === 0 ? (
              <Card>
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
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
                    meta={updated ? <span>updated {new Date(updated).toLocaleDateString()}</span> : <span>fresh</span>}
                    trailing={
                      <div style={{display: 'flex', gap: 4}}>
                        <Button kind='secondary' sm onClick={() => downloadDoc(dt as 'EXPLANATION' | 'SETUP')}>Download</Button>
                        <Button kind='ghost' sm onClick={() => generateDoc(dt as 'EXPLANATION' | 'SETUP')}>Regen</Button>
                      </div>
                    }
                  />
                );
              })
            )}
            <div style={{display: 'flex', gap: 8, marginTop: 8}}>
              <Button kind='secondary' block sm onClick={() => generateDoc('EXPLANATION')} disabled={busy === 'gen-EXPLANATION'}>
                {busy === 'gen-EXPLANATION' ? 'Generating…' : 'Generate EXPLANATION.md'}
              </Button>
              <Button kind='secondary' block sm onClick={() => generateDoc('SETUP')} disabled={busy === 'gen-SETUP'}>
                {busy === 'gen-SETUP' ? 'Generating…' : 'Generate SETUP.md'}
              </Button>
            </div>
          </>
        )}
      </Feed>
    </>
  );
}
