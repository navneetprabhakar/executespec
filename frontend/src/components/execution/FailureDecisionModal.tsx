import { useMemo, useState } from 'react';
import type { DecisionAction, SolutionDto } from '@/types/execution';
import { useExecutionStore } from '@/stores/useExecutionStore';

interface Props {
  executionId: string;
  failed: SolutionDto;
  onClose: () => void;
}

/**
 * v2.5.3 (UX-5.2) — failure decision modal with dependency impact.
 *
 * Computes which solutions in the current execution have a `dependencyIds`
 * edge pointing at the failed solution. Skipping/attempting-anyway reveals
 * that impact up-front; retry/fix don't need to show it.
 *
 * Buttons replace the previous dropdown so the primary/secondary hierarchy
 * is obvious at a glance. ATTEMPT_ANYWAY carries a warning label.
 */
export default function FailureDecisionModal({ executionId, failed, onClose }: Props) {
  const { decide, signalManualReady, executionRun } = useExecutionStore();
  const [submitting, setSubmitting] = useState<DecisionAction | null>(null);

  // Downstream solutions are any ExecutionSolution whose dependencyIds include
  // the failed solution's id. These are the ones that will be blocked by a
  // SKIP / ATTEMPT_ANYWAY choice.
  const blocked = useMemo(() => {
    const all = executionRun?.solutions ?? [];
    return all.filter((s) => s.dependencyIds?.includes(failed.id));
  }, [executionRun, failed.id]);

  const act = async (action: DecisionAction) => {
    setSubmitting(action);
    try {
      await decide(executionId, failed.id, action);
      if (action === 'FIX') {
        // Leave modal open — user clicks "Signal manual fix done" when ready.
        return;
      }
      onClose();
    } finally {
      setSubmitting(null);
    }
  };

  const doSignal = async () => {
    setSubmitting('FIX');
    try {
      await signalManualReady(executionId, failed.id);
      onClose();
    } finally {
      setSubmitting(null);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center">
      <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md p-4 w-full max-w-lg space-y-3">
        <div className="flex items-start justify-between gap-3">
          <h3 className="text-[13px] font-medium text-primary">
            ✗ Solution S{failed.sequenceNumber} failed
          </h3>
          <button onClick={onClose} className="text-muted hover:text-primary text-[14px] leading-none">×</button>
        </div>

        <div className="text-[11px] space-y-1">
          <div className="text-primary font-medium">{failed.title}</div>
          {failed.failurePhase && (
            <div className="text-muted">Phase: <span className="font-mono">{failed.failurePhase}</span></div>
          )}
          {failed.failureReason && (
            <div className="rounded-sm border border-accent-red/40 bg-accent-red/[0.06] px-2 py-1 text-accent-red">
              {failed.failureReason}
            </div>
          )}
        </div>

        {blocked.length > 0 && (
          <div className="rounded-sm border border-accent-amber/40 bg-accent-amber/10 px-2 py-1.5 text-[11px]">
            <div className="font-medium text-accent-amber mb-0.5">
              Blocked downstream ({blocked.length})
            </div>
            <ul className="space-y-0.5">
              {blocked.map((b) => (
                <li key={b.id} className="text-muted">
                  <span className="font-mono text-[10px]">○</span> S{b.sequenceNumber} · {b.title}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div className="grid grid-cols-2 gap-2 pt-1">
          <button
            disabled={submitting !== null}
            onClick={() => act('RETRY')}
            className="text-[11px] rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue px-2 py-1.5 disabled:opacity-40 hover:bg-accent-blue/20"
          >
            {submitting === 'RETRY' ? 'Retrying…' : 'Retry'}
          </button>
          <button
            disabled={submitting !== null}
            onClick={() => act('SKIP')}
            title={blocked.length > 0 ? `Skips this and the ${blocked.length} dependent solution(s).` : 'Skip this solution.'}
            className="text-[11px] rounded-sm border border-[var(--border-default)] text-muted px-2 py-1.5 disabled:opacity-40 hover:text-primary"
          >
            {submitting === 'SKIP' ? 'Skipping…'
              : blocked.length > 0 ? `Skip this + ${blocked.length} dependent${blocked.length === 1 ? '' : 's'}`
              : 'Skip this solution'}
          </button>
          <button
            disabled={submitting !== null}
            onClick={() => act('FIX')}
            className="text-[11px] rounded-sm border border-[var(--border-default)] text-muted px-2 py-1.5 disabled:opacity-40 hover:text-primary"
          >
            {submitting === 'FIX' ? 'Waiting for manual fix…' : 'Fix manually'}
          </button>
          <button
            disabled={submitting !== null}
            onClick={() => act('ATTEMPT_ANYWAY')}
            title="Downstream solutions will run without this solution's output. May produce incomplete code."
            className="text-[11px] rounded-sm border border-accent-amber/40 bg-accent-amber/10 text-accent-amber px-2 py-1.5 disabled:opacity-40 hover:bg-accent-amber/20"
          >
            {submitting === 'ATTEMPT_ANYWAY' ? 'Proceeding…' : '⚠ Attempt anyway'}
          </button>
        </div>

        {submitting === 'FIX' && (
          <div className="rounded-sm border border-emerald-500/40 bg-emerald-500/10 px-2 py-1.5 space-y-1">
            <div className="text-[10px] text-muted">
              Push your fix to the execution branch on GitHub, then signal done below.
            </div>
            <button
              onClick={doSignal}
              className="text-[11px] rounded-sm bg-emerald-500/20 hover:bg-emerald-500/30 text-emerald-400 px-2 py-1"
            >
              Signal manual fix done
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
