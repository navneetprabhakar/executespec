import { cn } from '@/lib/utils';
import type { ExecutionRunStatus } from '@/types/execution';

const STEPS: { key: string; label: string; match: ExecutionRunStatus[] }[] = [
  { key: 'setup', label: 'Setup', match: ['SETUP'] },
  { key: 'analysis', label: 'Analysis', match: ['ANALYSING'] },
  { key: 'arch', label: 'Arch review', match: ['ARCH_REVIEW'] },
  { key: 'plan', label: 'Plan', match: ['PLANNING'] },
  { key: 'exec', label: 'Execute', match: ['EXECUTING', 'PAUSED'] },
  { key: 'done', label: 'Complete', match: ['COMPLETE', 'ABORTED'] },
];

export default function ExecutionStepper({ status }: { status: ExecutionRunStatus }) {
  const currentIdx = STEPS.findIndex((s) => s.match.includes(status));
  return (
    <div className="flex items-center gap-1 text-[11px] text-muted">
      {STEPS.map((step, i) => {
        const isCurrent = i === currentIdx;
        const isDone = i < currentIdx;
        return (
          <div key={step.key} className="flex items-center gap-1">
            <span
              className={cn(
                'inline-flex items-center justify-center w-5 h-5 rounded-full border text-[10px] font-mono',
                isCurrent
                  ? 'bg-accent-blue text-white border-accent-blue'
                  : isDone
                  ? 'bg-emerald-500/20 text-emerald-400 border-emerald-500/40'
                  : 'text-muted border-[var(--border-default)]',
              )}
            >
              {isDone ? '✓' : i + 1}
            </span>
            <span className={cn(isCurrent && 'text-primary font-medium')}>{step.label}</span>
            {i < STEPS.length - 1 && <span className="mx-1 text-muted">›</span>}
          </div>
        );
      })}
    </div>
  );
}
