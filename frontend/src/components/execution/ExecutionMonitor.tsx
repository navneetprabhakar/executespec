import { useState } from 'react';
import { useExecutionStore } from '@/stores/useExecutionStore';
import SolutionSection from './SolutionSection';
import SolutionLogStream from './SolutionLogStream';
import FailureDecisionModal from './FailureDecisionModal';

export default function ExecutionMonitor({ executionId }: { executionId: string }) {
  const { executionRun, showLogs, toggleLogs, abort } = useExecutionStore();
  const [failureOpen, setFailureOpen] = useState(true);

  if (!executionRun) return null;
  const solutions = executionRun.solutions;
  const done = solutions.filter((s) => s.status === 'DONE' || s.status === 'SKIPPED').length;
  const pct = solutions.length === 0 ? 0 : Math.round((done / solutions.length) * 100);
  const failed = solutions.find((s) => s.status === 'FAILED');
  const paused = executionRun.status === 'PAUSED' && failed && failureOpen;

  return (
    <div className="space-y-3">
      <div>
        <div className="flex items-center justify-between text-[11px] text-muted mb-1">
          <span>Progress</span>
          <span className="font-mono tabular-nums">{done}/{solutions.length} · {pct}%</span>
        </div>
        <div className="w-full h-1.5 bg-[var(--gray-200)] rounded-full">
          <div className="h-1.5 bg-accent-blue rounded-full" style={{ width: `${pct}%` }} />
        </div>
      </div>

      <div className="flex items-center gap-2 text-[11px]">
        <button onClick={toggleLogs} className="text-muted hover:text-primary">
          {showLogs ? 'Hide' : 'Show'} log stream
        </button>
        <button onClick={() => abort(executionId)} className="text-accent-red hover:underline ml-auto">
          Abort
        </button>
      </div>

      <div className="space-y-1.5">
        {solutions.map((s) => (
          <SolutionSection key={s.id} executionId={executionId} solution={s} />
        ))}
      </div>

      {showLogs && <SolutionLogStream />}

      {paused && failed && (
        <FailureDecisionModal executionId={executionId} failed={failed} onClose={() => setFailureOpen(false)} />
      )}
    </div>
  );
}
