import {useEffect, useMemo, useState} from 'react';
import {useParams} from 'react-router-dom';
import {TopBar, SectionHeader, IconButton} from '@/signal/components/Chrome';
import {Feed, Row, Card, KvList, TabsStrip, Button} from '@/signal/components/Primitives';
import {ScoreCard, PrCard, TaskLine} from '@/signal/components/Compose';
import {Share} from '@/signal/icons/icons';
import {Glossary} from '@/signal/components/Glossary';
import * as api from '@/lib/api';

type ResultTab = 'overview' | 'tasks' | 'cost' | 'pr' | 'validation';

function shortStatusReason(r?: string | null): string {
  if (!r) return '—';
  return r.toUpperCase().replace(/_/g, ' ');
}

export default function ResultPage() {
  const {runId, runShort} = useParams<{runId?: string; runShort?: string}>();
  const id = runId || runShort;
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [tab, setTab] = useState<ResultTab>('overview');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [msg, setMsg] = useState('');

  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    Promise.allSettled([api.getRun(id), api.getValidationReport(id)]).then(([r, vr]) => {
      if (cancelled) return;
      if (r.status === 'fulfilled') setRun(r.value);
      else setErr('Failed to load run.');
      if (vr.status === 'fulfilled') setReport(vr.value);
    });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const score = (run as unknown as {verifiedScore?: number})?.verifiedScore ??
    (run as unknown as {accuracyScore?: number})?.accuracyScore ?? 0;
  const status = run?.status as string | undefined;
  const userRequest = run?.userRequest || '';
  const totalCost = (run as unknown as {actualCostUsd?: number})?.actualCostUsd ?? 0;
  const prUrl = (run as unknown as {scmPrUrl?: string; githubPrUrl?: string})?.scmPrUrl ||
    (run as unknown as {githubPrUrl?: string})?.githubPrUrl;
  const statusReason = (run as unknown as {statusReason?: string})?.statusReason;
  const verdict = score >= 90 ? 'Shippable' : score >= 70 ? 'Needs review' : 'Failed';
  const tasks = run?.tasks || [];

  const reportAny = (report || {}) as unknown as {
    buildStatus?: string;
    testsPassed?: number;
    testsTotal?: number;
    coverageDelta?: number;
    reviewerStatus?: string;
    securityStatus?: string;
    architectureStatus?: string;
    statusReason?: string;
    issues?: api.ReportIssue[];
  };

  const tokensTotal = (run?.totalInputTokens ?? 0) + (run?.totalOutputTokens ?? 0);
  const elapsed = useMemo(() => {
    if (!run?.startedAt) return '—';
    const t0 = new Date(run.startedAt).getTime();
    const t1 = run.completedAt ? new Date(run.completedAt).getTime() : Date.now();
    if (!t0 || !t1) return '—';
    const m = Math.floor((t1 - t0) / 60000);
    const s = Math.floor(((t1 - t0) % 60000) / 1000);
    return `${m}m ${s}s`;
  }, [run?.startedAt, run?.completedAt]);

  async function downloadPdf() {
    if (!id) return;
    setBusy(true);
    setErr('');
    try {
      await api.downloadValidationReportPdf(id);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Download failed.');
    } finally {
      setBusy(false);
    }
  }

  async function share() {
    if (!id) return;
    try {
      const r = await api.shareValidationReport(id);
      const token = (r as unknown as {shareToken?: string; token?: string}).shareToken ||
        (r as unknown as {token?: string}).token;
      if (token) {
        const url = `${window.location.origin}/shared/${token}`;
        await navigator.clipboard.writeText(url).catch(() => undefined);
        setMsg('Share URL copied to clipboard.');
      } else {
        setMsg('Share link created.');
      }
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Share failed.');
    }
  }

  async function downloadWorkspace() {
    if (!id) return;
    try {
      await api.downloadWorkspace(id);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Workspace download failed.');
    }
  }

  async function retryReport() {
    if (!id) return;
    try {
      await api.retryValidationReport(id);
      setMsg('Validation report retry queued.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Retry failed.');
    }
  }

  async function startPreview() {
    if (!id) return;
    try {
      await api.startPreview(id);
      setMsg('Preview starting. Check Deployments in 30s.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Preview start failed.');
    }
  }

  async function pushScm() {
    if (!id) return;
    try {
      await api.pushToScm(id);
      setMsg('Push triggered.');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Push failed.');
    }
  }

  if (!id) {
    return (
      <>
        <TopBar title='Run' sub='no run id' back backTo='/home' />
        <Feed>
          <Card><p style={{fontSize: 14, color: 'var(--signal-text-dim)', margin: 0}}>No run selected. <a href='/runs' style={{color: 'var(--signal-coral)'}}>Browse runs →</a></p></Card>
        </Feed>
      </>
    );
  }

  return (
    <>
      <TopBar
        title={`R-${id.slice(0, 6)}`}
        sub={status ? `${status.toLowerCase()} · ${(userRequest || '').slice(0, 60)}` : 'Loading…'}
        back
        backTo='/runs'
        actions={
          <>
            <IconButton ariaLabel='Download' onClick={downloadWorkspace}>
              <svg viewBox='0 0 24 24' style={{width: 18, height: 18}} fill='none' stroke='currentColor' strokeWidth={2} strokeLinecap='round'>
                <path d='M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8' />
                <polyline points='16 6 12 2 8 6' />
                <line x1='12' y1='2' x2='12' y2='15' />
              </svg>
            </IconButton>
            <IconButton ariaLabel='Share' onClick={share}>
              <Share size={18} />
            </IconButton>
          </>
        }
      />
      <Feed>
        {err && <div style={{background: 'var(--signal-coral-soft)', color: 'var(--signal-coral-text)', border: '1px solid var(--signal-coral)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{err}</div>}
        {msg && <div style={{background: 'var(--signal-mint-soft)', color: 'var(--signal-mint-text-on-light)', border: '1px solid var(--signal-mint)', borderRadius: 10, padding: '10px 14px', marginBottom: 12, fontSize: 13}}>{msg}</div>}

        <TabsStrip
          items={[
            {label: 'Overview', active: tab === 'overview', onClick: () => setTab('overview')},
            {label: `Tasks · ${tasks.length}`, active: tab === 'tasks', onClick: () => setTab('tasks')},
            {label: 'Cost', active: tab === 'cost', onClick: () => setTab('cost')},
            {label: prUrl ? 'PR' : 'PR · —', active: tab === 'pr', onClick: () => setTab('pr')},
            {label: 'Validation', active: tab === 'validation', onClick: () => setTab('validation')},
          ]}
        />

        {tab === 'overview' && (
          <>
            <ScoreCard
              verdict={verdict}
              score={Math.round(score)}
              label={`Verified score${prUrl ? ' · PR ready' : ''}`}
              cells={[
                {v: status || '—', l: 'Status'},
                {v: `$${totalCost.toFixed(2)}`, l: 'Total cost'},
                {v: prUrl ? 'open' : '—', l: 'Pull request'},
              ]}
            />

            <SectionHeader>Signal breakdown</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Build', v: <span style={{color: reportAny.buildStatus === 'PASSING' ? 'var(--signal-mint-text-on-light)' : 'var(--signal-coral-text)'}}>{reportAny.buildStatus ?? '—'}</span>},
                  {k: 'Tests', v: reportAny.testsTotal != null ? `${reportAny.testsPassed ?? 0} of ${reportAny.testsTotal}` : '—'},
                  {k: 'Coverage', v: reportAny.coverageDelta != null ? `${reportAny.coverageDelta >= 0 ? '+' : ''}${reportAny.coverageDelta.toFixed(1)}%` : '—'},
                  {k: <Glossary term='wiring pass'>Reviewer</Glossary>, v: <span>{reportAny.reviewerStatus ?? '—'}</span>},
                  {k: 'Security', v: <span style={{color: reportAny.securityStatus === 'CLEAN' ? 'var(--signal-mint-text-on-light)' : 'var(--signal-coral-text)'}}>{reportAny.securityStatus ?? '—'}</span>},
                  {k: 'Architecture', v: <span style={{color: reportAny.architectureStatus === 'CLEAN' ? 'var(--signal-mint-text-on-light)' : 'var(--signal-coral-text)'}}>{reportAny.architectureStatus ?? '—'}</span>},
                  {k: <Glossary term='status reason'>Status reason</Glossary>, v: shortStatusReason(reportAny.statusReason || statusReason)},
                ]}
              />
            </Card>

            <SectionHeader>What now</SectionHeader>
            <Row to='/compose' dot='signal' title='Iterate on this run' meta={<span>Reuse the spec in the composer</span>} showChev />
            <Row dot='lavender' title='Preview deploy' meta={<span>Cloud Run · 5min TTL</span>} trailing={<Button kind='secondary' sm onClick={startPreview}>Start</Button>} />
            <Row dot='lavender' title='Download report (PDF)' meta={<span>signed validation report</span>} trailing={<Button kind='secondary' sm onClick={downloadPdf} disabled={busy}>{busy ? '…' : 'PDF'}</Button>} />
            <Row dot='lavender' title='Share read-only report' meta={<span>public link · 30d expiry</span>} trailing={<Button kind='secondary' sm onClick={share}>Share</Button>} />
            <Row dot='lavender' title='Download workspace' meta={<span>full code · zip</span>} trailing={<Button kind='secondary' sm onClick={downloadWorkspace}>Download</Button>} />
          </>
        )}

        {tab === 'tasks' && (
          <Card style={{padding: 0}}>
            {tasks.length === 0 ? (
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 14}}>No tasks recorded.</p>
            ) : (
              tasks.map((t, i) => {
                const tAny = t as unknown as {taskId?: string; title?: string; status?: string; complexity?: string; iterations?: number};
                return (
                  <TaskLine
                    key={tAny.taskId || i}
                    status={tAny.status === 'COMPLETED' || tAny.status === 'DONE' ? 'done' : tAny.status === 'EXECUTING' || tAny.status === 'RUNNING' ? 'live' : 'todo'}
                    title={tAny.title || `Task ${i + 1}`}
                    sub={tAny.iterations ? `${tAny.iterations} iters` : undefined}
                    tag={tAny.complexity || 'AUTO'}
                  />
                );
              })
            )}
          </Card>
        )}

        {tab === 'cost' && (
          <>
            <Card>
              <KvList
                pairs={[
                  {k: 'Total cost', v: `$${totalCost.toFixed(2)}`},
                  {k: 'Hard cap', v: (run as unknown as {hardCapUsd?: number})?.hardCapUsd != null ? `$${(run as unknown as {hardCapUsd?: number}).hardCapUsd!.toFixed(2)}` : '—'},
                  {k: 'Credits charged', v: (run as unknown as {creditsCharged?: number})?.creditsCharged ?? '—'},
                  {k: 'Estimate range', v: `$${(run as unknown as {costEstimateLow?: number})?.costEstimateLow?.toFixed(2) ?? '—'} – $${(run as unknown as {costEstimateHigh?: number})?.costEstimateHigh?.toFixed(2) ?? '—'}`},
                  {k: 'Input tokens', v: (run?.totalInputTokens ?? 0).toLocaleString()},
                  {k: 'Output tokens', v: (run?.totalOutputTokens ?? 0).toLocaleString()},
                  {k: 'Total tokens', v: tokensTotal.toLocaleString()},
                  {k: 'Elapsed', v: elapsed},
                ]}
              />
            </Card>
            <SectionHeader>Per-phase cost</SectionHeader>
            <Card>
              <KvList
                pairs={[
                  {k: 'Classifier', v: (run as unknown as {classifierCostUsd?: number})?.classifierCostUsd != null ? `$${(run as unknown as {classifierCostUsd: number}).classifierCostUsd.toFixed(4)}` : '—'},
                  {k: 'Outline', v: (run as unknown as {outlineCostUsd?: number})?.outlineCostUsd != null ? `$${(run as unknown as {outlineCostUsd: number}).outlineCostUsd.toFixed(4)}` : '—'},
                  {k: 'Expansion', v: (run as unknown as {expansionCostUsd?: number})?.expansionCostUsd != null ? `$${(run as unknown as {expansionCostUsd: number}).expansionCostUsd.toFixed(4)}` : '—'},
                  {k: 'Coherence', v: (run as unknown as {coherenceCostUsd?: number})?.coherenceCostUsd != null ? `$${(run as unknown as {coherenceCostUsd: number}).coherenceCostUsd.toFixed(4)}` : '—'},
                ]}
              />
            </Card>
          </>
        )}

        {tab === 'pr' && (
          prUrl ? (
            <PrCard
              label='PR · OPEN'
              title={userRequest ? userRequest.slice(0, 80) : 'Open pull request'}
              repo={prUrl.replace(/^https?:\/\/[^/]+\//, '').replace(/\/pull\//, '#')}
              ciStatus={<span className='ci-ok'>● open</span>}
              actions={
                <button type='button' onClick={() => window.open(prUrl, '_blank', 'noopener')}>
                  Open on GitHub
                </button>
              }
            />
          ) : (
            <Card>
              <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0, lineHeight: 1.5}}>
                No PR yet — the run is{' '}
                <strong style={{color: 'var(--signal-ink)'}}>{status?.toLowerCase() || 'in progress'}</strong>. PRs open automatically once the executor finishes pushing.
              </p>
              <div style={{display: 'flex', gap: 6, marginTop: 12}}>
                <Button kind='secondary' sm onClick={pushScm}>Push to SCM</Button>
              </div>
            </Card>
          )
        )}

        {tab === 'validation' && (
          <>
            <Card>
              {report === null ? (
                <p style={{fontSize: 13.5, color: 'var(--signal-text-dim)', margin: 0}}>No validation report. <Button kind='ghost' sm onClick={retryReport}>Retry generation</Button></p>
              ) : (
                <KvList
                  pairs={[
                    {k: 'Build', v: reportAny.buildStatus ?? '—'},
                    {k: 'Tests', v: reportAny.testsTotal != null ? `${reportAny.testsPassed}/${reportAny.testsTotal}` : '—'},
                    {k: 'Coverage Δ', v: reportAny.coverageDelta != null ? `${reportAny.coverageDelta.toFixed(1)}%` : '—'},
                    {k: 'Reviewer', v: reportAny.reviewerStatus ?? '—'},
                    {k: 'Security', v: reportAny.securityStatus ?? '—'},
                    {k: 'Architecture', v: reportAny.architectureStatus ?? '—'},
                  ]}
                />
              )}
            </Card>
            {(reportAny.issues || []).length > 0 && (
              <>
                <SectionHeader>Issues · {reportAny.issues!.length}</SectionHeader>
                {reportAny.issues!.slice(0, 20).map((iss, i) => {
                  const iAny = iss as unknown as {id?: string; title?: string; description?: string; severity?: string; file?: string; line?: number; category?: string};
                  const sev = (iAny.severity || 'MINOR').toUpperCase();
                  return (
                    <Row
                      key={iAny.id || i}
                      dot={sev === 'CRITICAL' ? 'coral' : sev === 'MAJOR' ? 'signal' : 'lavender'}
                      title={iAny.title || iAny.description || `Issue ${i + 1}`}
                      meta={
                        <>
                          {iAny.file && <span className='mono'>{iAny.file}{iAny.line ? `:${iAny.line}` : ''}</span>}
                          {iAny.category && <><span>·</span><span>{iAny.category}</span></>}
                        </>
                      }
                      trailing={sev}
                    />
                  );
                })}
              </>
            )}
            <div style={{display: 'flex', gap: 6, marginTop: 12}}>
              <Button kind='secondary' block onClick={downloadPdf} disabled={busy}>{busy ? '…' : 'Download PDF'}</Button>
              <Button kind='ghost' block onClick={retryReport}>Retry</Button>
            </div>
          </>
        )}
      </Feed>
    </>
  );
}
