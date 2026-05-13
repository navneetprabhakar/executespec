import {useState} from 'react';
import {Link} from 'react-router-dom';
import {ExternalLink} from 'lucide-react';
import {errMsg} from '@/lib/utils';
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

  // V188 (2026-05-13) — unified dispatch: the entire execution runs as ONE V2 pipeline_run
  // (multi-repo, hierarchical-planned). Render a thin status panel that links into the full V2
  // run detail page for live worker logs + task progress + PR list, instead of the per-Solution
  // SolutionSection list (which doesn't apply — there are no Solution rows in unified mode).
  if (executionRun.pipelineRunId) {
    return (
      <div className='space-y-3'>
        <div className='rounded-sm border border-accent-blue/30 bg-accent-blue/5 px-3 py-2.5 text-sm space-y-1.5'>
          <div className='flex items-center gap-2'>
            <span className='text-[11px] uppercase tracking-wide text-accent-blue font-medium'>
              Unified V2 run
            </span>
            <span className='font-mono text-[11px] text-muted'>
              {executionRun.pipelineRunId.substring(0, 8)}
            </span>
            <Link
              to={`/runs/${executionRun.pipelineRunId}`}
              className='ml-auto inline-flex items-center gap-1 text-[11px] text-accent-blue hover:underline'
            >
              Open run detail <ExternalLink className='h-3 w-3' />
            </Link>
          </div>
          <div className='text-[11px] text-muted leading-relaxed'>
            The execution is being driven by one V2 pipeline run with the full multi-repo workspace
            and the hierarchical planner. Task progress, worker logs, reviewer issues, and the
            per-repo PRs all live on the run detail page above. This page shows the high-level
            execution status (top stepper) + abort control below.
          </div>
        </div>

        <div className='flex items-center gap-2 text-xs'>
          <button onClick={() => abort(executionId)} className='text-accent-red hover:underline'>
            Abort execution
          </button>
        </div>
      </div>
    );
  }

  // Legacy multi-Solution view — retained for in-flight executions created before V188 that
  // fanned out into per-Solution V2 runs.
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
              setResumeStatus(`Resume failed: ${e instanceof Error ? errMsg(e) : String(e)}`);
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
