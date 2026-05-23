import {useEffect, useRef, useState} from 'react';
import {useNavigate, useParams} from 'react-router-dom';
import {PrCommentInbox} from '@/signal/components/PrCommentInbox';
import {Markdown} from '@/signal/components/Markdown';
import {ConfirmModal} from '@/signal/components/Modal';
import * as api from '@/lib/api';
import {
  IdeBadge,
  IdeCodeViewer,
  IdeFileTree,
  IdeLoader,
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

// Split file text into lines for diffing (drops a single trailing newline so an
// empty last "line" doesn't render as a phantom row).
function toLines(s: string): string[] {
  return (s || '').replace(/\n$/, '').split('\n');
}

/**
 * LCS line diff: `base` (old) → `head` (new) into a flat DiffLine[] where
 * unchanged lines are ' ', deletions '-', and additions '+'. Deletions in a
 * hunk are emitted before the additions that replace them (standard diff
 * grouping). Used for MODIFIED files where the backend gives us both sides.
 */
function lineDiff(base: string, head: string): DiffLine[] {
  const a = toLines(base);
  const b = toLines(head);
  const n = a.length;
  const m = b.length;
  // LCS length table (n+1 × m+1).
  const lcs: number[][] = Array.from({length: n + 1}, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({sign: ' ', text: a[i]});
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({sign: '-', text: a[i]});
      i++;
    } else {
      out.push({sign: '+', text: b[j]});
      j++;
    }
  }
  while (i < n) out.push({sign: '-', text: a[i++]});
  while (j < m) out.push({sign: '+', text: b[j++]});
  return out;
}
function fmtDur(ms?: number | null) {
  if (!ms) return '—';
  const s = Math.round(ms / 1000);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${s % 60}s`;
}

const TABS = [
  'Overview',
  'Code',
  'Tasks',
  'Timeline',
  'Validation',
  'Cost',
  'PR',
  'Tests',
  'Chat',
] as const;
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
  const [testPlan, setTestPlan] = useState<api.TestPlanData | null>(null);
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
  const [loading, setLoading] = useState(true);
  // Single confirm-modal slot — multiple destructive actions on this page
  // (delete run / deploy / stop preview / mark-test-invalid) drive it via a
  // discriminated payload that carries the action to run on confirm.
  const [confirm, setConfirm] = useState<{
    title: string;
    body: string;
    confirmLabel: string;
    danger?: boolean;
    onConfirm: () => void;
  } | null>(null);
  // Timeline + Chat — IDE parity with Signal ResultPage.
  const [timeline, setTimeline] = useState<api.TimelineData | null>(null);
  const [chat, setChat] = useState<api.RunChatMessage[] | null>(null);
  const [chatInput, setChatInput] = useState('');
  const [chatBusy, setChatBusy] = useState(false);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  // Preview lifecycle — status / logs / iframe embed once a preview is live.
  const [preview, setPreview] = useState<api.LocalPreviewStatus | null>(null);
  const [previewLogs, setPreviewLogs] = useState<string[] | null>(null);
  const [previewStarted, setPreviewStarted] = useState(false);

  useEffect(() => {
    if (!runId) return;
    api
      .getRun(runId)
      .then(setRun)
      .catch(() => {})
      .finally(() => setLoading(false));
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
    if (!runId) return;
    if (tab === 'Tests' && !tests)
      api
        .getTestReport(runId)
        .then(setTests)
        .catch(() => setTests(null));
    if (tab === 'Tests' && !testPlan)
      api
        .getTestPlan(runId)
        .then(setTestPlan)
        .catch(() => setTestPlan(null));
    if (tab === 'Timeline' && !timeline)
      api
        .getTimeline(runId)
        .then(setTimeline)
        .catch(() => setTimeline({tasks: []}));
    if (tab === 'Chat' && !chat)
      api
        .getRunChat(runId)
        .then(setChat)
        .catch(() => setChat([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  // Keep the chat thread pinned to the newest message.
  useEffect(() => {
    if (tab !== 'Chat') return;
    const box = chatEndRef.current?.parentElement;
    if (box) box.scrollTop = box.scrollHeight;
  }, [tab, chat, chatBusy]);

  // Preview status poll — once a preview has been started, poll status + logs
  // while the lifecycle is non-terminal (IDLE → BUILDING → … → RUNNING/FAILED).
  useEffect(() => {
    if (!runId || !previewStarted) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const TERMINAL = ['RUNNING', 'FAILED', 'STOPPED', 'TIMED_OUT', 'IDLE'];
    const tick = async () => {
      try {
        const s = await api.getPreviewStatus(runId);
        if (cancelled) return;
        setPreview(s);
        api
          .getPreviewLogs(runId, 200)
          .then((l) => !cancelled && setPreviewLogs(l.lines || []))
          .catch(() => {});
        if (!TERMINAL.includes(s.status)) timer = setTimeout(tick, 3000);
      } catch {
        if (!cancelled) timer = setTimeout(tick, 5000);
      }
    };
    void tick();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [runId, previewStarted]);

  async function openFile(path: string) {
    setActiveFile(path);
    setOpenFiles((o) => (o.includes(path) ? o : [...o, path]));
    if (cache[path]) return;
    try {
      const d = await api.getFileDiff(runId, path);
      // ADDED → every head line is an addition. MODIFIED → real LCS diff over
      // base (old) + head (new). UNCHANGED/other → no diff toggle.
      let diff: DiffLine[] | null = null;
      if (d.status === 'ADDED') {
        diff = toLines(d.head || '').map((t) => ({sign: '+' as const, text: t}));
      } else if (d.status === 'MODIFIED' && (d.base || d.head)) {
        diff = lineDiff(d.base || '', d.head || '');
      }
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

  // Run management — fork / archive / unarchive / soft-delete (dark endpoints).
  // 'delete' is confirmed by the caller via the ConfirmModal before reaching here.
  async function manageRun(act: 'fork' | 'archive' | 'unarchive' | 'delete') {
    if (!runId) return;
    setBusy(act);
    setMsg('');
    try {
      if (act === 'fork') {
        const f = await api.forkRun(runId);
        navigate(`/plan/${f.runId}`);
        return;
      }
      if (act === 'delete') {
        await api.softDeleteRun(runId);
        navigate('/runs');
        return;
      }
      await (act === 'archive' ? api.archiveRun(runId) : api.unarchiveRun(runId));
      setRun(await api.getRun(runId));
      setMsg(act === 'archive' ? 'Run archived.' : 'Run unarchived.');
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Action failed.');
    } finally {
      setBusy('');
    }
  }

  // Run-scoped chat: append the user message optimistically, then the reply.
  async function sendChat() {
    if (!runId || !chatInput.trim() || chatBusy) return;
    const text = chatInput.trim();
    setChatInput('');
    setChatBusy(true);
    setChat((prev) => [...(prev || []), {role: 'user', content: text}]);
    try {
      const resp = await api.sendRunChatMessage(runId, text);
      setChat((prev) => [...(prev || []), {role: 'assistant', content: resp.reply || ''}]);
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Chat failed.');
    } finally {
      setChatBusy(false);
    }
  }

  // Iterate as new run — forks the run's codebase + spec into a fresh run via the
  // backend /iterate endpoint, then routes to the new run's gate (PRODUCTION) or
  // live view (PROTOTYPE).
  async function iterateAsNewRun() {
    if (!runId || chatBusy) return;
    const instruction = chatInput.trim();
    if (!instruction) {
      setMsg('Type the change you want before starting a new iteration.');
      return;
    }
    setChatBusy(true);
    setMsg('');
    try {
      const {runId: newId} = await api.iterateRun(runId, instruction);
      if (newId) {
        setChatInput('');
        const prod = (run?.executionMode || '').toUpperCase() === 'PRODUCTION';
        navigate(prod ? `/plan/${newId}` : `/live/${newId}`);
      }
    } catch (e: unknown) {
      setMsg(e instanceof Error ? e.message : 'Iterate failed.');
    } finally {
      setChatBusy(false);
    }
  }

  const statusUpper = (run?.status || '').toUpperCase();
  const terminal = ['COMPLETE', 'COMPLETED', 'FAILED', 'CANCELLED', 'PARTIAL'].includes(
    statusUpper,
  );
  // Power-ops gating: pause is only meaningful while running; resume only when paused.
  const canPause = [
    'RUNNING',
    'EXECUTING',
    'IN_PROGRESS',
    'PLANNING',
    'AWAITING_APPROVAL',
  ].includes(statusUpper);
  const canResume = statusUpper === 'PAUSED';
  const score = report?.verifiedScore ?? null;
  const v = verdictFor(score, run?.status);
  const active = activeFile ? cache[activeFile] : null;
  const issues = report?.issues ?? [];

  if (loading) {
    return (
      <div style={{position: 'absolute', inset: 0}}>
        <IdeLoader label='Loading run…' />
      </div>
    );
  }

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
              {canPause && (
                <button
                  className='ide-btn ghost'
                  disabled={busy === 'pause'}
                  onClick={() =>
                    action(
                      'pause',
                      () => api.pauseRun(runId),
                      () => 'Run paused.',
                    )
                  }
                >
                  {busy === 'pause' ? 'Pausing…' : '⏸ Pause'}
                </button>
              )}
              {canResume && (
                <button
                  className='ide-btn'
                  disabled={busy === 'resume'}
                  onClick={() =>
                    action(
                      'resume',
                      () => api.resumeRun(runId),
                      () => 'Run resumed.',
                    )
                  }
                >
                  {busy === 'resume' ? 'Resuming…' : '▶ Resume'}
                </button>
              )}
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
                    async () => {
                      const s = await api.startPreview(runId);
                      setPreview(s);
                      setPreviewStarted(true);
                      return s;
                    },
                    (r) => `Preview: ${(r as {url?: string})?.url || 'starting'}`,
                  )
                }
              >
                ▶ Preview
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'deploy'}
                onClick={() =>
                  setConfirm({
                    title: 'Deploy to Cloud Run',
                    body: 'Deploy this run to a permanent Cloud Run service?',
                    confirmLabel: 'Deploy',
                    onConfirm: () =>
                      action(
                        'deploy',
                        () => api.createDeployment({runId}),
                        (r) =>
                          `Deployed: ${(r as {serviceUrl?: string})?.serviceUrl || 'provisioning'}`,
                      ),
                  })
                }
              >
                {busy === 'deploy' ? 'Deploying…' : '☁ Deploy'}
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
              <button
                className='ide-btn ghost'
                disabled={busy === 'fork'}
                onClick={() => manageRun('fork')}
              >
                ⑂ Fork
              </button>
              <button
                className='ide-btn ghost'
                disabled={busy === 'archive' || busy === 'unarchive'}
                onClick={() => manageRun(run?.archivedAt ? 'unarchive' : 'archive')}
              >
                {run?.archivedAt ? '⊡ Unarchive' : '⊟ Archive'}
              </button>
              {terminal && (
                <button
                  className='ide-btn ghost'
                  style={{color: 'var(--ide-red)'}}
                  disabled={busy === 'delete'}
                  onClick={() =>
                    setConfirm({
                      title: 'Delete run',
                      body: 'Delete this run? It is soft-deleted with a 30-day undo window.',
                      confirmLabel: 'Delete',
                      danger: true,
                      onConfirm: () => manageRun('delete'),
                    })
                  }
                >
                  ⊘ Delete
                </button>
              )}
            </div>

            {/* Preview lifecycle — status + iframe embed + logs + rebuild/stop. */}
            {(previewStarted || preview) &&
              (() => {
                const ps = (preview?.status || 'STARTING').toUpperCase();
                const previewUrl = preview?.url || '';
                const live = ps === 'RUNNING' && !!previewUrl;
                const failed = ps === 'FAILED' || ps === 'TIMED_OUT';
                const settled = live || failed || ps === 'STOPPED';
                return (
                  <>
                    <div
                      className='ide-h2'
                      style={{display: 'flex', alignItems: 'center', gap: 10}}
                    >
                      Preview
                      <IdeBadge kind={live ? 'ok' : failed ? 'bad' : 'warn'}>{ps}</IdeBadge>
                      {preview?.ttlRemainingSeconds ? (
                        <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                          TTL {Math.max(0, Math.round(preview.ttlRemainingSeconds))}s
                        </span>
                      ) : null}
                      <span style={{flex: 1}} />
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px'}}
                        disabled={busy === 'preview-rebuild'}
                        onClick={() =>
                          action(
                            'preview-rebuild',
                            async () => {
                              const s = await api.rebuildPreview(runId);
                              setPreview(s);
                              setPreviewStarted(true);
                              return s;
                            },
                            () => 'Rebuilding preview…',
                          )
                        }
                      >
                        {busy === 'preview-rebuild' ? 'Rebuilding…' : '↻ Rebuild'}
                      </button>
                      <button
                        className='ide-btn ghost'
                        style={{padding: '4px 10px', color: 'var(--ide-red)'}}
                        disabled={busy === 'preview-stop'}
                        onClick={() =>
                          setConfirm({
                            title: 'Stop preview',
                            body: 'Stop this preview?',
                            confirmLabel: 'Stop',
                            danger: true,
                            onConfirm: () =>
                              action(
                                'preview-stop',
                                async () => {
                                  await api.stopPreview(runId);
                                  setPreviewStarted(false);
                                  setPreview((p) => (p ? {...p, status: 'STOPPED', url: null} : p));
                                  return null;
                                },
                                () => 'Preview stopped.',
                              ),
                          })
                        }
                      >
                        {busy === 'preview-stop' ? 'Stopping…' : '■ Stop'}
                      </button>
                    </div>

                    {preview?.errorMessage && failed && (
                      <div
                        className='ide-card'
                        style={{color: 'var(--ide-red)', fontSize: 12, marginBottom: 10}}
                      >
                        {preview.errorMessage}
                      </div>
                    )}

                    {live ? (
                      <div className='ide-card' style={{padding: 0, overflow: 'hidden'}}>
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '6px 12px',
                            borderBottom: '1px solid var(--ide-border)',
                          }}
                        >
                          <span
                            className='ide-mono'
                            style={{
                              fontSize: 11,
                              color: 'var(--ide-fg-muted)',
                              flex: 1,
                              minWidth: 0,
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                            }}
                          >
                            {previewUrl}
                          </span>
                          <a
                            className='ide-btn ghost'
                            style={{padding: '3px 9px'}}
                            href={previewUrl || '#'}
                            target='_blank'
                            rel='noreferrer'
                          >
                            ↗ Open
                          </a>
                        </div>
                        <iframe
                          title='Run preview'
                          src={previewUrl}
                          sandbox='allow-scripts allow-same-origin allow-forms allow-popups'
                          style={{
                            width: '100%',
                            height: 420,
                            border: 'none',
                            background: '#fff',
                          }}
                        />
                      </div>
                    ) : (
                      !settled && (
                        <div
                          className='ide-card'
                          style={{fontSize: 12, color: 'var(--ide-fg-muted)'}}
                        >
                          Preview is {ps.toLowerCase()}… this can take up to a minute.
                        </div>
                      )
                    )}

                    {previewLogs && previewLogs.length > 0 && (
                      <>
                        <div className='ide-h2' style={{fontSize: 12}}>
                          Preview logs
                        </div>
                        <pre
                          className='ide-card ide-mono'
                          style={{
                            fontSize: 11,
                            lineHeight: 1.5,
                            maxHeight: 220,
                            overflow: 'auto',
                            whiteSpace: 'pre-wrap',
                            wordBreak: 'break-all',
                            margin: 0,
                          }}
                        >
                          {previewLogs.join('\n')}
                        </pre>
                      </>
                    )}
                  </>
                );
              })()}
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

        {/* ── Timeline ── */}
        {tab === 'Timeline' && (
          <div className='ide-pane' style={{position: 'absolute', inset: 0, overflowY: 'auto'}}>
            <div className='ide-h1'>Timeline</div>
            {timeline == null ? (
              <IdeLoader label='Loading timeline…' />
            ) : (timeline.tasks || []).length === 0 ? (
              <div className='ide-empty'>No timeline data yet. Tasks populate as the run runs.</div>
            ) : (
              <div className='ide-card'>
                {(() => {
                  const allTasks = timeline.tasks || [];
                  const starts = allTasks.map((t) => new Date(t.startedAt).getTime());
                  const ends = allTasks.map((t) =>
                    t.completedAt ? new Date(t.completedAt).getTime() : Date.now(),
                  );
                  const minT = Math.min(...starts);
                  const maxT = Math.max(...ends);
                  const span = Math.max(1, maxT - minT);
                  return allTasks.map((t, i) => {
                    const start = new Date(t.startedAt).getTime();
                    const end = t.completedAt ? new Date(t.completedAt).getTime() : Date.now();
                    const leftPct = ((start - minT) / span) * 100;
                    const widthPct = Math.max(2, ((end - start) / span) * 100);
                    const st = (t.status || '').toUpperCase();
                    const done = st === 'COMPLETED' || st === 'COMPLETE' || st === 'DONE';
                    const failed = st === 'FAILED';
                    return (
                      <div key={t.taskId || i} style={{marginBottom: 14}}>
                        <div
                          style={{
                            display: 'flex',
                            justifyContent: 'space-between',
                            gap: 8,
                            marginBottom: 5,
                          }}
                        >
                          <span
                            style={{
                              fontSize: 13,
                              color: 'var(--ide-fg-bright)',
                              flex: 1,
                              minWidth: 0,
                              whiteSpace: 'nowrap',
                              overflow: 'hidden',
                              textOverflow: 'ellipsis',
                            }}
                          >
                            {t.title || `Task ${i + 1}`}
                          </span>
                          <span
                            className='ide-mono'
                            style={{fontSize: 11, color: 'var(--ide-fg-muted)', flexShrink: 0}}
                          >
                            {fmtDur(end - start)}
                          </span>
                        </div>
                        <div
                          style={{
                            position: 'relative',
                            height: 8,
                            background: 'var(--ide-bg)',
                            borderRadius: 4,
                            overflow: 'hidden',
                          }}
                        >
                          <div
                            style={{
                              position: 'absolute',
                              left: `${leftPct}%`,
                              width: `${widthPct}%`,
                              top: 0,
                              bottom: 0,
                              borderRadius: 4,
                              background: failed
                                ? 'var(--ide-red)'
                                : done
                                  ? 'var(--ide-green)'
                                  : 'var(--ide-yellow)',
                            }}
                          />
                        </div>
                      </div>
                    );
                  });
                })()}
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
              <>
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
                <PrCommentInbox runId={runId} variant='ide' />
              </>
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

                {/* Test plan — generated test tasks + compile status. */}
                {(testPlan?.tasks?.length ?? 0) > 0 && (
                  <>
                    <div className='ide-h2'>Test plan · {testPlan?.tasks.length}</div>
                    <div className='ide-card' style={{padding: 0}}>
                      {(testPlan?.tasks ?? []).map((tk, i, arr) => (
                        <div
                          key={tk.id}
                          style={{
                            padding: '10px 16px',
                            borderBottom:
                              i < arr.length - 1 ? '1px solid var(--ide-border)' : 'none',
                          }}
                        >
                          <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                            <IdeBadge
                              kind={
                                tk.status === 'COMPILED'
                                  ? 'ok'
                                  : tk.status === 'FAILED'
                                    ? 'bad'
                                    : 'warn'
                              }
                            >
                              {tk.status}
                            </IdeBadge>
                            <span style={{flex: 1, fontSize: 13, fontWeight: 500}}>
                              {tk.taskName}
                            </span>
                            {tk.testCategory && (
                              <span style={{fontSize: 10, color: 'var(--ide-fg-muted)'}}>
                                {tk.testCategory}
                              </span>
                            )}
                          </div>
                          {tk.compilationError && (
                            <div
                              className='ide-mono'
                              style={{fontSize: 11, color: 'var(--ide-red)', marginTop: 4}}
                            >
                              {tk.compilationError}
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  </>
                )}

                {/* Root-cause groups — each renders its suggestedFix via Markdown +
                    an "Apply fix as new run" affordance (mirrors Signal ResultPage). */}
                {tests.rootCauseGroups?.length > 0 && (
                  <>
                    <div className='ide-h2'>Root causes · {tests.rootCauseGroups.length}</div>
                    {tests.rootCauseGroups.map((g, i) => {
                      const gAny = g as unknown as {
                        signature?: string;
                        description?: string;
                        count?: number;
                        failureCount?: number;
                        suggestion?: string;
                        suggestedFix?: string;
                      };
                      const fix = gAny.suggestedFix || gAny.suggestion;
                      const affected = gAny.failureCount ?? gAny.count ?? 0;
                      return (
                        <div className='ide-card' key={i} style={{marginBottom: 10}}>
                          <div style={{fontSize: 13, fontWeight: 600, marginBottom: 4}}>
                            {gAny.description || gAny.signature || `Root cause ${i + 1}`}
                          </div>
                          <div
                            style={{
                              fontSize: 11,
                              color: 'var(--ide-fg-muted)',
                              marginBottom: fix ? 10 : 0,
                            }}
                          >
                            {affected} affected test{affected === 1 ? '' : 's'}
                          </div>
                          {fix && (
                            <div
                              style={{
                                background: 'var(--ide-bg-input)',
                                padding: '10px 12px',
                                borderRadius: 6,
                                fontSize: 12,
                                lineHeight: 1.5,
                              }}
                            >
                              <Markdown>{fix}</Markdown>
                            </div>
                          )}
                          {gAny.signature && (
                            <button
                              className='ide-btn'
                              style={{marginTop: 10, padding: '4px 12px'}}
                              disabled={busy === `applyfix-${i}`}
                              onClick={() =>
                                action(
                                  `applyfix-${i}`,
                                  async () => {
                                    const r = (await api.applyTestFixes(
                                      runId,
                                      gAny.signature,
                                    )) as unknown as {runId?: string} | undefined;
                                    if (r?.runId) navigate(`/live/${r.runId}`);
                                    return r;
                                  },
                                  (r) => {
                                    const nid = (r as {runId?: string} | undefined)?.runId;
                                    return nid ? 'Fix run started.' : 'Test-fix run queued.';
                                  },
                                )
                              }
                            >
                              {busy === `applyfix-${i}` ? 'Applying…' : '⤳ Apply fix as new run'}
                            </button>
                          )}
                        </div>
                      );
                    })}
                  </>
                )}

                {/* Per-test result rows + mark-invalid on failed/error rows. */}
                {(tests.results?.length ?? 0) > 0 && (
                  <>
                    <div className='ide-h2'>All tests · {tests.results.length}</div>
                    <div className='ide-card' style={{padding: 0}}>
                      {tests.results.slice(0, 60).map((r, i) => {
                        const st = (r.status || 'UNKNOWN').toUpperCase();
                        const bad = st === 'FAILED' || st === 'ERROR';
                        return (
                          <div
                            key={r.testResultId ?? i}
                            style={{
                              padding: '10px 16px',
                              borderBottom:
                                i < Math.min(tests.results.length, 60) - 1
                                  ? '1px solid var(--ide-border)'
                                  : 'none',
                            }}
                          >
                            <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                              <IdeBadge kind={st === 'PASSED' ? 'ok' : bad ? 'bad' : 'warn'}>
                                {st}
                              </IdeBadge>
                              <span style={{flex: 1, fontSize: 13, minWidth: 0}}>
                                <span style={{fontWeight: 500}}>{r.testMethod}</span>{' '}
                                <span
                                  className='ide-mono'
                                  style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}
                                >
                                  {r.testClass}
                                </span>
                              </span>
                              <span style={{fontSize: 11, color: 'var(--ide-fg-muted)'}}>
                                {fmtDur(r.durationMs)}
                              </span>
                              {bad && r.testResultId != null && (
                                <button
                                  className='ide-btn ghost'
                                  style={{padding: '3px 8px'}}
                                  disabled={busy === `invalid-${r.testResultId}`}
                                  onClick={() =>
                                    setConfirm({
                                      title: 'Mark test invalid',
                                      body: 'Mark this test as invalid?',
                                      confirmLabel: 'Mark invalid',
                                      onConfirm: () =>
                                        action(
                                          `invalid-${r.testResultId}`,
                                          () => api.markTestInvalid(r.testResultId),
                                          () => 'Marked invalid.',
                                        ),
                                    })
                                  }
                                >
                                  Mark invalid
                                </button>
                              )}
                            </div>
                            {r.failureMessage && (
                              <div
                                className='ide-mono'
                                style={{
                                  fontSize: 11,
                                  color: 'var(--ide-red)',
                                  marginTop: 4,
                                  whiteSpace: 'pre-wrap',
                                  wordBreak: 'break-word',
                                }}
                              >
                                {r.failureMessage}
                              </div>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  </>
                )}
              </>
            )}
          </div>
        )}

        {/* ── Chat ── */}
        {tab === 'Chat' && (
          <div
            className='ide-pane'
            style={{position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column'}}
          >
            <div className='ide-h1'>Chat</div>
            <div style={{fontSize: 12, color: 'var(--ide-fg-muted)', marginBottom: 12}}>
              Run-scoped chat — ask the agent about its decisions or describe a change and start a
              fresh iteration from this run&apos;s codebase.
            </div>
            {msg && (
              <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-blue)'}}>
                {msg}
              </div>
            )}
            <div
              style={{
                flex: 1,
                minHeight: 0,
                overflowY: 'auto',
                display: 'flex',
                flexDirection: 'column',
                gap: 10,
                marginBottom: 12,
              }}
            >
              {chat == null ? (
                <IdeLoader label='Loading chat…' />
              ) : chat.length === 0 && !chatBusy ? (
                <div className='ide-empty'>
                  No messages yet. Ask the agent anything about this run.
                </div>
              ) : (
                chat.map((m, i) => {
                  const isUser = m.role === 'user';
                  return (
                    <div
                      key={i}
                      className='ide-card'
                      style={{
                        alignSelf: isUser ? 'flex-end' : 'flex-start',
                        maxWidth: '85%',
                        background: isUser ? 'var(--ide-blue)' : undefined,
                        color: isUser ? '#fff' : undefined,
                        padding: '10px 14px',
                      }}
                    >
                      <div
                        className='ide-mono'
                        style={{fontSize: 10, opacity: 0.7, marginBottom: 4}}
                      >
                        {isUser ? 'YOU' : 'RUN COPILOT'}
                      </div>
                      {/* Both roles render through Markdown (all-chat-markdown rule). */}
                      <Markdown>{m.content || ''}</Markdown>
                    </div>
                  );
                })
              )}
              {chatBusy && (
                <div style={{alignSelf: 'flex-start', fontSize: 12, color: 'var(--ide-fg-muted)'}}>
                  Thinking…
                </div>
              )}
              <div ref={chatEndRef} />
            </div>
            <div style={{display: 'flex', gap: 8, alignItems: 'flex-end'}}>
              <textarea
                className='ide-input'
                value={chatInput}
                onChange={(e) => setChatInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    void sendChat();
                  }
                }}
                placeholder='Ask anything about this run…'
                rows={2}
                disabled={chatBusy}
                style={{flex: 1, resize: 'vertical', minHeight: 56, maxHeight: 200}}
              />
              <div style={{display: 'flex', flexDirection: 'column', gap: 6, flexShrink: 0}}>
                <button
                  className='ide-btn'
                  onClick={sendChat}
                  disabled={chatBusy || !chatInput.trim()}
                >
                  Send
                </button>
                <button
                  className='ide-btn ghost'
                  onClick={iterateAsNewRun}
                  disabled={chatBusy || !chatInput.trim()}
                  title='Start a new run from this run’s code with your change applied'
                >
                  ⑂ Iterate as new run
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      <ConfirmModal
        open={!!confirm}
        title={confirm?.title ?? ''}
        body={confirm?.body ?? ''}
        confirmLabel={confirm?.confirmLabel ?? 'Confirm'}
        danger={confirm?.danger}
        onConfirm={() => confirm?.onConfirm()}
        onClose={() => setConfirm(null)}
      />
    </div>
  );
}
