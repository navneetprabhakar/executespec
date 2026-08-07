import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  ArrowLeft,
  Check,
  ChevronDown,
  ChevronRight,
  ExternalLink,
  RefreshCw,
  X,
} from 'lucide-react';
import {Link, useNavigate, useParams} from 'react-router-dom';
import * as api from '@/lib/api';
import {useAuthStore, selectIsSuperAdmin} from '@/stores/useAuthStore';
import {IdeBadge, IdeLoader} from '@/ide/IdeKit';
import './surfaces/systemdiscovery.css';

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

function duration(seconds?: number | null): string {
  const s = Math.max(0, Math.round(Number(seconds ?? 0)));
  if (!Number.isFinite(s)) return '—';
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${s % 60}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
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
  // Rule 2 — failure is a leading edge on a tinted note, not a red card body.
  return (
    <div className='ide-note bad' style={{marginBottom: 12}}>
      {msg}
    </div>
  );
}

function EmptyCard({children}: {children: React.ReactNode}) {
  return <div className='ide-card sysd-empty-card'>{children}</div>;
}

function SuiteChip({suite}: {suite: string}) {
  return <span className='sysd-chip'>{suite}</span>;
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
  return url ? (
    <a href={url} target='_blank' rel='noreferrer' className='sysd-sub-chip' style={{color}}>
      {label}{' '}
      <ExternalLink
        size={12}
        strokeWidth={1.75}
        aria-hidden='true'
        style={{display: 'inline-block', verticalAlign: '-0.1em', marginLeft: 4}}
      />
    </a>
  ) : (
    <span className='sysd-sub-chip' style={{color}}>
      {label}
    </span>
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
        borderRadius: 'var(--ide-r-control)',
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
      <span className='sysd-kv-label'>{k}</span>
      <span className='sysd-kv-value'>{children}</span>
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
      <td className={`${TD} ide-benchmark-name-cell`}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 0}}>
          <SuiteChip suite={r.suite} />
          <span className='ide-benchmark-model' title={r.model}>
            {r.model}
          </span>
        </div>
        <div className='sysd-sub'>{r.provider}</div>
      </td>
      <td className={TD}>{shortDataset(r.dataset)}</td>
      <td className={TD}>
        <IdeBadge kind={benchKind(status)}>{status}</IdeBadge>
        {r.submissionStatus && (
          <div style={{marginTop: 6}}>
            <SubChip status={r.submissionStatus} url={r.leaderboardUrl} />
          </div>
        )}
      </td>
      <td className={TD}>
        <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 160}}>
          <ResolvedBar resolved={r.resolvedTasks} total={r.totalTasks} />
          <span style={{whiteSpace: 'nowrap'}}>
            <strong>{scored}</strong> ({pct(r.resolvedTasks, r.totalTasks)}%)
          </span>
        </div>
      </td>
      <td className={TD}>k={r.k}</td>
      <td className={TD}>{fmtCost(r.costUsd)}</td>
      <td className={TD} style={{color: MUTED, whiteSpace: 'nowrap'}}>
        {fmtTok(r.inputTokens)} in · {fmtTok(r.outputTokens)} out
      </td>
      <td className={TD} style={{color: MUTED, whiteSpace: 'nowrap'}}>
        {r.activeAttemptCount ? `${r.activeAttemptCount} active · ` : ''}
        {duration(r.elapsedSeconds)} elapsed
        <div className='sysd-kv-label'>
          {r.finishedAt
            ? `done ${dateTime(r.finishedAt)}`
            : r.etaSeconds != null
              ? `ETA ${duration(r.etaSeconds)}`
              : relTime(r.createdAt)}
        </div>
      </td>
      <td className={TD} style={{width: 36}}>
        <ExternalLink size={15} aria-hidden='true' />
      </td>
    </tr>
  );
}

/** Compact phone representation of a run. The desktop table stays intact so
 * wide screens retain the comparison density; phones get a readable card with
 * the same run-level affordance and metrics. */
function RunCard({r, onOpen}: {r: api.BenchmarkRun; onOpen: () => void}) {
  const status = effectiveStatus(r);
  const scored = r.totalTasks > 0 ? `${r.resolvedTasks}/${r.totalTasks}` : String(r.resolvedTasks);
  return (
    <article className='ide-benchmark-mobile-card'>
      <div className='ide-benchmark-mobile-card-head'>
        <div style={{minWidth: 0}}>
          <div style={{display: 'flex', alignItems: 'center', gap: 8, minWidth: 0}}>
            <SuiteChip suite={r.suite} />
            <strong className='ide-benchmark-mobile-model'>{r.model}</strong>
          </div>
          <div className='ide-benchmark-mobile-provider'>{r.provider}</div>
        </div>
        <button
          type='button'
          className='ide-benchmark-mobile-open'
          onClick={onOpen}
          aria-label={`Open ${r.model} ${r.suite} benchmark`}
          title='Open benchmark details'
        >
          <ExternalLink size={16} aria-hidden='true' />
        </button>
      </div>
      <div className='ide-benchmark-mobile-dataset'>
        <span>{shortDataset(r.dataset)}</span>
        <IdeBadge kind={benchKind(status)}>{status}</IdeBadge>
      </div>
      <div className='ide-benchmark-mobile-progress'>
        <ResolvedBar resolved={r.resolvedTasks} total={r.totalTasks} />
        <span style={{whiteSpace: 'nowrap'}}>
          <strong>{scored}</strong> ({pct(r.resolvedTasks, r.totalTasks)}%)
        </span>
      </div>
      {r.submissionStatus && (
        <div className='ide-benchmark-mobile-submission'>
          <SubChip status={r.submissionStatus} url={r.leaderboardUrl} />
        </div>
      )}
      <div className='ide-benchmark-mobile-metrics'>
        <KV k='Cost'>{fmtCost(r.costUsd)}</KV>
        <KV k='Tokens'>
          {fmtTok(r.inputTokens)} in · {fmtTok(r.outputTokens)} out
        </KV>
        <KV k='Attempts'>k={r.k}</KV>
        <KV k='Time'>{dateTime(r.createdAt)}</KV>
        <KV k='Progress'>
          {r.activeAttemptCount || 0} active · {r.completedAttempts || 0} scored
        </KV>
      </div>
    </article>
  );
}

function RunTable({runs, empty}: {runs: api.BenchmarkRun[]; empty: string}) {
  const navigate = useNavigate();
  if (!runs.length) return <EmptyCard>{empty}</EmptyCard>;
  return (
    <div className='ide-card ide-benchmark-table-card'>
      <div className='ide-benchmark-mobile-list' aria-label='Benchmark runs'>
        {runs.map((r) => (
          <RunCard key={r.id} r={r} onOpen={() => navigate(`/benchmark/${r.id}`)} />
        ))}
      </div>
      <table className='ide-benchmark-table'>
        <thead>
          <tr>
            {['Benchmark', 'Dataset', 'Status', 'Resolved', 'K', 'Cost', 'Tokens', 'Time', ''].map(
              (h) => (
                <th key={h} className={TH}>
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

// Table cell metrics/type live in `.sysd-th` / `.sysd-td`
// (surfaces/systemdiscovery.css) so they ride the `--ide-t-*` ramp and its
// <=600px step-down. These aliases keep the existing `style={TH}` call sites
// honest about carrying only per-cell overrides.
const TH = 'sysd-th';
const TD = 'sysd-td';

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
    <span className='ide-mono sysd-attempts'>
      {Array.from({length: slots}).map((_, i) => {
        const a = attempts[i];
        if (!a)
          return (
            <span key={i} style={{color: MUTED, opacity: 0.4}} title='not yet run'>
              ·
            </span>
          );
        const pass = a.reward > 0;
        const extra = [
          a.outcome,
          a.failureClassification,
          a.reason,
          a.trajectorySha256 ? `trajectory ${a.trajectorySha256.slice(0, 10)}…` : null,
        ]
          .filter(Boolean)
          .join(' · ');
        return (
          <span
            key={i}
            style={{color: pass ? 'var(--ide-green)' : 'var(--ide-red)'}}
            title={`attempt ${i + 1}: ${pass ? 'pass' : 'fail'}${extra ? ` · ${extra}` : ''}`}
          >
            {pass ? (
              <Check size={12} strokeWidth={2.25} aria-hidden='true' />
            ) : (
              <X size={12} strokeWidth={2.25} aria-hidden='true' />
            )}
          </span>
        );
      })}
    </span>
  );
}

function TaskTable({groups, k, runDone}: {groups: TaskGroup[]; k: number; runDone: boolean}) {
  if (!groups.length) return <EmptyCard>No task scores yet.</EmptyCard>;
  return (
    <div className='ide-card sysd-scroll'>
      <table>
        <thead>
          <tr>
            {['Task', 'Result', `Attempts (k=${k})`, 'Cost', 'Tokens', 'Work'].map((h) => (
              <th key={h} className={TH}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {groups.map((g) => (
            <tr
              key={g.taskName}
              className={`sysd-tr is-${g.passed ? 'ok' : g.done >= k || runDone ? 'bad' : 'warn'}`}
            >
              <td className={`${TD} ide-mono`}>{g.taskName}</td>
              <td className={TD}>
                {g.passed ? (
                  <span
                    style={{
                      color: 'var(--ide-green)',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 4,
                    }}
                  >
                    <Check size={12} strokeWidth={2.25} aria-hidden='true' /> resolved
                  </span>
                ) : g.done >= k || runDone ? (
                  <span
                    style={{
                      color: 'var(--ide-red)',
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 4,
                    }}
                  >
                    <X size={12} strokeWidth={2.25} aria-hidden='true' /> failed
                  </span>
                ) : (
                  <span style={{color: MUTED}}>
                    … {g.done}/{k}
                  </span>
                )}
                {!g.passed && (g.outcome || g.reason) && (
                  <div className='sysd-sub'>
                    {[g.outcome, g.reason].filter(Boolean).join(' · ')}
                  </div>
                )}
                {!g.passed && g.attempts[g.attempts.length - 1]?.failureClassification && (
                  <div className='sysd-sub'>
                    {g.attempts[g.attempts.length - 1]?.failureClassification}
                  </div>
                )}
              </td>
              <td className={TD}>
                <AttemptPips attempts={g.attempts} k={k} />
              </td>
              <td className={TD}>{fmtCost(g.cost)}</td>
              <td className={TD} style={{color: MUTED, whiteSpace: 'nowrap'}}>
                {fmtTok(g.inTok)} in · {fmtTok(g.outTok)} out
              </td>
              <td className={TD}>
                {g.runId ? (
                  <Link to={`/result/${g.runId}`} style={{color: 'var(--ide-blue)'}}>
                    {g.runId.slice(0, 8)}
                  </Link>
                ) : (
                  <span style={{color: MUTED}}>—</span>
                )}
                {g.attempts
                  .filter((a) => a.trajectoryLocation)
                  .map((a) => (
                    <div
                      key={`trajectory-${a.attempt}`}
                      className='sysd-sub'
                      style={{maxWidth: 220}}
                      title={`${a.trajectoryLocation}\nsha256:${a.trajectorySha256}`}
                    >
                      a{a.attempt} trajectory · {a.trajectorySha256?.slice(0, 10)}…
                    </div>
                  ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ActiveAttempts({attempts}: {attempts: api.BenchmarkActiveAttempt[]}) {
  if (!attempts.length) return null;
  return (
    <div className='ide-card sysd-scroll'>
      <table>
        <thead>
          <tr>
            {['Task', 'Attempt', 'Elapsed', 'ETA', 'Last heartbeat'].map((h) => (
              <th key={h} className={TH}>
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {attempts.map((a) => (
            <tr key={a.id} className='sysd-tr'>
              <td className={`${TD} ide-mono`}>{a.taskName}</td>
              <td className={TD}>
                #{a.attempt}{' '}
                {a.stale && <span style={{color: 'var(--ide-red)'}}>· stale heartbeat</span>}
              </td>
              <td className={TD}>{duration((Date.now() - Date.parse(a.startedAt)) / 1000)}</td>
              <td className={TD}>{a.etaSeconds == null ? '—' : duration(a.etaSeconds)}</td>
              <td className={TD}>{relTime(a.lastProgressAt)}</td>
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
    <div className='ide-card sysd-eventlog'>
      {rev.map((e) => (
        <div key={e.id} className='sysd-event'>
          <span className='ide-mono sysd-event-type' style={{color: evColor(e.type)}}>
            {e.type}
          </span>
          <span className='sysd-event-msg'>{e.message || ''}</span>
          <span className='sysd-event-time'>{relTime(e.createdAt)}</span>
        </div>
      ))}
    </div>
  );
}

function Detail({d}: {d: api.BenchmarkDetail}) {
  const r = d.run;
  const activeAttempts = d.activeAttempts ?? [];
  const groups = groupTasks(d.tasks);
  const status = effectiveStatus(r);
  const runDone = ['DONE', 'FAILED', 'CANCELLED'].includes(status.toUpperCase());
  const resolved = groups.filter((g) => g.passed).length;
  const failed = groups.filter((g) => !g.passed && (g.done >= r.k || runDone)).length;
  const pending = groups.length - resolved - failed;
  return (
    <div>
      <div className='sysd-detail-head'>
        <Link to='/benchmark' className='ide-btn ghost'>
          <ArrowLeft size={15} aria-hidden='true' />
          Back
        </Link>
        <div className='ide-h2' style={{margin: 0}}>
          {r.model} · {r.suite}
        </div>
      </div>

      <div className='ide-card sysd-kv-grid'>
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
        <KV k='Readiness'>
          {r.submissionReadiness || 'NOT_REQUIRED'}
          {r.submissionReadinessReason && (
            <div className='sysd-sub'>{r.submissionReadinessReason}</div>
          )}
        </KV>
        <KV k='Elapsed / ETA'>
          {duration(r.elapsedSeconds)} / {r.etaSeconds == null ? '—' : duration(r.etaSeconds)}
        </KV>
        <KV k='Attempts'>
          {r.activeAttemptCount || 0} active · {r.completedAttempts || 0} scored
        </KV>
      </div>

      {r.leaderboardUrl && (
        <a
          className='ide-btn'
          href={r.leaderboardUrl}
          target='_blank'
          rel='noreferrer'
          style={{display: 'inline-block', marginTop: 12}}
        >
          View on leaderboard{' '}
          <ExternalLink
            size={12}
            strokeWidth={1.75}
            aria-hidden='true'
            style={{display: 'inline-block', verticalAlign: '-0.1em', marginLeft: 4}}
          />
        </a>
      )}
      {r.error && (
        <div style={{marginTop: 12}}>
          <ErrCard msg={r.error} />
        </div>
      )}

      {activeAttempts.length > 0 && (
        <>
          <div className='ide-h2' style={{marginTop: 18}}>
            Active attempts ({activeAttempts.length})
          </div>
          <ActiveAttempts attempts={activeAttempts} />
        </>
      )}

      <div className='ide-h2' style={{marginTop: 18}}>
        Tasks ({groups.length})
        <span className='sysd-kv-label' style={{fontWeight: 400, marginLeft: 8}}>
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
  // RR-1350 — the tracking controller only exists in local/dev/benchmark profiles.
  // In production it returns 404, which the 5s poll would otherwise hit forever.
  // Detect that once, stop polling, and say so instead of showing a sync error.
  const [unavailable, setUnavailable] = useState(false);
  const [lastSuccessfulRefresh, setLastSuccessfulRefresh] = useState<number | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [currentCollapsed, setCurrentCollapsed] = useState(false);
  const [historicalCollapsed, setHistoricalCollapsed] = useState(false);
  const groups = useMemo(() => groupRuns(runs ?? []), [runs]);

  const loadList = useCallback(async () => {
    setRefreshing(true);
    try {
      setRuns(await api.listBenchmarks());
      setErr('');
      setLastSuccessfulRefresh(Date.now());
    } catch (e: unknown) {
      // RR-1350 — a 404 means this environment does not host the tracking controller.
      if (Number((e as {status?: number}).status) === 404) {
        setUnavailable(true);
        setErr('');
        return;
      }
      setErr(e instanceof Error ? e.message : 'Failed to load benchmark runs.');
      // A transient telemetry failure must not replace the last known-good snapshot with an
      // authoritative-looking empty state.
      setRuns((current) => current ?? []);
    } finally {
      setRefreshing(false);
    }
  }, []);

  const loadDetail = useCallback(async (id: string) => {
    setRefreshing(true);
    try {
      setDetail(await api.getBenchmark(id));
      setErr('');
      setLastSuccessfulRefresh(Date.now());
    } catch (e: unknown) {
      if (Number((e as {status?: number}).status) === 404) {
        setUnavailable(true);
        setErr('');
        return;
      }
      setErr(e instanceof Error ? e.message : 'Failed to refresh benchmark detail.');
      // Keep the last good detail, but make the failed refresh visible to the operator.
    } finally {
      setRefreshing(false);
    }
  }, []);

  const refreshActive = useCallback(() => {
    if (benchmarkId) return loadDetail(benchmarkId);
    return loadList();
  }, [benchmarkId, loadDetail, loadList]);

  // List: initial load + 5s poll (skipped when the tab is hidden).
  useEffect(() => {
    if (!isAdmin || benchmarkId || unavailable) return;
    void loadList();
    const t = setInterval(() => {
      if (!document.hidden) void loadList();
    }, 5000);
    return () => clearInterval(t);
  }, [benchmarkId, isAdmin, loadList, unavailable]);

  // Detail route: load on open + 3s poll while selected (skipped when hidden).
  useEffect(() => {
    if (!benchmarkId) {
      setDetail(null);
      return;
    }
    if (unavailable) return;
    void loadDetail(benchmarkId);
    const t = setInterval(() => {
      if (!document.hidden) void loadDetail(benchmarkId);
    }, 3000);
    return () => clearInterval(t);
  }, [benchmarkId, loadDetail, unavailable]);

  // Browser timers are throttled in background tabs. Refetch immediately when the operator
  // returns instead of waiting for the next polling interval and presenting an old snapshot.
  useEffect(() => {
    if (!isAdmin) return;
    const onVisible = () => {
      if (!document.hidden) void refreshActive();
    };
    const onFocus = () => void refreshActive();
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('focus', onFocus);
    return () => {
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('focus', onFocus);
    };
  }, [isAdmin, refreshActive]);

  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);

  const staleAfterMs = benchmarkId ? 10_000 : 15_000;
  const isStale = lastSuccessfulRefresh !== null && now - lastSuccessfulRefresh > staleAfterMs;
  const syncLabel = lastSuccessfulRefresh
    ? `${err ? 'Sync error · showing data from' : isStale ? 'Stale · last synced' : 'Synced'} ${new Date(
        lastSuccessfulRefresh,
      ).toLocaleTimeString()}`
    : refreshing
      ? 'Syncing benchmark telemetry…'
      : err
        ? 'Benchmark telemetry unavailable'
        : 'Waiting for benchmark telemetry';

  const SyncStatus = () => (
    <div
      data-testid='benchmark-sync-status'
      aria-live='polite'
      className={`sysd-sync${err || isStale ? ' is-warn' : ''}`}
    >
      {refreshing && lastSuccessfulRefresh ? 'Refreshing · ' : ''}
      {syncLabel}
    </div>
  );

  if (!isAdmin) {
    return (
      <div className='ide-pane' style={{maxWidth: 760}}>
        <h1 className='ide-h1'>{benchmarkId ? 'Benchmark Detail' : 'Benchmarks'}</h1>
        <EmptyCard>This is an operator-only surface (SUPER_ADMIN).</EmptyCard>
      </div>
    );
  }

  if (unavailable) {
    // RR-1350 — the benchmark tracking backend is not deployed in this environment.
    return (
      <div className='ide-pane' style={{maxWidth: 760}}>
        <h1 className='ide-h1'>Benchmarks</h1>
        <EmptyCard>
          Benchmark tracking is not available in this environment. The tracking backend runs only in
          local, dev and dedicated benchmark deployments.
        </EmptyCard>
      </div>
    );
  }

  if (benchmarkId) {
    return (
      <div className='ide-pane' style={{maxWidth: 1180}}>
        <SyncStatus />
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
          style={{marginLeft: 'auto'}}
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

      <SyncStatus />

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
