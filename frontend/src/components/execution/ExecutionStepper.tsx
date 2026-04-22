import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';
import type { ExecutionRunStatus } from '@/types/execution';

/**
 * v2.5.2 (UX-3.2) — progress breadcrumb with user-facing labels, descriptions,
 * and live elapsed time on the active step.
 *
 * Labels intentionally replace backend enum values ("ARCH_REVIEW" →
 * "Review architecture") so non-developer users aren't reading internal jargon.
 * Each step exposes a one-sentence hover description as a title tooltip.
 */

interface Step {
  key: string;
  label: string;
  description: string;
  match: ExecutionRunStatus[];
}

const STEPS: Step[] = [
  { key: 'setup',    label: 'Setup',               match: ['SETUP'],
    description: 'Creating repos and linking them to your project.' },
  { key: 'analysis', label: 'Analysing codebase',  match: ['ANALYSING'],
    description: 'Reading your repos and mapping the architecture.' },
  { key: 'arch',     label: 'Review architecture', match: ['ARCH_REVIEW'],
    description: 'Check the generated architecture before planning starts.' },
  { key: 'plan',     label: 'Planning',            match: ['PLANNING'],
    description: 'Breaking your requirement into tasks across repos.' },
  { key: 'exec',     label: 'Building',            match: ['EXECUTING'],
    description: 'Agents are writing code across your repos.' },
  { key: 'paused',   label: 'Action needed',       match: ['PAUSED'],
    description: 'A task hit an issue. Choose how to proceed.' },
  { key: 'done',     label: 'Done',                match: ['COMPLETE', 'ABORTED'],
    description: 'All tasks complete, PRs raised.' },
];

export default function ExecutionStepper({
  status,
  activeSince,
}: {
  status: ExecutionRunStatus;
  /** ISO timestamp of the last status transition — drives the elapsed-time counter. */
  activeSince?: string | null;
}) {
  // Collapse PAUSED into EXECUTING so the breadcrumb doesn't add an extra
  // column mid-run; the PAUSED state is surfaced via the label swap instead.
  const visible = STEPS.filter((s) => s.key !== 'paused');
  const currentIdx = visible.findIndex((s) =>
    s.match.includes(status) ||
    (status === 'PAUSED' && s.key === 'exec'),
  );

  const elapsed = useElapsed(activeSince);

  return (
    <div className="flex items-center gap-1 text-xs text-muted flex-wrap">
      {visible.map((step, i) => {
        const isCurrent = i === currentIdx;
        const isDone = i < currentIdx;
        const label = status === 'PAUSED' && step.key === 'exec'
          ? 'Paused — action needed'
          : step.label;
        return (
          <div key={step.key} className="flex items-center gap-1" title={step.description}>
            <span
              className={cn(
                'inline-flex items-center justify-center w-5 h-5 rounded-full border text-[11px] font-mono',
                isCurrent
                  ? (status === 'PAUSED'
                      ? 'bg-accent-amber text-white border-accent-amber'
                      : 'bg-accent-blue text-white border-accent-blue')
                  : isDone
                  ? 'bg-status-success/20 text-status-success border-status-success/40'
                  : 'text-muted border-[var(--border-default)]',
              )}
            >
              {isDone ? '✓' : isCurrent ? <Spinner paused={status === 'PAUSED'} /> : i + 1}
            </span>
            <span className={cn(isCurrent && 'text-primary font-medium')}>{label}</span>
            {isCurrent && elapsed && (
              <span className="text-[9px] font-mono text-muted">({elapsed})</span>
            )}
            {i < visible.length - 1 && <span className="mx-1 text-muted">›</span>}
          </div>
        );
      })}
    </div>
  );
}

function Spinner({ paused }: { paused: boolean }) {
  // Paused steps shouldn't pulse — the user's action is what unblocks them.
  if (paused) return <span>⏸</span>;
  return <span className="inline-block w-1.5 h-1.5 rounded-full bg-white animate-pulse" />;
}

/**
 * Ticks every 10s when activeSince is set. Returns "42s" / "3m 21s" / "1h 4m".
 * Null when the input is missing or parseable as NaN.
 */
function useElapsed(activeSince?: string | null): string | null {
  const [_, setTick] = useState(0);
  useEffect(() => {
    if (!activeSince) return;
    const id = setInterval(() => setTick((t) => t + 1), 10_000);
    return () => clearInterval(id);
  }, [activeSince]);
  if (!activeSince) return null;
  const start = new Date(activeSince).getTime();
  if (Number.isNaN(start)) return null;
  const secs = Math.max(0, Math.floor((Date.now() - start) / 1000));
  if (secs < 60) return `${secs}s`;
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m ${secs % 60}s`;
  const hrs = Math.floor(mins / 60);
  return `${hrs}h ${mins % 60}m`;
}
