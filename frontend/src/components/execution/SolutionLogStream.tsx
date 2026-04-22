import { useEffect, useRef } from 'react';
import { useExecutionStore } from '@/stores/useExecutionStore';

export default function SolutionLogStream() {
  const { executionRun, solutionLogs } = useExecutionStore();
  const activeSolution = executionRun?.solutions.find((s) => s.status === 'RUNNING');
  const scrollRef = useRef<HTMLDivElement>(null);
  const lines = activeSolution ? solutionLogs[activeSolution.id] ?? [] : [];

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [lines.length]);

  if (!activeSolution) return null;

  return (
    <div className="mt-3 rounded-sm border border-[var(--border-default)] bg-gray-950 font-mono text-xs text-status-success">
      <div className="px-2 py-1 border-b border-[var(--border-default)] text-[11px] text-muted">
        S{activeSolution.sequenceNumber} · {activeSolution.title}
      </div>
      <div ref={scrollRef} className="max-h-60 overflow-y-auto px-2 py-1 whitespace-pre-wrap">
        {lines.length === 0 ? <span className="text-muted">Waiting for first log line…</span> : lines.join('\n')}
      </div>
    </div>
  );
}
