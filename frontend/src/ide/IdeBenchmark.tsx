import {useCallback, useEffect, useMemo, useState} from 'react';
import {ArrowLeft, ChevronDown, ChevronRight, ExternalLink, RefreshCw} from 'lucide-react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';

// Operator `/benchmark` dashboard (RL-2026-06) — the visual surface for the
// common, suite-agnostic in-app benchmark TRACKING layer. ExecuteSpec ingests
// events an external verifier (Harbor for Terminal-Bench; SWE/ProgramBench next)
// publishes; this page renders the live grid + per-task scores + event log +
// submission status. Operator-only (SUPER_ADMIN). Browser EventSource can't send
// the bearer the SSE endpoint requires, so we POLL (the backend SSE stream still
// exists for non-browser consumers) — every few seconds, which is real-time
// enough for benchmark cadence (a task scores roughly once a minute).

// ── helpers ────────────────────────────────────────────────────────────────

/** DONE → ok · FAILED/CANCELLED → bad · else (PENDING/RUNNING) → warn. */
function benchKind(status?: string | null): 'ok' | 'warn' | 'bad' {
  const s = (status || '').toUpperCase();
  if (s === 'DONE') return 'ok';
  if (s === 'FAILED' || s === 'CANCELLED') return 'bad';
  return 'warn';
}

function effectiveStatus(r: api.BenchmarkRun): string {
  const s = (r.status || '').toUpperCase();
  if ((s === 'RUNNING' || s === 'PENDING') && r.finishedAt) return 'DONE';
  return r.status || 'UNKNOWN';
}

function fmtCost(c: number | string | null | undefined): string {
  const n = Number(c ?? 0);
  return Number.isFinite(n) ? `$${n.toFixed(4)}` : '$0.0000';
}

function fmtTok(t: number | null | undefined): string {
  const n = Number(t ?? 0);
  if (!Number.isFinite(n) || n <= 0) return '0';
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`;
  if (n >= 1e3) return `${Math.round(n / 1e3)}K`;
  return String(n);
}

function pct(resolved: number, total: number): number {
  return total > 0 ? Math.round((100 * resolved) / total) : 0;
}

function dateTime(iso?: string | null): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(t);
}

function relTime(iso?: string | null): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '—';
  const s = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (s < 60) return `${s}s ago`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return `${h}h ago`;
  return `${Math.floor(h / 24)}d ago`;
}

function evColor(type: string): string {
  const t = type.toUpperCase();
  if (t === 'RUN_DONE' || t === 'SUBMITTED') return 'var(--ide-green)';
  if (t === 'RUN_FAILED' || t === 'SUBMISSION_FAILED') return 'var(--ide-red)';
  if (t === 'TASK_SCORED') return 'var(--ide-blue)';
  return 'var(--ide-fg-muted)';
}

const MUTED = 'var(--ide-fg-muted)';

// ── small components ────────────────────────────────────────────────────────

function ErrCard({msg}: {msg: string}) {
  return (
    <div className='ide-card' style={{marginBottom: 12, color: 'var(--ide-red)'}}>
      {msg}
    </div>
  );
}

function EmptyCard({children}: {children: React.ReactNode}) {
  return (
    <div className='ide-card' style={{color: MUTED, fontSize: 13}}>
      {children}
    </div>
  );
}

function SuiteChip({suite}: {suite: string}) {
  return (
    <span
      style={{
        fontSize: 11,
        fontWeight: 600,
        letterSpacing: 0.3,
        padding: '2px 7px',
        borderRadius: 4,
        background: 'var(--ide-bg-input)',
        border: '1px solid var(--ide-border)',
        color: 'var(--ide-fg-bright)',
        whiteSpace: 'nowrap',
      }}
    >
      {suite}
    </span>
  );
}

function SubChip({status, url}: {status: string; url?: string | null}) {
  const s = status.toUpperCase();
  const color =
    s === 'SUBMITTED'
      ? 'var(--ide-green)'
      : s === 'FAILED'
        ? 'var(--ide-red)'
        : 'var(--ide-yellow)';
  const label = `submission: ${status.toLowerCase()}`;
  const style: React.CSSProperties = {
    fontSize: 11,
    padding: '2px 7px',
    borderRadius: 4,
    border: `1px solid ${color}`,
    color,
    whiteSpace: 'nowrap',
    textDecoration: 'none',
  };
  return url ? (
    <a href={url} target='_blank' rel='noreferrer' style={style}>
      {label} ↗
    </a>
  ) : (
    <span style={style}>{label}</span>
  );
}

function ResolvedBar({resolved, total}: {resolved: number; total: number}) {
  const p = pct(resolved, total);
  return (
    <div
      style={{
        flex: '1 1 120px',
        minWidth: 90,
        height: 8,
        background: 'var(--ide-bg-input)',
        border: '1px solid var(--ide-border)',
        borderRadius: 4,
        overflow: 'hidden',
      }}
    >
      <div style={{width: `${p}%`, height: '100%', background: 'var(--ide-green)'}} />
    </div>
  );
}

function KV({k, children}: {k: string; children: React.ReactNode}) {
  return (
    <div style={{display: 'flex', flexDirection: 'column', gap: 3}}>
      <span style={{fontSize: 11, color: MUTED, textTransform: 'uppercase', letterSpacing: 0.4}}>
        {k}
      </span>
      <span style={{fontSize: 13}}>{children}</span>
    </div>
  );
}

// ── run row ─────────────────────────────────────────────────────────────────

function shortDataset(dataset?: string | null): string {
  if (!dataset) return '—';
  return dataset.replace(/^swe-bench-pro-/, 'pro-').replace(/^terminal-bench-/, 'tb-');
}

function runSort(a: api.BenchmarkRun, b: api.BenchmarkRun): number {
  return Date.parse(b.createdAt || '') - Date.parse(a.createdAt || '');
}

interface RunGroups {
  current: api.BenchmarkRun[];
  historical: api.BenchmarkRun[];
}

function groupRuns(runs: api.BenchmarkRun[]): RunGroups {
  const latestDatasetBySuite = new Map<string, {key: string; t: number}>();
  for (const r of runs) {
    const suite = r.suite || 'UNKNOWN';
    const key = `${suite}::${r.dataset || ''}`;
    const t = Date.parse(r.createdAt || '') || 0;
    const prev = latestDatasetBySuite.get(suite);
    if (!prev || t > prev.t) latestDatasetBySuite.set(suite, {key, t});
  }
  const current: api.BenchmarkRun[] = [];
  const historical: api.BenchmarkRun[] = [];
  for (const r of runs) {
    const status = effectiveStatus(r).toUpperCase();
    const key = `${r.suite || 'UNKNOWN'}::${r.dataset || ''}`;
    const newestDataset = latestDatasetBySuite.get(r.suite || 'UNKNOWN')?.key === key;
    if (status === 'RUNNING' || status === 'PENDING' || newestDataset) current.push(r);
    else historical.push(r);
  }
  return {current: current.sort(runSort), historical: historical.sort(runSort)};
}

function RunRow({r, onOpen}: {r: api.BenchmarkRun; onOpen: () => void}) {
  const status = effectiveStatus(r);
  const scored = r.totalTasks > 0 ? `${r.resolvedTasks}/${r.totalTasks}` : String(r.resolvedTasks);
  return (
    <tr
      className='ide-benchmark-table-row'
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          onOpen();
        }
      }}
      tabIndex={0}
    >
      <td style={TD}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 0}}>
          <SuiteChip suite={r.suite} />
          <span className='ide-benchmark-model'>{r.model}</span>
        </div>
        <div style={{marginTop: 4, color: MUTED, fontSize: 12}}>{r.provider}</div>
      </td>
      <td style={TD}>{shortDataset(r.dataset)}</td>
      <td style={TD}>
        <IdeBadge kind={benchKind(status)}>{status}</IdeBadge>
        {r.submissionStatus && (
          <div style={{marginTop: 6}}>
            <SubChip status={r.submissionStatus} url={r.leaderboardUrl} />
          </div>
        )}
      </td>
      <td style={TD}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 160}}>
          <ResolvedBar resolved={r.resolvedTasks} total={r.totalTasks} />
          <span style={{whiteSpace: 'nowrap'}}>
            <strong>{scored}</strong> ({pct(r.resolvedTasks, r.totalTasks)}%)
          </span>
        </div>
      </td>
      <td style={TD}>k={r.k}</td>
      <td style={TD}>{fmtCost(r.costUsd)}</td>
      <td style={{...TD, color: MUTED, whiteSpace: 'nowrap'}}>
        {fmtTok(r.inputTokens)}↑ {fmtTok(r.outputTokens)}↓
      </td>
      <td style={{...TD, color: MUTED, whiteSpace: 'nowrap'}}>
        {dateTime(r.createdAt)}
        <div style={{fontSize: 11}}>
          {r.finishedAt ? `done ${dateTime(r.finishedAt)}` : relTime(r.createdAt)}
        </div>
      </td>
      <td style={{...TD, width: 36}}>
        <ExternalLink size={15} aria-hidden='true' />
      </td>
    </tr>
  );
}

function RunTable({runs, empty}: {runs: api.BenchmarkRun[]; empty: string}) {
  const navigate = useNavigate();
  if (!runs.length) return <EmptyCard>{empty}</EmptyCard>;
  return (
    <div className='ide-card ide-benchmark-table-card'>
      <table className='ide-benchmark-table'>
        <thead>
          <tr>
            {['Benchmark', 'Dataset', 'Status', 'Resolved', 'K', 'Cost', 'Tokens', 'Time', ''].map(
              (h) => (
                <th key={h} style={TH}>
                  {h}
                </th>
              ),
            )}
          </tr>
        </thead>
        <tbody>
          {runs.map((r) => (
            <RunRow key={r.id} r={r} onOpen={() => navigate(`/benchmark/${r.id}`)} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function BenchmarkSection({
  title,
  subtitle,
  runs,
  collapsed,
  onToggle,
  empty,
}: {
  title: string;
  subtitle: string;
  runs: api.BenchmarkRun[];
  collapsed: boolean;
  onToggle: () => void;
  empty: string;
}) {
  return (
    <section className='ide-benchmark-section'>
      <div className='ide-benchmark-section-head'>
        <button className='ide-btn ghost ide-benchmark-collapse' onClick={onToggle}>
          {collapsed ? <ChevronRight size={16} /> : <ChevronDown size={16} />}
          <span>{title}</span>
          <span className='ide-benchmark-count'>{runs.length}</span>
        </button>
        <span className='ide-benchmark-section-sub'>{subtitle}</span>
      </div>
      {!collapsed && <RunTable runs={runs} empty={empty} />}
    </section>
  );
}

// ── detail ──────────────────────────────────────────────────────────────────

const TH: React.CSSProperties = {
  textAlign: 'left',
  padding: '8px 12px',
  fontSize: 11,
  textTransform: 'uppercase',
  letterSpacing: 0.4,
  color: MUTED,
  whiteSpace: 'nowrap',
};
const TD: React.CSSProperties = {padding: '8px 12px', fontSize: 13, verticalAlign: 'top'};

/** One task with its up-to-k attempts folded together. Harbor runs k attempts per
 *  task (and interleaves them across tasks), so the raw event rows list each task
 *  up to k times — we group them so the table shows ONE row per task. */
interface TaskGroup {
  taskName: string;
  attempts: api.BenchmarkTaskResult[]; // ascending by attempt
  passed: boolean; // pass@k — any attempt with reward>0
  done: number; // attempts scored so far
  cost: number;
  inTok: number;
  outTok: number;
  runId: string | null; // the passing attempt's run, else the latest
  outcome: string | null;
  reason: string | null;
}

function groupTasks(tasks: api.BenchmarkTaskResult[]): TaskGroup[] {
  const byName = new Map<string, api.BenchmarkTaskResult[]>();
  for (const t of tasks) {
    const arr = byName.get(t.taskName);
    if (arr) arr.push(t);
    else byName.set(t.taskName, [t]);
  }
  const groups: TaskGroup[] = [];
  byName.forEach((atts, taskName) => {
    const sorted = [...atts].sort((a, b) => a.attempt - b.attempt);
    const passing = sorted.find((a) => a.reward > 0);
    const displayAttempt = passing ?? sorted[sorted.length - 1];
    groups.push({
      taskName,
      attempts: sorted,
      passed: Boolean(passing),
      done: sorted.length,
      cost: sorted.reduce((s, a) => s + Number(a.costUsd ?? 0), 0),
      inTok: sorted.reduce((s, a) => s + Number(a.inputTokens ?? 0), 0),
      outTok: sorted.reduce((s, a) => s + Number(a.outputTokens ?? 0), 0),
      runId: displayAttempt?.esRunId ?? null,
      outcome: displayAttempt?.outcome ?? null,
      reason: displayAttempt?.reason ?? null,
    });
  });
  return groups.sort((a, b) => a.taskName.localeCompare(b.taskName));
}

/** Per-attempt pass/fail pips. The stored attempt NUMBER is harbor discovery order,
 *  not a meaningful k-index, so we render positionally: ✓ pass · ✗ fail · · not-yet-run. */
function AttemptPips({attempts, k}: {attempts: api.BenchmarkTaskResult[]; k: number}) {
  const slots = Math.max(k, attempts.length);
  return (
    <span style={{display: 'inline-flex', gap: 5, fontFamily: 'monospace'}}>
      {Array.from({length: slots}).map((_, i) => {
        const a = attempts[i];
        if (!a)
          return (
            <span key={i} style={{color: MUTED, opacity: 0.4}} title='not yet run'>
              ·
            </span>
          );
        const pass = a.reward > 0;
        const extra = [a.outcome, a.reason].filter(Boolean).join(' · ');
        return (
          <span
            key={i}
            style={{color: pass ? 'var(--ide-green)' : 'var(--ide-red)'}}
            title={`attempt ${i + 1}: ${pass ? 'pass' : 'fail'}${extra ? ` · ${extra}` : ''}`}
          >
            {pass ? '✓' : '✗'}
          </span>
        );
      })}
    </span>
  );
}

function TaskTable({groups, k, runDone}: {groups: TaskGroup[]; k: number; runDone: boolean}) {
  if (!groups.length) return <EmptyCard>No task scores yet.</EmptyCard>;
  return (
    <div className='ide-card' style={{padding: 0, overflowX: 'auto'}}>
      <table style={{width: '100%', borderCollapse: 'collapse'}}>
        <thead>
          <tr>
            {['Task', 'Result', `Attempts (k=${k})`, 'Cost', 'Tokens', 'Work'].map((h) => (
              <th key={h} style={TH}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => (
            <tr key={g.taskName} style={{borderTop: '1px solid var(--ide-border)'}}>
              <td style={{...TD, fontFamily: 'monospace'}}>{g.taskName}</td>
              <td style={TD}>
                {g.passed ? (
                  <span style={{color: 'var(--ide-green)'}}>✓ resolved</span>
                ) : g.done >= k || runDone ? (
                  <span style={{color: 'var(--ide-red)'}}>✗ failed</span>
                ) : (
                  <span style={{color: MUTED}}>
                    … {g.done}/{k}
                  </span>
                )}
                {!g.passed && (g.outcome || g.reason) && (
                  <div style={{marginTop: 3, color: MUTED, fontSize: 11}}>
                    {[g.outcome, g.reason].filter(Boolean).join(' · ')}
                  </div>
                )}
              </td>
              <td style={TD}>
                <AttemptPips attempts={g.attempts} k={k} />
              </td>
              <td style={TD}>{fmtCost(g.cost)}</td>
              <td style={{...TD, color: MUTED, whiteSpace: 'nowrap'}}>
                {fmtTok(g.inTok)}↑ {fmtTok(g.outTok)}↓
              </td>
              <td style={TD}>
                {g.runId ? (
                  <Link to={`/result/${g.runId}`} style={{color: 'var(--ide-blue)'}}>
                    {g.runId.slice(0, 8)}
                  </Link>
                ) : (
                  <span style={{color: MUTED}}>—</span>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EventLog({events}: {events: api.BenchmarkEvent[]}) {
  if (!events.length) return <EmptyCard>No events yet.</EmptyCard>;
  const rev = [...events].sort((a, b) => b.seq - a.seq);
  return (
    <div className='ide-card' style={{padding: 0, maxHeight: 340, overflowY: 'auto'}}>
      {rev.map((e, i) => (
        <div
          key={e.id}
          style={{
            display: 'flex',
            gap: 10,
            padding: '8px 12px',
            borderTop: i ? '1px solid var(--ide-border)' : 'none',
            fontSize: 12,
          }}
        >
          <span style={{fontFamily: 'monospace', color: evColor(e.type), minWidth: 140}}>
            {e.type}
          </span>
          <span style={{flex: 1, wordBreak: 'break-word'}}>{e.message || ''}</span>
          <span style={{color: MUTED, whiteSpace: 'nowrap'}}>{relTime(e.createdAt)}</span>
        </div>
      ))}
    </div>
  );
}

function Detail({d}: {d: api.BenchmarkDetail}) {
  const r = d.run;
  const groups = groupTasks(d.tasks);
  const status = effectiveStatus(r);
  const runDone = ['DONE', 'FAILED', 'CANCELLED'].includes(status.toUpperCase());
  const resolved = groups.filter((g) => g.passed).length;
  const failed = groups.filter((g) => !g.passed && (g.done >= r.k || runDone)).length;
  const pending = groups.length - resolved - failed;
  return (
    <div>
      <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10}}>
        <Link to='/benchmark' className='ide-btn ghost' style={{fontSize: 12}}>
          <ArrowLeft size={15} />
          Back
        </Link>
        <div className='ide-h2' style={{margin: 0}}>
          {r.model} · {r.suite}
        </div>
      </div>

      <div
        className='ide-card'
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))',
          gap: 14,
        }}
      >
        <KV k='Status'>
          <IdeBadge kind={benchKind(status)}>{status}</IdeBadge>
        </KV>
        <KV k='Resolved'>
          {r.resolvedTasks}/{r.totalTasks} ({pct(r.resolvedTasks, r.totalTasks)}%)
        </KV>
        <KV k='Cost'>{fmtCost(r.costUsd)}</KV>
        <KV k='Attempts (k)'>{r.k}</KV>
        <KV k='Provider'>{r.provider}</KV>
        <KV k='Dataset'>{r.dataset || '—'}</KV>
        <KV k='Submission'>{r.submissionStatus || '—'}</KV>
        <KV k='Started'>{relTime(r.startedAt)}</KV>
      </div>

      {r.leaderboardUrl && (
        <a
          className='ide-btn'
          href={r.leaderboardUrl}
          target='_blank'
          rel='noreferrer'
          style={{display: 'inline-block', marginTop: 12}}
        >
          View on leaderboard ↗
        </a>
      )}
      {r.error && (
        <div style={{marginTop: 12}}>
          <ErrCard msg={r.error} />
        </div>
      )}

      <div className='ide-h2' style={{marginTop: 18}}>
        Tasks ({groups.length})
        <span style={{fontSize: 12, fontWeight: 400, color: MUTED, marginLeft: 8}}>
          {d.tasks.length} attempts ·{' '}
          <span style={{color: 'var(--ide-green)'}}>{resolved} resolved</span>
          {failed > 0 && (
            <>
              {' · '}
              <span style={{color: 'var(--ide-red)'}}>{failed} failed</span>
            </>
          )}
          {pending > 0 && <> · {pending} in progress</>}
        </span>
      </div>
      <TaskTable groups={groups} k={r.k} runDone={runDone} />

      <div className='ide-h2' style={{marginTop: 18}}>
        Event log ({d.events.length})
      </div>
      <EventLog events={d.events} />
    </div>
  );
}

// ── page ────────────────────────────────────────────────────────────────────

export default function IdeBenchmark() {
  const isAdmin = useAuthStore(selectIsSuperAdmin);
  const {benchmarkId} = useParams();
  const [runs, setRuns] = useState<api.BenchmarkRun[] | null>(null);
  const [detail, setDetail] = useState<api.BenchmarkDetail | null>(null);
  const [err, setErr] = useState('');
  const [currentCollapsed, setCurrentCollapsed] = useState(false);
  const [historicalCollapsed, setHistoricalCollapsed] = useState(false);
  const groups = useMemo(() => groupRuns(runs ?? []), [runs]);

  const loadList = useCallback(async () => {
    try {
      setRuns(await api.listBenchmarks());
      setErr('');
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load benchmark runs.');
      setRuns([]);
    }
  }, []);

  const loadDetail = useCallback(async (id: string) => {
    try {
      setDetail(await api.getBenchmark(id));
    } catch {
      /* keep the last good detail on a transient poll error */
    }
  }, []);

  // List: initial load + 5s poll (skipped when the tab is hidden).
  useEffect(() => {
    if (!isAdmin) return;
    void loadList();
    const t = setInterval(() => {
      if (!document.hidden) void loadList();
    }, 5000);
    return () => clearInterval(t);
  }, [isAdmin, loadList]);

  // Detail route: load on open + 3s poll while selected (skipped when hidden).
  useEffect(() => {
    if (!benchmarkId) {
      setDetail(null);
      return;
    }
    void loadDetail(benchmarkId);
    const t = setInterval(() => {
      if (!document.hidden) void loadDetail(benchmarkId);
    }, 3000);
    return () => clearInterval(t);
  }, [benchmarkId, loadDetail]);

  if (!isAdmin) {
    return (
      <div className='ide-pane' style={{maxWidth: 760}}>
        <h1 className='ide-h1'>{benchmarkId ? 'Benchmark Detail' : 'Benchmarks'}</h1>
        <EmptyCard>This is an operator-only surface (SUPER_ADMIN).</EmptyCard>
      </div>
    );
  }

  if (benchmarkId) {
    return (
      <div className='ide-pane' style={{maxWidth: 1180}}>
        {err && <ErrCard msg={err} />}
        {!detail ? <IdeLoader label='Loading benchmark detail…' pad={24} /> : <Detail d={detail} />}
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 1180}}>
      <div style={{display: 'flex', alignItems: 'baseline', gap: 12}}>
        <h1 className='ide-h1'>Benchmarks</h1>
        <button
          onClick={() => void loadList()}
          className='ide-btn ghost'
          style={{marginLeft: 'auto', fontSize: 12}}
          title='Refresh now'
        >
          <RefreshCw size={15} />
          Refresh
        </button>
      </div>
      <div className='ide-sub'>
        Operator telemetry for Terminal-Bench and SWE-bench. This is not a customer run launcher;
        external harnesses publish verifier events and this page tracks them.
      </div>

      {err && <ErrCard msg={err} />}

      {runs === null ? (
        <IdeLoader label='Loading benchmark work…' pad={24} />
      ) : runs.length === 0 ? (
        <EmptyCard>
          No benchmark work tracked yet. Items appear here as the harness publishes events to{' '}
          <code>/api/v2/test/benchmark</code>.
        </EmptyCard>
      ) : (
        <>
          <BenchmarkSection
            title='Current'
            subtitle='Latest dataset per suite plus any active benchmark runs.'
            runs={groups.current}
            collapsed={currentCollapsed}
            onToggle={() => setCurrentCollapsed((v) => !v)}
            empty='No current benchmark rows.'
          />
          <BenchmarkSection
            title='Historical'
            subtitle='Older completed benchmark lanes kept for comparison.'
            runs={groups.historical}
            collapsed={historicalCollapsed}
            onToggle={() => setHistoricalCollapsed((v) => !v)}
            empty='No historical benchmark rows.'
          />
        </>
      )}
    </div>
  );
}
