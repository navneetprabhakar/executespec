import {useEffect, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {
  IdeBadge,
  IdeCodeViewer,
  IdeFileTree,
  FileIcon,
  ResizeHandle,
  type DiffLine,
} from '@/ide/IdeKit';

type Verdict = {label: string; kind: 'ok' | 'warn' | 'bad'};
function verdictFor(score: number | null | undefined, status?: string | null): Verdict {
  const s = (status || '').toUpperCase();
  if (s === 'FAILED' || s === 'ERROR' || s === 'CANCELLED') return {label: 'Failed', kind: 'bad'};
  if (score == null) return {label: s === 'COMPLETE' ? 'Complete' : 'In progress', kind: 'warn'};
  if (score >= 85) return {label: 'Shippable', kind: 'ok'};
  if (score >= 70) return {label: 'Needs review', kind: 'warn'};
  return {label: 'Failed', kind: 'bad'};
}
const SEV: Record<string, {l: string; k: 'ok' | 'warn' | 'bad'}> = {
  WOULD_BREAK_BUILD: {l: 'BUILD', k: 'bad'},
  WOULD_FAIL_REVIEW: {l: 'REVIEW', k: 'warn'},
  WOULD_CAUSE_BUG: {l: 'BUG', k: 'warn'},
  WOULD_FAIL_LINT: {l: 'LINT', k: 'ok'},
};
const usd = (n?: number | null) => (n == null ? '—' : `$${n.toFixed(2)}`);
const num = (n?: number | null) => (n == null ? '—' : n.toLocaleString());
function fmtDur(ms?: number | null) {
  if (!ms) return '—';
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

const TABS = ['Overview', 'Code', 'Tasks', 'Validation', 'Cost', 'PR', 'Tests'] as const;
type Tab = (typeof TABS)[number];

function Kv({k, v}: {k: string; v: React.ReactNode}) {
  return (
    <div className='ide-kv'>
      <span>{k}</span>
      <span>{v}</span>
    </div>
  );
}

export default function IdeRunResult() {
  const params = useParams();
  const navigate = useNavigate();
  const runId = params.runId || params.runShort || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [tests, setTests] = useState<api.TestReportData | null>(null);
  const [files, setFiles] = useState<string[]>([]);
  const [openFiles, setOpenFiles] = useState<string[]>([]);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [cache, setCache] = useState<Record<string, {content: string; diff: DiffLine[] | null}>>(
    {},
  );
  const [filesWidth, setFilesWidth] = useState(240);
  const [tab, setTab] = useState<Tab>('Overview');
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState('');

  useEffect(() => {
    if (!runId) return;
    api
      .getRun(runId)
      .then(setRun)
      .catch(() => {});
    api
      .getValidationReport(runId)
      .then(setReport)
      .catch(() => setReport(null));
    api
      .getFiles(runId)
      .then((f) => {
        setFiles(f);
        if (f.length) openFile(f[0]);
      })
      .catch(() => setFiles([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);

  useEffect(() => {
    if (tab === 'Tests' && !tests && runId)
      api
        .getTestReport(runId)
        .then(setTests)
        .catch(() => setTests(null));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  async function openFile(path: string) {
    setActiveFile(path);
    setOpenFiles((o) => (o.includes(path) ? o : [...o, path]));
    if (cache[path]) return;
    try {
      const d = await api.getFileDiff(runId, path);
      const diff: DiffLine[] | null =
        d.status === 'ADDED'
          ? (d.head || '')
              .replace(/\n$/, '')
              .split('\n')
              .map((t) => ({sign: '+', text: t}))
          : null;
      setCache((c) => ({...c, [path]: {content: d.head ?? '', diff}}));
    } catch {
      try {
        const content = await api.getFile(runId, path);
        setCache((c) => ({...c, [path]: {content, diff: null}}));
      } catch {
        setCache((c) => ({...c, [path]: {content: '// could not load', diff: null}}));
      }
    }
  }
  function closeFile(path: string, e?: React.MouseEvent) {
    e?.stopPropagation();
    setOpenFiles((o) => {
      const next = o.filter((f) => f !== path);
      if (activeFile === path) setActiveFile(next[next.length - 1] ?? null);
      return next;
    });
  }

  async function action(name: string, fn: () => Promise<unknown>, ok: (r: unknown) => string) {
    setBusy(name);
    setMsg('');
    try {
      const r = await fn();
      setMsg(ok(r));
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Failed.');
    } finally {
      setBusy('');
    }
  }
  function tryProvider(p: string) {
    if (!run) return;
    action(
      'reprovider',
      () =>
        api.createRun({
          userRequest: run.userRequest || '',
          aiProvider: p,
          executionMode: 'PRODUCTION',
          projectId: run.projectId || undefined,
        }),
      (r) => {
        const id = (r as {runId?: string})?.runId;
        if (id) navigate(`/live/${id}`);
        return 'Re-run started.';
      },
    );
  }

  const score = report?.verifiedScore ?? null;
  const v = verdictFor(score, run?.status);
  const active = activeFile ? cache[activeFile] : null;
  const issues = report?.issues ?? [];

  return (
    <div style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column'}}>
      {/* tab strip */}
      <div className='ide-rtabs'>
        {TABS.map((t) => {
          const badge =
            t === 'Code'
              ? files.length
              : t === 'Tasks'
                ? run?.tasks?.length
                : t === 'Validation'
                  ? issues.length
                  : null;
          return (
            <button
              key={t}
              className={`ide-rtab${tab === t ? ' active' : ''}`}
              onClick={() => setTab(t)}
            >
              {t}
              {badge ? <span className='ide-rtab-badge'>{badge}</span> : null}
            </button>
          );
        })}
      </div>

      <div style={{flex: 1, minHeight: 0, position: 'relative', overflow: 'hidden'}}>
        {/* ── Overview ── */}
        {tab === 'Overview' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
              {run ? run.runTitle || (run.userRequest || '').slice(0, 70) || 'Run' : 'Loading…'}
            </div>
            <div style={{display: 'flex', gap: 8, alignItems: 'center', margin: '8px 0 18px'}}>
              <IdeBadge kind={v.kind}>{v.label}</IdeBadge>
              {score != null && (
                <span style={{fontSize: 28, fontWeight: 700, color: 'var(--ide-fg-bright)'}}>
                  {score}
                  <span style={{fontSize: 13, color: 'var(--ide-fg-muted)'}}> / 100</span>
                </span>
              )}
              <span style={{flex: 1}} />
              {run?.statusReason && (
                <span style={{color: 'var(--ide-fg-muted)', fontSize: 12}}>{run.statusReason}</span>
              )}
            </div>

            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}

            <div style={{display: 'flex', gap: 12, flexWrap: 'wrap'}}>
              <div className='ide-card' style={{flex: 1, minWidth: 240}}>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Signals
                </div>
                <Kv
                  k='Build'
                  v={
                    report
                      ? report.buildBreakers > 0
                        ? `${report.buildBreakers} breaker(s)`
                        : 'green'
                      : '—'
                  }
                />
                <Kv k='Tests' v={tests ? `${tests.passed}/${tests.totalTests}` : '—'} />
                <Kv
                  k='Reviewer'
                  v={
                    report
                      ? report.reviewFailures > 0
                        ? `${report.reviewFailures} unresolved`
                        : 'clean'
                      : '—'
                  }
                />
                <Kv k='Issues' v={issues.length} />
                <Kv k='Files' v={report?.filesGenerated ?? files.length} />
                <Kv k='Pipeline' v={report?.pipelineResult ?? '—'} />
              </div>
              <div className='ide-card' style={{flex: 1, minWidth: 240}}>
                <div className='ide-h2' style={{marginTop: 0}}>
                  Run
                </div>
                <Kv k='Status' v={run?.status ?? '—'} />
                <Kv k='Provider' v={(run?.aiProvider || '—').toLowerCase()} />
                <Kv
                  k='Worker'
                  v={
                    <span className='ide-mono' style={{fontSize: 11}}>
                      {run?.workerModel || '—'}
                    </span>
                  }
                />
                <Kv k='Cost' v={usd(run?.actualCostUsd)} />
                <Kv k='Credits' v={run?.creditsCharged ?? '—'} />
              </div>
            </div>

            {run?.userRequest && (
              <>
                <div className='ide-h2'>Submitted prompt</div>
                <div
                  className='ide-card ide-mono'
                  style={{fontSize: 12, lineHeight: 1.6, whiteSpace: 'pre-wrap'}}
                >
                  {run.userRequest}
                </div>
              </>
            )}

            <div className='ide-h2'>What now</div>
            <div style={{display: 'flex', gap: 8, flexWrap: 'wrap'}}>
              {run?.githubPrUrl ? (
                <a className='ide-btn' href={run.githubPrUrl} target='_blank' rel='noreferrer'>
                  ↗ Open PR
                </a>
              ) : (
                <button
                  className='ide-btn'
                  disabled={busy === 'push'}
                  onClick={() =>
                    action(
                      'push',
                      () => api.pushToScm(runId),
                      () => 'Pushed — PR opening.',
                    )
                  }
                >
                  {busy === 'push' ? 'Pushing…' : '⤴ Push to GitHub'}
                </button>
              )}
              <button
                className='ide-btn ghost'
                disabled={busy === 'preview'}
                onClick={() =>
                  action(
                    'preview',
                    () => api.startPreview(runId),
                    (r) => `Preview: ${(r as {url?: string})?.url || 'starting'}`,
                  )
                }
              >
                ▶ Preview
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'pdf'}
                onClick={() =>
                  action(
                    'pdf',
                    () => api.downloadValidationReportPdf(runId),
                    () => 'Report downloaded.',
                  )
                }
              >
                ⬇ Report PDF
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'share'}
                onClick={() =>
                  action(
                    'share',
                    () => api.shareValidationReport(runId),
                    (r) => `Shared: ${(r as {shareUrl?: string})?.shareUrl || 'link created'}`,
                  )
                }
              >
                ⌁ Share
              </button>
              {['anthropic', 'openai', 'gemini']
                .filter((p) => p !== (run?.aiProvider || '').toLowerCase())
                .map((p) => (
                  <button
                    key={p}
                    className='ide-btn ghost'
                    onClick={() => tryProvider(p.toUpperCase())}
                  >
                    ↻ Try {p}
                  </button>
                ))}
            </div>
          </div>
        )}

        {/* ── Code ── */}
        {tab === 'Code' && (
          <div className='ide-split'>
            <div className='ide-split-files' style={{width: filesWidth}}>
              <div className='ide-side-head'>
                Explorer {files.length ? `· ${files.length}` : ''}
              </div>
              {files.length ? (
                <IdeFileTree files={files} activePath={activeFile} onOpen={openFile} />
              ) : (
                <div className='ide-empty' style={{padding: 14}}>
                  No files
                </div>
              )}
            </div>
            <ResizeHandle
              onDelta={(dx) => setFilesWidth((w) => Math.max(160, Math.min(520, w + dx)))}
            />
            <div className='ide-split-main'>
              {openFiles.length > 0 && (
                <div className='ide-tabs'>
                  {openFiles.map((f) => (
                    <div
                      key={f}
                      className={`ide-tab${activeFile === f ? ' active' : ''}`}
                      onClick={() => setActiveFile(f)}
                      title={f}
                    >
                      <FileIcon name={f.split('/').pop() || f} />
                      {f.split('/').pop()}
                      <span className='ide-tab-close' onClick={(e) => closeFile(f, e)}>
                        ×
                      </span>
                    </div>
                  ))}
                </div>
              )}
              {active ? (
                <IdeCodeViewer
                  filename={activeFile || ''}
                  content={active.content}
                  diff={active.diff}
                />
              ) : (
                <div className='ide-empty'>
                  {files.length ? 'Select a file' : 'No generated files'}
                </div>
              )}
            </div>
          </div>
        )}

        {/* ── Tasks ── */}
        {tab === 'Tasks' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Tasks</div>
            {!run?.tasks?.length ? (
              <div className='ide-empty'>No tasks.</div>
            ) : (
              <div className='ide-card' style={{padding: 0}}>
                {run.tasks.map((t, i) => (
                  <div
                    key={t.taskId}
                    style={{
                      padding: '12px 16px',
                      borderBottom:
                        i < run.tasks.length - 1 ? '1px solid var(--ide-border)' : 'none',
                    }}
                  >
                    <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                      <IdeBadge
                        kind={
                          t.status === 'COMPLETE' || t.status === 'SUCCESS'
                            ? 'ok'
                            : t.status === 'FAILED'
                              ? 'bad'
                              : 'warn'
                        }
                      >
                        {t.status}
                      </IdeBadge>
                      <span style={{flex: 1, fontWeight: 500}}>{t.title}</span>
                      <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                        {fmtDur(t.durationMs)}
                      </span>
                      {(t.status === 'FAILED' ||
                        t.status === 'PARTIAL' ||
                        t.status === 'BLOCKED') && (
                        <button
                          className='ide-btn ghost'
                          style={{padding: '3px 8px'}}
                          disabled={busy === t.taskId}
                          onClick={() =>
                            action(
                              t.taskId,
                              () => api.retryTask(runId, t.taskId),
                              () => 'Retry started.',
                            )
                          }
                        >
                          Retry
                        </button>
                      )}
                    </div>
                    {t.failureReason && (
                      <div style={{fontSize: 12, color: 'var(--ide-red)', marginTop: 4}}>
                        {t.failureReason}
                      </div>
                    )}
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)', marginTop: 4}}>
                      {(t.provider || '').toLowerCase()} · {t.model || t.assignedModel} ·{' '}
                      {num(t.inputTokens + t.outputTokens)} tok
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* ── Validation ── */}
        {tab === 'Validation' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
              Validation
              {report && (
                <button
                  className='ide-btn ghost'
                  style={{marginLeft: 'auto', padding: '4px 10px'}}
                  onClick={() =>
                    action(
                      'pdf2',
                      () => api.downloadValidationReportPdf(runId),
                      () => 'Downloaded.',
                    )
                  }
                >
                  ⬇ PDF
                </button>
              )}
            </div>
            {!report ? (
              <div className='ide-empty'>No validation report.</div>
            ) : (
              <>
                <div className='ide-card'>
                  <Kv k='Pipeline' v={report.pipelineResult} />
                  <Kv k='Files generated' v={report.filesGenerated} />
                  <Kv k='Build breakers' v={report.buildBreakers} />
                  <Kv k='Review failures' v={report.reviewFailures} />
                  <Kv k='Issues caught' v={report.issuesCaught} />
                </div>
                <div className='ide-h2'>Issues · {issues.length}</div>
                {issues.length === 0 ? (
                  <div className='ide-empty'>No issues flagged.</div>
                ) : (
                  <div className='ide-card' style={{padding: 0}}>
                    {issues.map((is, i) => {
                      const sv = SEV[is.severity] ?? {l: is.severity, k: 'warn' as const};
                      return (
                        <div
                          key={i}
                          style={{
                            padding: '11px 16px',
                            borderBottom:
                              i < issues.length - 1 ? '1px solid var(--ide-border)' : 'none',
                          }}
                        >
                          <div style={{display: 'flex', gap: 8, alignItems: 'center'}}>
                            <IdeBadge kind={sv.k}>{sv.l}</IdeBadge>
                            {is.filePath && (
                              <span
                                className='ide-mono'
                                style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                              >
                                {is.filePath}
                              </span>
                            )}
                            <span
                              style={{
                                fontSize: 10,
                                color: 'var(--ide-fg-muted)',
                                marginLeft: 'auto',
                              }}
                            >
                              {is.stage}
                            </span>
                          </div>
                          <div style={{fontSize: 13, marginTop: 4}}>{is.description}</div>
                          {is.fix && (
                            <div style={{fontSize: 12, color: 'var(--ide-green)', marginTop: 3}}>
                              Fix: {is.fix}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {/* ── Cost ── */}
        {tab === 'Cost' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Cost</div>
            <div className='ide-card'>
              <Kv k='Actual cost' v={usd(run?.actualCostUsd)} />
              <Kv k='Hard cap' v={usd(run?.hardCapUsd)} />
              <Kv k='Credits charged' v={run?.creditsCharged ?? '—'} />
              <Kv
                k='Estimate range'
                v={
                  run?.costEstimateLow != null
                    ? `${usd(run.costEstimateLow)} – ${usd(run.costEstimateHigh)}`
                    : '—'
                }
              />
              <Kv k='Input tokens' v={num(run?.totalInputTokens)} />
              <Kv k='Output tokens' v={num(run?.totalOutputTokens)} />
            </div>
            <div className='ide-h2'>Per phase</div>
            <div className='ide-card'>
              <Kv k='Classifier' v={usd(run?.classifierCostUsd)} />
              <Kv k='Outline' v={usd(run?.outlineCostUsd)} />
              <Kv k='Expansion' v={usd(run?.expansionCostUsd)} />
            </div>
          </div>
        )}

        {/* ── PR ── */}
        {tab === 'PR' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Pull request</div>
            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}
            {run?.githubPrUrl ? (
              <div className='ide-card'>
                <div
                  className='ide-mono'
                  style={{fontSize: 12, wordBreak: 'break-all', marginBottom: 10}}
                >
                  {run.githubPrUrl}
                </div>
                <a className='ide-btn' href={run.githubPrUrl} target='_blank' rel='noreferrer'>
                  ↗ Open on GitHub
                </a>
              </div>
            ) : (
              <div className='ide-card'>
                <div style={{marginBottom: 10, color: 'var(--ide-fg-muted)'}}>
                  No PR yet for this run.
                </div>
                <button
                  className='ide-btn'
                  disabled={busy === 'push'}
                  onClick={() =>
                    action(
                      'push',
                      () => api.pushToScm(runId),
                      () => 'Pushed — PR opening.',
                    )
                  }
                >
                  {busy === 'push' ? 'Pushing…' : '⤴ Push to GitHub'}
                </button>
              </div>
            )}
          </div>
        )}

        {/* ── Tests ── */}
        {tab === 'Tests' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1' style={{display: 'flex', alignItems: 'center', gap: 10}}>
              Tests
              <button
                className='ide-btn ghost'
                style={{marginLeft: 'auto', padding: '4px 10px'}}
                disabled={busy === 'runtests'}
                onClick={() =>
                  action(
                    'runtests',
                    () => api.runTests(runId),
                    () => 'Test run queued.',
                  )
                }
              >
                ↻ Re-run tests
              </button>
            </div>
            {!tests ? (
              <div className='ide-empty'>No test report yet.</div>
            ) : (
              <>
                <div className='ide-card' style={{display: 'flex', gap: 18}}>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700}}>
                      {tests.passed}/{tests.totalTests}
                    </div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>passed</div>
                  </div>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700, color: 'var(--ide-red)'}}>
                      {tests.failed}
                    </div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>failed</div>
                  </div>
                  <div>
                    <div style={{fontSize: 22, fontWeight: 700}}>{tests.errors}</div>
                    <div style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>errors</div>
                  </div>
                  <div
                    style={{
                      marginLeft: 'auto',
                      alignSelf: 'center',
                      fontSize: 12,
                      color: 'var(--ide-fg-muted)',
                    }}
                  >
                    {fmtDur(tests.executionDurationMs)}
                  </div>
                </div>
                {tests.rootCauseGroups?.length > 0 && (
                  <>
                    <div className='ide-h2'>Root causes · {tests.rootCauseGroups.length}</div>
                    <div className='ide-card' style={{padding: 0}}>
                      {tests.rootCauseGroups.map((g, i) => (
                        <div
                          key={i}
                          style={{
                            padding: '11px 16px',
                            borderBottom:
                              i < tests.rootCauseGroups.length - 1
                                ? '1px solid var(--ide-border)'
                                : 'none',
                            fontSize: 13,
                          }}
                        >
                          {(g as {signature?: string}).signature || 'Failure group'}
                        </div>
                      ))}
                    </div>
                  </>
                )}
              </>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
