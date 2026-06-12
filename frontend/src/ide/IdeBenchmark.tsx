import {useCallback, useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
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

function RunRow({r, active, onClick}: {r: api.BenchmarkRun; active: boolean; onClick: () => void}) {
  return (
    <button
      onClick={onClick}
      className='ide-card ide-benchmark-row'
      style={{
        width: '100%',
        textAlign: 'left',
        cursor: 'pointer',
        marginBottom: 8,
        border: `1px solid ${active ? 'var(--ide-accent)' : 'var(--ide-border)'}`,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        font: 'inherit',
        color: 'var(--ide-fg)',
      }}
    >
      <div
        className='ide-benchmark-row-head'
        style={{display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap'}}
      >
        <SuiteChip suite={r.suite} />
        <span style={{fontWeight: 600}}>{r.model}</span>
        <span style={{color: MUTED, fontSize: 12}}>{r.provider}</span>
        <span
          className='ide-benchmark-row-status'
          style={{marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 8}}
        >
          {r.submissionStatus && <SubChip status={r.submissionStatus} url={r.leaderboardUrl} />}
          <IdeBadge kind={benchKind(r.status)}>{r.status}</IdeBadge>
        </span>
      </div>
      <div
        className='ide-benchmark-row-stats'
        style={{display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap'}}
      >
        <ResolvedBar resolved={r.resolvedTasks} total={r.totalTasks} />
        <span style={{fontSize: 12, whiteSpace: 'nowrap'}}>
          <strong>
            {r.resolvedTasks}/{r.totalTasks}
          </strong>{' '}
          <span style={{color: MUTED}}>resolved ({pct(r.resolvedTasks, r.totalTasks)}%)</span>
        </span>
        <span style={{fontSize: 12, color: MUTED}}>k={r.k}</span>
        <span style={{fontSize: 12}}>{fmtCost(r.costUsd)}</span>
        <span style={{fontSize: 12, color: MUTED}} title='input ↑ / output ↓ tokens'>
          {fmtTok(r.inputTokens)}↑ {fmtTok(r.outputTokens)}↓ tok
        </span>
        <span
          className='ide-benchmark-row-time'
          style={{marginLeft: 'auto', fontSize: 12, color: MUTED}}
        >
          {relTime(r.createdAt)}
        </span>
      </div>
    </button>
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
    groups.push({
      taskName,
      attempts: sorted,
      passed: Boolean(passing),
      done: sorted.length,
      cost: sorted.reduce((s, a) => s + Number(a.costUsd ?? 0), 0),
      inTok: sorted.reduce((s, a) => s + Number(a.inputTokens ?? 0), 0),
      outTok: sorted.reduce((s, a) => s + Number(a.outputTokens ?? 0), 0),
      runId: (passing ?? sorted[sorted.length - 1])?.esRunId ?? null,
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
        return (
          <span
            key={i}
            style={{color: pass ? 'var(--ide-green)' : 'var(--ide-red)'}}
            title={`attempt ${i + 1}: ${pass ? 'pass' : 'fail'}`}
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

function Detail({d, onClose}: {d: api.BenchmarkDetail; onClose: () => void}) {
  const r = d.run;
  const groups = groupTasks(d.tasks);
  const runDone = ['DONE', 'FAILED', 'CANCELLED'].includes((r.status || '').toUpperCase());
  const resolved = groups.filter((g) => g.passed).length;
  const failed = groups.filter((g) => !g.passed && (g.done >= r.k || runDone)).length;
  const pending = groups.length - resolved - failed;
  return (
    <div style={{marginTop: 20}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10}}>
        <div className='ide-h2' style={{margin: 0}}>
          {r.model} · {r.suite}
        </div>
        <button
          onClick={onClose}
          className='ide-btn ghost'
          style={{marginLeft: 'auto', fontSize: 12}}
          title='Close detail'
        >
          ✕ Close
        </button>
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
          <IdeBadge kind={benchKind(r.status)}>{r.status}</IdeBadge>
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
  const [runs, setRuns] = useState<api.BenchmarkRun[] | null>(null);
  const [sel, setSel] = useState<string | null>(null);
  const [detail, setDetail] = useState<api.BenchmarkDetail | null>(null);
  const [err, setErr] = useState('');

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

  // Detail: load on select + 3s poll while selected (skipped when hidden).
  useEffect(() => {
    if (!sel) {
      setDetail(null);
      return;
    }
    void loadDetail(sel);
    const t = setInterval(() => {
      if (!document.hidden) void loadDetail(sel);
    }, 3000);
    return () => clearInterval(t);
  }, [sel, loadDetail]);

  if (!isAdmin) {
    return (
      <div className='ide-pane' style={{maxWidth: 760}}>
        <div className='ide-h1'>Benchmarks</div>
        <EmptyCard>This is an operator-only surface (SUPER_ADMIN).</EmptyCard>
      </div>
    );
  }

  return (
    <div className='ide-pane' style={{maxWidth: 1080}}>
      <div style={{display: 'flex', alignItems: 'baseline', gap: 12}}>
        <div className='ide-h1'>Benchmarks</div>
        <button
          onClick={() => void loadList()}
          className='ide-btn ghost'
          style={{marginLeft: 'auto', fontSize: 12}}
          title='Refresh now'
        >
          ↻ Refresh
        </button>
      </div>
      <div className='ide-sub'>
        Live tracking of Terminal-Bench / SWE-bench work + leaderboard submission. An external
        verifier publishes events; this auto-refreshes every few seconds.
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
        runs.map((r) => (
          <RunRow
            key={r.id}
            r={r}
            active={r.id === sel}
            onClick={() => setSel(r.id === sel ? null : r.id)}
          />
        ))
      )}

      {sel && detail && <Detail d={detail} onClose={() => setSel(null)} />}
    </div>
  );
}
