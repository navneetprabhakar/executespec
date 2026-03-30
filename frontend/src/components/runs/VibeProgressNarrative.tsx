import { useMemo } from 'react';
import { Loader2, CheckCircle2, AlertTriangle } from 'lucide-react';
import { useSseStore } from '@/stores/useSseStore';
import { useRunStore } from '@/stores/useRunStore';
import { mapEventToNarrative, type NarrativeItem } from '@/lib/vibeNarrative';
import { useViewMode } from '@/hooks/useViewMode';
import { ACTIVE_STATUSES } from '@/lib/constants';
import VibeRunSummary from './VibeRunSummary';

/** Derive a narrative item from the run's persisted status (no SSE needed). */
function statusToNarrative(status: string): NarrativeItem | null {
  switch (status) {
    case 'PLANNING':
    case 'AWAITING_APPROVAL':
      return { text: 'Analyzing requirements...', variant: 'progress' };
    case 'WORKING':
      return { text: 'Building...', variant: 'progress' };
    case 'COMPILING':
      return { text: 'Checking the build...', variant: 'progress' };
    case 'MERGING':
      return { text: 'Assembling the pieces...', variant: 'progress' };
    case 'REVIEWING':
      return { text: 'Reviewing the code...', variant: 'progress' };
    case 'COMPLETE':
      return { text: 'Code generated.', variant: 'done' };
    case 'PARTIAL':
      return { text: 'Ready.', variant: 'done' };
    case 'FAILED':
      return { text: 'Execution halted.', variant: 'warning' };
    default:
      return null;
  }
}

const ICON: Record<NarrativeItem['variant'], React.ReactNode> = {
  progress: <Loader2 className="h-4 w-4 text-accent-violet animate-spin shrink-0" />,
  done: <CheckCircle2 className="h-4 w-4 text-[var(--mc-status-success)] shrink-0" />,
  warning: <AlertTriangle className="h-4 w-4 text-[var(--mc-status-error)] shrink-0" />,
};

export default function VibeProgressNarrative() {
  const entries = useSseStore((s) => s.entries);
  const activeRun = useRunStore((s) => s.activeRun);
  const { escalateToPro } = useViewMode();

  const narrative = useMemo(() => {
    const items: { id: string; text: string; variant: NarrativeItem['variant']; ts: number }[] = [];
    for (const entry of entries) {
      const mapped = mapEventToNarrative(entry);
      if (mapped) {
        items.push({ id: entry.id, text: mapped.text, variant: mapped.variant, ts: entry.timestamp });
      }
    }

    // If no SSE-derived narrative yet but we have a run status, synthesize
    // an initial narrative from the run's current state so toggling between
    // runs doesn't flash "Starting up..." while history loads.
    if (items.length === 0 && activeRun) {
      const synth = statusToNarrative(activeRun.status);
      if (synth) {
        items.push({
          id: 'synth-status',
          text: synth.text,
          variant: synth.variant,
          ts: activeRun.startedAt ? new Date(activeRun.startedAt).getTime() : Date.now(),
        });
      }
    }

    return items;
  }, [entries, activeRun?.status]);

  // Task progress counter
  const tasksDone = activeRun?.tasks?.filter((t) => t.status === 'COMPLETED').length ?? 0;
  const tasksTotal = activeRun?.tasks?.length ?? 0;
  const isActive = activeRun ? ACTIVE_STATUSES.has(activeRun.status) : false;
  const isFailed = activeRun?.status === 'FAILED';
  const isComplete = activeRun?.status === 'COMPLETE';

  // Progress bar percentage
  const progress = tasksTotal > 0 ? (tasksDone / tasksTotal) * 100 : 0;

  return (
    <div className="flex flex-col h-full">
      {/* Progress bar */}
      {isActive && (
        <div className="h-0.5 w-full bg-[var(--mc-bg-3)]">
          <div
            className="h-full bg-accent-violet transition-all duration-500"
            style={{ width: `${progress}%` }}
          />
        </div>
      )}

      <div className="flex-1 overflow-y-auto p-4 space-y-3">
        {/* Narrative events */}
        {narrative.map((item) => (
          <div key={item.id} className="flex items-start gap-3">
            {ICON[item.variant]}
            <div className="flex-1 min-w-0">
              <span className="text-sm text-[var(--mc-text-primary)]">{item.text}</span>
              {tasksTotal > 0 && item.text === 'Building...' && (
                <span className="ml-2 text-xs text-[var(--mc-text-muted)]">
                  {tasksDone} of {tasksTotal} tasks done
                </span>
              )}
            </div>
            <span className="text-xs text-[var(--mc-text-muted)] shrink-0">
              {new Date(item.ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </span>
          </div>
        ))}

        {/* Empty state */}
        {narrative.length === 0 && isActive && (
          <div className="flex items-center gap-3 text-sm text-[var(--mc-text-muted)]">
            <Loader2 className="h-4 w-4 animate-spin" />
            Starting up...
          </div>
        )}

        {/* Completion summary */}
        {isComplete && activeRun && <VibeRunSummary run={activeRun} />}

        {/* Failure */}
        {isFailed && activeRun && (
          <div className="mt-4 rounded-sm border border-[var(--mc-status-error)]/20 bg-[var(--mc-status-error)]/5 p-4">
            <p className="text-sm text-[var(--mc-status-error)] font-medium mb-1">Run failed</p>
            {activeRun.failureReason && (
              <p className="text-xs text-[var(--mc-text-secondary)] mb-3">{activeRun.failureReason}</p>
            )}
            <button
              onClick={escalateToPro}
              className="text-xs text-accent-violet hover:text-accent-violet/80 font-medium"
            >
              View full details →
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
