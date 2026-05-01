import {useState} from 'react';
import {useExecutionStore} from '@/stores/useExecutionStore';
import {executionApi} from '@/lib/execution-api';
import SolutionSection from './SolutionSection';
import SolutionLogStream from './SolutionLogStream';
import FailureDecisionModal from './FailureDecisionModal';

export default function ExecutionMonitor({executionId}: {executionId: string}) {
  const {executionRun, showLogs, toggleLogs, abort} = useExecutionStore();
  const [failureOpen, setFailureOpen] = useState(true);
  const [resuming, setResuming] = useState(false);
  const [resumeStatus, setResumeStatus] = useState<string | null>(null);

  if (!executionRun) return null;
  const solutions = executionRun.solutions;
  const done = solutions.filter((s) => s.status === 'DONE' || s.status === 'SKIPPED').length;
  const pct = solutions.length === 0 ? 0 : Math.round((done / solutions.length) * 100);
  const failed = solutions.find((s) => s.status === 'FAILED');
  const paused = executionRun.status === 'PAUSED' && failed && failureOpen;

  return (
    <div className='space-y-3'>
      <div>
        <div className='flex items-center justify-between text-xs text-muted mb-1'>
          <span>Progress</span>
          <span className='font-mono tabular-nums'>
            {done}/{solutions.length} · {pct}%
          </span>
        </div>
        <div className='w-full h-1.5 bg-[var(--gray-200)] rounded-full'>
          <div className='h-1.5 bg-accent-blue rounded-full' style={{width: `${pct}%`}} />
        </div>
      </div>

      <div className='flex items-center gap-2 text-xs'>
        <button onClick={toggleLogs} className='text-muted hover:text-primary'>
          {showLogs ? 'Hide' : 'Show'} log stream
        </button>
        <button
          onClick={async () => {
            setResuming(true);
            setResumeStatus(null);
            try {
              await executionApi.resume(executionId);
              setResumeStatus('Re-kicked execution');
            } catch (e) {
              setResumeStatus(`Resume failed: ${e instanceof Error ? e.message : String(e)}`);
            } finally {
              setResuming(false);
            }
          }}
          disabled={resuming}
          className='text-accent-blue hover:underline ml-auto disabled:opacity-50'
          title='Re-trigger execution from the Plan step without redoing Setup/Analysis'
        >
          {resuming ? 'Resuming…' : 'Resume'}
        </button>
        <button onClick={() => abort(executionId)} className='text-accent-red hover:underline'>
          Abort
        </button>
      </div>
      {resumeStatus && <div className='text-[11px] text-muted'>{resumeStatus}</div>}

      <div className='space-y-1.5'>
        {solutions.map((s) => (
          <SolutionSection key={s.id} executionId={executionId} solution={s} />
        ))}
      </div>

      {showLogs && <SolutionLogStream />}

      {paused && failed && (
        <FailureDecisionModal
          executionId={executionId}
          failed={failed}
          onClose={() => setFailureOpen(false)}
        />
      )}
    </div>
  );
}
