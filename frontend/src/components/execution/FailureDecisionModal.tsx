import { useState } from 'react';
import type { DecisionAction, SolutionDto } from '@/types/execution';
import { useExecutionStore } from '@/stores/useExecutionStore';

interface Props {
  executionId: string;
  failed: SolutionDto;
  onClose: () => void;
}

const ACTIONS: DecisionAction[] = ['RETRY', 'SKIP', 'FIX', 'ATTEMPT_ANYWAY'];

export default function FailureDecisionModal({ executionId, failed, onClose }: Props) {
  const { decide, signalManualReady } = useExecutionStore();
  const [action, setAction] = useState<DecisionAction>('RETRY');
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    setSubmitting(true);
    try {
      await decide(executionId, failed.id, action);
      if (action === 'FIX') {
        // Leave open — user can click "Signal manual fix done" when ready.
        setSubmitting(false);
        return;
      }
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 bg-black/60 flex items-center justify-center">
      <div className="bg-[var(--gray-100)] border border-[var(--border-default)] rounded-md p-4 w-full max-w-lg space-y-3">
        <h3 className="text-[13px] font-medium text-primary">
          Solution S{failed.sequenceNumber} failed
        </h3>
        <div className="text-[11px] text-accent-red">
          <div>{failed.title}</div>
          <div className="text-muted">Phase: {failed.failurePhase ?? '—'}</div>
          <div>{failed.failureReason ?? 'No reason captured.'}</div>
        </div>

        <div className="flex items-center gap-2 text-[11px]">
          <span className="text-muted">Decision:</span>
          <select
            value={action}
            onChange={(e) => setAction(e.target.value as DecisionAction)}
            className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1"
          >
            {ACTIONS.map((a) => (
              <option key={a} value={a}>{a}</option>
            ))}
          </select>
        </div>

        {action === 'FIX' && (
          <button
            onClick={async () => { await signalManualReady(executionId, failed.id); onClose(); }}
            className="text-[11px] rounded-sm border border-emerald-500/40 bg-emerald-500/10 text-emerald-400 px-2 py-1"
          >
            Signal manual fix done
          </button>
        )}

        <div className="flex justify-end gap-2 pt-2">
          <button onClick={onClose} className="text-[11px] text-muted px-2 py-1">Cancel</button>
          <button
            onClick={submit}
            disabled={submitting}
            className="rounded-sm border border-accent-blue/40 bg-accent-blue/10 text-accent-blue px-3 py-1 text-[11px] disabled:opacity-40"
          >
            {submitting ? 'Submitting…' : 'Submit decision'}
          </button>
        </div>
      </div>
    </div>
  );
}
