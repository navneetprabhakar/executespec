import {useEffect, useMemo, useState} from 'react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {IdeBadge, IdeCodeViewer, statusKind, type DiffLine} from '@/ide/IdeKit';

function fmtWhen(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '—' : d.toLocaleString();
}
function runLabel(r: {
  runTitle?: string | null;
  userRequest?: string | null;
  runId: string;
}): string {
  return (r.runTitle || r.userRequest || r.runId).slice(0, 80);
}

// ───────────────────────── Welcome / workspace home ─────────────────────────
export function IdeWelcome() {
  const [runs, setRuns] = useState<api.RunSummary[] | null>(null);
  const [projects, setProjects] = useState<api.ProjectDto[]>([]);

  useEffect(() => {
    api
      .getRuns({size: 12})
      .then((r) => setRuns(r.content ?? []))
      .catch(() => setRuns([]));
    api
      .getProjects()
      .then(setProjects)
      .catch(() => setProjects([]));
  }, []);

  return (
    <div className='ide-pane'>
      <div className='ide-h1'>Workspace</div>
      <div className='ide-sub'>Spec-driven runs across your repos. Press ⌘K to jump anywhere.</div>

      <div style={{display: 'flex', gap: 10, marginBottom: 8}}>
        <Link className='ide-btn' to='/compose'>
          ＋ New spec
        </Link>
        <Link className='ide-btn ghost' to='/runs'>
          ◎ All runs
        </Link>
      </div>

      <div className='ide-h2'>Recent runs</div>
      {runs === null ? (
        <div className='ide-empty'>Loading runs…</div>
      ) : runs.length === 0 ? (
        <div className='ide-empty'>No runs yet — start with a new spec.</div>
      ) : (
        <div className='ide-grid'>
          {runs.map((r) => (
            <Link key={r.runId} className='ide-run-card' to={`/result/${r.runId}`}>
              <div className='ide-run-title'>{runLabel(r)}</div>
              <div className='ide-run-meta'>
                <IdeBadge kind={statusKind(r.status)}>{r.status}</IdeBadge>
                {r.aiProvider && <span>{r.aiProvider.toLowerCase()}</span>}
                <span>{fmtWhen(r.startedAt)}</span>
              </div>
            </Link>
          ))}
        </div>
      )}

      {projects.length > 0 && (
        <>
          <div className='ide-h2'>Projects</div>
          <div className='ide-grid'>
            {projects.map((p) => (
              <Link key={p.id} className='ide-run-card' to={`/projects/${p.id}`}>
                <div className='ide-run-title'>▤ {p.name}</div>
                <div className='ide-run-meta'>{p.description || 'No description'}</div>
              </Link>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

// ───────────────────────── Run result + code viewer (flagship) ──────────────
export function IdeRunResult() {
  const params = useParams();
  const runId = params.runId || params.runShort || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);
  const [report, setReport] = useState<api.ValidationReportData | null>(null);
  const [files, setFiles] = useState<string[]>([]);
  const [openFiles, setOpenFiles] = useState<string[]>([]);
  const [activeFile, setActiveFile] = useState<string | null>(null);
  const [fileCache, setFileCache] = useState<
    Record<string, {content: string; diff: DiffLine[] | null}>
  >({});
  const [err, setErr] = useState('');

  useEffect(() => {
    if (!runId) return;
    api
      .getRun(runId)
      .then(setRun)
      .catch((e) => setErr(String(e?.message || e)));
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

  async function openFile(path: string) {
    setActiveFile(path);
    setOpenFiles((o) => (o.includes(path) ? o : [...o, path]));
    if (fileCache[path]) return;
    try {
      const d = await api.getFileDiff(runId, path);
      const diff: DiffLine[] | null =
        d.status === 'ADDED'
          ? (d.head || '')
              .replace(/\n$/, '')
              .split('\n')
              .map((t) => ({sign: '+', text: t}))
          : null;
      setFileCache((c) => ({...c, [path]: {content: d.head ?? '', diff}}));
    } catch {
      try {
        const content = await api.getFile(runId, path);
        setFileCache((c) => ({...c, [path]: {content, diff: null}}));
      } catch {
        setFileCache((c) => ({...c, [path]: {content: '// could not load file', diff: null}}));
      }
    }
  }

  const active = activeFile ? fileCache[activeFile] : null;

  return (
    <div className='ide-split'>
      {/* file explorer */}
      <div className='ide-split-files'>
        <div className='ide-side-head'>Files {files.length ? `(${files.length})` : ''}</div>
        {files.length === 0 ? (
          <div className='ide-empty' style={{padding: '16px 12px'}}>
            {err ? 'Run not found' : 'No files'}
          </div>
        ) : (
          files.map((f) => (
            <button
              key={f}
              className={`ide-tree-item${activeFile === f ? ' active' : ''}`}
              onClick={() => openFile(f)}
              title={f}
            >
              <span className='ide-tree-ico'>📄</span>
              {f.split('/').pop()}
            </button>
          ))
        )}
      </div>

      {/* editor */}
      <div className='ide-split-main'>
        {openFiles.length > 0 && (
          <div className='ide-tabs'>
            {openFiles.map((f) => (
              <div
                key={f}
                className={`ide-tab${activeFile === f ? ' active' : ''}`}
                onClick={() => setActiveFile(f)}
              >
                <span className='ide-tab-ico'>📄</span>
                {f.split('/').pop()}
              </div>
            ))}
          </div>
        )}
        {active ? (
          <IdeCodeViewer filename={activeFile || ''} content={active.content} diff={active.diff} />
        ) : (
          <div className='ide-empty'>{files.length ? 'Select a file' : 'No generated files'}</div>
        )}
      </div>

      {/* run info panel */}
      <div className='ide-split-info'>
        <div className='ide-h1' style={{fontSize: 16}}>
          {run ? runLabel(run) : 'Loading…'}
        </div>
        {run && (
          <>
            <div style={{margin: '10px 0', display: 'flex', gap: 6, flexWrap: 'wrap'}}>
              <IdeBadge kind={statusKind(run.status)}>{run.status}</IdeBadge>
              {report?.verifiedScore != null && (
                <IdeBadge
                  kind={
                    report.verifiedScore >= 85 ? 'ok' : report.verifiedScore >= 70 ? 'warn' : 'bad'
                  }
                >
                  Score {report.verifiedScore}
                </IdeBadge>
              )}
            </div>
            {run.runSummary && (
              <div style={{fontSize: 13, lineHeight: 1.55, marginBottom: 12}}>{run.runSummary}</div>
            )}
            <div className='ide-kv'>
              <span>Provider</span>
              <span>{(run.aiProvider || '—').toLowerCase()}</span>
            </div>
            <div className='ide-kv'>
              <span>Worker</span>
              <span className='ide-mono' style={{fontSize: 11}}>
                {run.workerModel || '—'}
              </span>
            </div>
            <div className='ide-kv'>
              <span>Files</span>
              <span>{report?.filesGenerated ?? files.length}</span>
            </div>
            {run.statusReason && (
              <div className='ide-kv'>
                <span>Reason</span>
                <span>{run.statusReason}</span>
              </div>
            )}
            <div className='ide-kv'>
              <span>Started</span>
              <span>{fmtWhen(run.startedAt)}</span>
            </div>
            {report && (
              <div className='ide-kv'>
                <span>Pipeline</span>
                <span>{report.pipelineResult}</span>
              </div>
            )}
            {run.githubPrUrl && (
              <a
                className='ide-btn'
                href={run.githubPrUrl}
                target='_blank'
                rel='noreferrer'
                style={{marginTop: 14}}
              >
                ↗ Open pull request
              </a>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// ───────────────────────── Compose (new spec) ───────────────────────────────
export function IdeCompose() {
  const navigate = useNavigate();
  const [spec, setSpec] = useState('');
  const [provider, setProvider] = useState('ANTHROPIC');
  const [mode, setMode] = useState<'PROTOTYPE' | 'PRODUCTION'>('PRODUCTION');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');

  async function submit() {
    if (!spec.trim()) {
      setErr('Describe what to build first.');
      return;
    }
    setBusy(true);
    setErr('');
    try {
      const res = await api.createRun({
        userRequest: spec.trim(),
        aiProvider: provider,
        executionMode: mode,
      });
      navigate(`/live/${res.runId}`);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Submit failed.');
      setBusy(false);
    }
  }

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <div className='ide-h1'>New spec</div>
      <div className='ide-sub'>
        Describe the change in plain language. The pipeline plans it, writes the code across your
        repos, builds + tests, and opens a PR with a verified score.
      </div>
      <textarea
        className='ide-textarea'
        rows={12}
        placeholder='e.g. Add a /health endpoint that returns build SHA + uptime, with a unit test…'
        value={spec}
        onChange={(e) => setSpec(e.target.value)}
      />
      <div style={{display: 'flex', gap: 12, margin: '14px 0', flexWrap: 'wrap'}}>
        <div style={{flex: 1, minWidth: 180}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Provider
          </div>
          <select
            className='ide-select'
            value={provider}
            onChange={(e) => setProvider(e.target.value)}
          >
            <option value='ANTHROPIC'>Anthropic</option>
            <option value='OPENAI'>OpenAI</option>
            <option value='GEMINI'>Gemini</option>
          </select>
        </div>
        <div style={{flex: 1, minWidth: 180}}>
          <div className='ide-h2' style={{marginTop: 0}}>
            Mode
          </div>
          <select
            className='ide-select'
            value={mode}
            onChange={(e) => setMode(e.target.value as 'PROTOTYPE' | 'PRODUCTION')}
          >
            <option value='PRODUCTION'>Production (plan gate)</option>
            <option value='PROTOTYPE'>Prototype (auto)</option>
          </select>
        </div>
      </div>
      {err && <div style={{color: 'var(--ide-red)', fontSize: 13, marginBottom: 10}}>{err}</div>}
      <button className='ide-btn' onClick={submit} disabled={busy}>
        {busy ? 'Submitting…' : '▷ Run spec'}
      </button>
    </div>
  );
}

// ───────────────────────── Live run ─────────────────────────────────────────
export function IdeLive() {
  const params = useParams();
  const navigate = useNavigate();
  const runId = params.runId || '';
  const [run, setRun] = useState<api.RunDetail | null>(null);

  useEffect(() => {
    if (!runId) return;
    let stop = false;
    const poll = () => {
      api
        .getRun(runId)
        .then((r) => {
          if (stop) return;
          setRun(r);
        })
        .catch(() => {});
    };
    poll();
    const t = setInterval(poll, 4000);
    return () => {
      stop = true;
      clearInterval(t);
    };
  }, [runId]);

  const terminal = useMemo(() => {
    const s = (run?.status || '').toUpperCase();
    return ['COMPLETE', 'COMPLETED', 'FAILED', 'ERROR', 'CANCELLED', 'PARTIAL'].includes(s);
  }, [run?.status]);

  return (
    <div className='ide-pane' style={{maxWidth: 820}}>
      <div className='ide-h1'>Live run</div>
      <div className='ide-sub ide-mono'>{runId}</div>
      {!run ? (
        <div className='ide-empty'>Connecting…</div>
      ) : (
        <div className='ide-card'>
          <div style={{display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12}}>
            <IdeBadge kind={statusKind(run.status)}>{run.status}</IdeBadge>
            {run.statusReason && (
              <span style={{color: 'var(--ide-fg-muted)'}}>{run.statusReason}</span>
            )}
          </div>
          {run.runSummary && <div style={{fontSize: 13, lineHeight: 1.6}}>{run.runSummary}</div>}
          <div className='ide-kv' style={{marginTop: 12}}>
            <span>Provider</span>
            <span>{(run.aiProvider || '—').toLowerCase()}</span>
          </div>
          <div className='ide-kv'>
            <span>Worker model</span>
            <span className='ide-mono' style={{fontSize: 11}}>
              {run.workerModel || '—'}
            </span>
          </div>
          {terminal && (
            <button
              className='ide-btn'
              style={{marginTop: 14}}
              onClick={() => navigate(`/result/${runId}`)}
            >
              View result →
            </button>
          )}
        </div>
      )}
    </div>
  );
}
