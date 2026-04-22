import { useMemo, useState } from 'react';
import type { TaskSummary } from '@/lib/api';

/**
 * Gantt-ish swimlane timeline for a run's tasks.
 *
 * One row per task. Bar X = `startedAt` (relative to earliest task start),
 * width = `completedAt - startedAt` (or `now - startedAt` for still-running).
 * Tasks with no startedAt render as a dashed placeholder after the latest
 * completed bar ("queued").
 *
 * Status-coloured. WORKING bars pulse. Hover shows duration + model + tokens.
 *
 * Built to be the default "Progress" tab for V2 runs, where the DAG view
 * collapses to a single column but users want to see "where did the time go".
 */

interface Props {
  tasks: TaskSummary[] | null | undefined;
  runStartedAt?: string | null;
}

// Status → colour token, matching the existing TaskGraph palette.
const STATUS_FILL: Record<string, string> = {
  COMPLETE: '#10B981',
  WORKING:  '#3B82F6',
  RUNNING:  '#3B82F6',
  COMPILING:'#3B82F6',
  MERGING:  '#3B82F6',
  PLANNING: '#8B5CF6',
  REVIEWING:'#8B5CF6',
  PENDING:  '#4B5563',
  QUEUED:   '#4B5563',
  PARTIAL:  '#F59E0B',
  BLOCKED:  '#F59E0B',
  FAILED:   '#EF4444',
  CANCELLED:'#6B7280',
  SKIPPED:  '#6B7280',
};

const pillClass = (status: string): string => {
  const s = (status || '').toUpperCase();
  if (s === 'COMPLETE') return 'bg-status-success/15 text-status-success';
  if (['WORKING', 'RUNNING', 'COMPILING', 'MERGING'].includes(s)) return 'bg-blue-500/15 text-blue-300';
  if (['PLANNING', 'REVIEWING'].includes(s)) return 'bg-violet-500/15 text-violet-300';
  if (['PARTIAL', 'BLOCKED'].includes(s)) return 'bg-status-waiting/15 text-status-waiting';
  if (s === 'FAILED') return 'bg-status-error/15 text-status-error';
  return 'bg-neutral-500/15 text-neutral-300';
};

/** Format a short duration like "1m 14s" / "45s" / "820ms". */
function formatDuration(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)}ms`;
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rs = s % 60;
  return rs === 0 ? `${m}m` : `${m}m ${rs}s`;
}

/** Pick 5–8 evenly-spaced tick marks for the x-axis, with readable labels. */
function pickTicks(totalMs: number): Array<{ pct: number; label: string }> {
  if (totalMs <= 0) return [];
  const targetCount = 6;
  // Candidate spacings in ms.
  const candidates = [
    1_000, 2_000, 5_000, 10_000, 15_000, 30_000,
    60_000, 2 * 60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000,
    3_600_000, 2 * 3_600_000, 6 * 3_600_000,
  ];
  let step = candidates[0];
  for (const c of candidates) {
    if (totalMs / c <= targetCount + 1) { step = c; break; }
    step = c;
  }
  const out: Array<{ pct: number; label: string }> = [];
  for (let t = 0; t <= totalMs; t += step) {
    out.push({ pct: (t / totalMs) * 100, label: formatDuration(t) });
    if (out.length > 12) break;
  }
  return out;
}

export default function TaskTimeline({ tasks, runStartedAt }: Props) {
  const [hoverId, setHoverId] = useState<string | null>(null);

  const { rows, totalMs, anchorMs, hasAny, runningCount, completedCount } = useMemo(() => {
    const arr = (tasks ?? []).filter(t => !t.parentTaskId); // top-level only
    if (arr.length === 0) {
      return { rows: [], totalMs: 0, anchorMs: 0, hasAny: false, runningCount: 0, completedCount: 0 };
    }

    // Anchor: earliest startedAt of any task, falling back to runStartedAt.
    const startedTimes = arr
      .map(t => t.startedAt ? new Date(t.startedAt).getTime() : null)
      .filter((n): n is number => n !== null);
    const runAnchor = runStartedAt ? new Date(runStartedAt).getTime() : null;
    const anchor = startedTimes.length > 0
      ? Math.min(...startedTimes, runAnchor ?? Number.POSITIVE_INFINITY)
      : (runAnchor ?? Date.now());

    // End: latest completedAt or now if any task is still running.
    const completedTimes = arr
      .map(t => t.completedAt ? new Date(t.completedAt).getTime() : null)
      .filter((n): n is number => n !== null);
    const anyRunning = arr.some(t => {
      const s = (t.status || '').toUpperCase();
      return ['WORKING', 'RUNNING', 'COMPILING', 'MERGING', 'PLANNING', 'REVIEWING'].includes(s);
    });
    const end = anyRunning
      ? Date.now()
      : completedTimes.length > 0
        ? Math.max(...completedTimes)
        : anchor;
    const total = Math.max(1, end - anchor);

    let done = 0, running = 0;
    const rows = arr.map(t => {
      const s = (t.status || '').toUpperCase();
      const startedMs = t.startedAt ? new Date(t.startedAt).getTime() : null;
      const completedMs = t.completedAt ? new Date(t.completedAt).getTime() : null;
      const isRunning = ['WORKING', 'RUNNING', 'COMPILING', 'MERGING', 'PLANNING', 'REVIEWING'].includes(s);
      if (s === 'COMPLETE') done++;
      if (isRunning) running++;

      // Where does the bar go?
      //   has startedAt + completedAt  → [startedAt, completedAt]
      //   has startedAt, still running → [startedAt, now]
      //   has neither                  → ghost "queued" bar at the far right
      let startPct: number, widthPct: number, ghost = false;
      if (startedMs != null) {
        const endMs = completedMs ?? Date.now();
        startPct = ((startedMs - anchor) / total) * 100;
        widthPct = Math.max(0.6, ((endMs - startedMs) / total) * 100);
      } else {
        ghost = true;
        startPct = 98;
        widthPct = 1.5;
      }

      const durMs = (startedMs != null)
        ? (completedMs ?? Date.now()) - startedMs
        : (t.durationMs ?? 0);

      return {
        id: t.taskId,
        title: t.title,
        status: s,
        complexity: t.status, // unused; kept for future
        startPct,
        widthPct,
        ghost,
        isRunning,
        durMs,
        inputTokens: t.inputTokens,
        outputTokens: t.outputTokens,
        provider: t.provider ?? null,
        model: t.model ?? t.assignedModel ?? null,
        failureReason: t.failureReason ?? null,
      };
    });

    return {
      rows, totalMs: total, anchorMs: anchor, hasAny: true,
      runningCount: running, completedCount: done,
    };
  }, [tasks, runStartedAt]);

  if (!hasAny) {
    return (
      <div className="p-8 text-center text-sm text-[var(--text-muted)]">
        No tasks yet — the plan hasn't materialised.
      </div>
    );
  }

  const ticks = pickTicks(totalMs);

  return (
    <div className="w-full bg-[#0a0a0f] border border-[var(--border-default)] rounded-sm">
      {/* Header strip */}
      <div className="flex items-center gap-4 px-4 py-2 border-b border-[var(--border-default)] text-xs">
        <span className="text-[var(--text-muted)]">
          Run anchor: <span className="text-[var(--text-secondary)]">
            {new Date(anchorMs).toLocaleTimeString()}
          </span>
        </span>
        <span className="text-[var(--text-muted)]">·</span>
        <span className="text-[var(--text-muted)]">
          Total: <span className="text-[var(--text-secondary)]">{formatDuration(totalMs)}</span>
        </span>
        <span className="text-[var(--text-muted)]">·</span>
        <span className={pillClass('COMPLETE') + ' px-2 py-0.5 rounded-full'}>
          {completedCount} done
        </span>
        {runningCount > 0 && (
          <span className={pillClass('WORKING') + ' px-2 py-0.5 rounded-full'}>
            {runningCount} running
          </span>
        )}
        <span className="ml-auto text-[var(--text-muted)]">
          {rows.length} task{rows.length === 1 ? '' : 's'}
        </span>
      </div>

      {/* X-axis */}
      <div className="relative mx-48 mr-4 h-6 border-b border-[var(--border-default)]">
        {ticks.map((t, i) => (
          <div
            key={i}
            className="absolute top-0 h-full border-l border-white/10"
            style={{ left: `${t.pct}%` }}
          >
            <span className="absolute top-0 left-1 text-[10px] text-[var(--text-muted)] whitespace-nowrap">
              {t.label}
            </span>
          </div>
        ))}
      </div>

      {/* Rows */}
      <div className="py-2">
        {rows.map(r => {
          const fill = STATUS_FILL[r.status] ?? STATUS_FILL.PENDING;
          const isHover = hoverId === r.id;
          return (
            <div
              key={r.id}
              className="relative flex items-center h-8 hover:bg-white/5"
              onMouseEnter={() => setHoverId(r.id)}
              onMouseLeave={() => setHoverId(null)}
            >
              {/* Label gutter */}
              <div className="w-48 shrink-0 px-3 flex items-center gap-2 overflow-hidden">
                <span className={`w-2 h-2 rounded-full shrink-0`} style={{ background: fill }} />
                <span className="truncate text-xs text-[var(--text-primary)]" title={r.title}>
                  {r.title}
                </span>
              </div>

              {/* Track */}
              <div className="flex-1 relative h-6 mr-4 rounded-sm bg-white/5">
                <div
                  className={`absolute top-0 bottom-0 rounded-sm ${r.isRunning ? 'animate-pulse' : ''}`}
                  style={{
                    left: `${Math.max(0, Math.min(99.5, r.startPct))}%`,
                    width: `${Math.max(0.6, Math.min(100 - r.startPct, r.widthPct))}%`,
                    background: r.ghost ? 'transparent' : fill,
                    opacity: r.ghost ? 1 : 0.85,
                    border: r.ghost ? `1px dashed ${fill}` : undefined,
                  }}
                />
              </div>

              {/* Right-edge chips */}
              <div className="w-24 shrink-0 pr-3 flex items-center justify-end gap-2">
                <span className={`px-1.5 py-0.5 rounded text-[10px] font-medium ${pillClass(r.status)}`}>
                  {r.status}
                </span>
              </div>

              {/* Hover tooltip */}
              {isHover && (
                <div className="absolute top-full left-48 z-10 mt-1 rounded-sm bg-[#111] border border-white/10 p-3 text-xs shadow-xl min-w-[280px]">
                  <div className="font-medium text-[var(--text-primary)] truncate">{r.title}</div>
                  <div className="mt-1 grid grid-cols-2 gap-x-3 gap-y-1 text-[var(--text-muted)]">
                    <span>Status</span><span className="text-[var(--text-secondary)]">{r.status}</span>
                    <span>Duration</span>
                    <span className="text-[var(--text-secondary)]">
                      {r.ghost ? 'queued' : formatDuration(r.durMs)}
                    </span>
                    {r.model && (
                      <>
                        <span>Model</span>
                        <span className="text-[var(--text-secondary)] truncate">
                          {r.provider ? `${r.provider}:` : ''}{r.model}
                        </span>
                      </>
                    )}
                    {(r.inputTokens > 0 || r.outputTokens > 0) && (
                      <>
                        <span>Tokens</span>
                        <span className="text-[var(--text-secondary)]">
                          {r.inputTokens.toLocaleString()} in · {r.outputTokens.toLocaleString()} out
                        </span>
                      </>
                    )}
                    {r.failureReason && (
                      <>
                        <span className="col-span-2 mt-1 text-status-error">Failure</span>
                        <span className="col-span-2 text-status-error whitespace-pre-wrap">{r.failureReason}</span>
                      </>
                    )}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
