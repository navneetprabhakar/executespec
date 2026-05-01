import {useMemo, useState} from 'react';
import type {DecisionAction, SolutionDto} from '@/types/execution';
import {useExecutionStore} from '@/stores/useExecutionStore';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';

interface Props {
  executionId: string;
  failed: SolutionDto;
  onClose: () => void;
}

export default function FailureDecisionModal({executionId, failed, onClose}: Props) {
  const {decide, signalManualReady, executionRun} = useExecutionStore();
  const [submitting, setSubmitting] = useState<DecisionAction | null>(null);

  const blocked = useMemo(() => {
    const all = executionRun?.solutions ?? [];
    return all.filter((s) => s.dependencyIds?.includes(failed.id));
  }, [executionRun, failed.id]);

  const act = async (action: DecisionAction) => {
    setSubmitting(action);
    try {
      await decide(executionId, failed.id, action);
      if (action === 'FIX') return;
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
    <ResponsiveModal
      open={true}
      onClose={onClose}
      title={`✗ Solution S${failed.sequenceNumber} failed`}
      maxWidth='max-w-lg'
    >
      <div className='text-xs space-y-1'>
        <div className='text-primary font-medium'>{failed.title}</div>
        {failed.failurePhase && (
          <div className='text-muted'>
            Phase: <span className='font-mono'>{failed.failurePhase}</span>
          </div>
        )}
        {failed.failureReason && (
          <div className='rounded-sm border border-accent-red/40 bg-accent-red/[0.06] px-2 py-1 text-accent-red'>
            {failed.failureReason}
          </div>
        )}
      </div>

      {blocked.length > 0 && (
        <div className='mt-3 rounded-sm border border-accent-amber/40 bg-accent-amber/10 px-2 py-1.5 text-xs'>
          <div className='font-medium text-accent-amber mb-0.5'>
            Blocked downstream ({blocked.length})
          </div>
          <ul className='space-y-0.5'>
            {blocked.map((b) => (
              <li key={b.id} className='text-muted'>
                <span className='font-mono text-[11px]'>○</span> S{b.sequenceNumber} · {b.title}
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className='grid grid-cols-2 gap-2 pt-3'>
        <Button
          variant='primary'
          size='sm'
          disabled={submitting !== null}
          loading={submitting === 'RETRY'}
          onClick={() => act('RETRY')}
        >
          {submitting === 'RETRY' ? 'Retrying…' : 'Retry'}
        </Button>
        <Button
          variant='secondary'
          size='sm'
          disabled={submitting !== null}
          loading={submitting === 'SKIP'}
          onClick={() => act('SKIP')}
          title={
            blocked.length > 0
              ? `Skips this and the ${blocked.length} dependent solution(s).`
              : 'Skip this solution.'
          }
        >
          {submitting === 'SKIP'
            ? 'Skipping…'
            : blocked.length > 0
              ? `Skip this + ${blocked.length} dependent${blocked.length === 1 ? '' : 's'}`
              : 'Skip this solution'}
        </Button>
        <Button
          variant='secondary'
          size='sm'
          disabled={submitting !== null}
          loading={submitting === 'FIX'}
          onClick={() => act('FIX')}
        >
          {submitting === 'FIX' ? 'Waiting for manual fix…' : 'Fix manually'}
        </Button>
        <Button
          variant='tertiary'
          size='sm'
          disabled={submitting !== null}
          loading={submitting === 'ATTEMPT_ANYWAY'}
          onClick={() => act('ATTEMPT_ANYWAY')}
          title="Downstream solutions will run without this solution's output. May produce incomplete code."
          className='border-accent-amber/40 text-accent-amber hover:bg-accent-amber/10'
        >
          {submitting === 'ATTEMPT_ANYWAY' ? 'Proceeding…' : '⚠ Attempt anyway'}
        </Button>
      </div>

      {submitting === 'FIX' && (
        <div className='mt-3 rounded-sm border border-status-success/40 bg-status-success/10 px-2 py-1.5 space-y-1'>
          <div className='text-[11px] text-muted'>
            Push your fix to the execution branch on GitHub, then signal done below.
          </div>
          <Button variant='success' size='sm' onClick={doSignal}>
            Signal manual fix done
          </Button>
        </div>
      )}
    </ResponsiveModal>
  );
}
