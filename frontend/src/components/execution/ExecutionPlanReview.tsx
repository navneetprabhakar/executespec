import { useMemo } from 'react';
import { useExecutionStore } from '@/stores/useExecutionStore';
import DependencyGraph from './DependencyGraph';
import ConflictWarning from './ConflictWarning';
import type { ExecutionPlan } from '@/types/execution';

export default function ExecutionPlanReview({ executionId }: { executionId: string }) {
  const { executionRun, approvePlan } = useExecutionStore();
  const plan = useMemo<ExecutionPlan | null>(() => {
    if (!executionRun?.executionPlan) return null;
    try { return JSON.parse(executionRun.executionPlan); } catch { return null; }
  }, [executionRun?.executionPlan]);

  if (!plan) {
    return <div className="text-[12px] text-muted">Waiting for the planner to produce a plan…</div>;
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 text-[11px] text-muted">
        <span className="font-mono">{plan.solutions.length} solutions</span>
        <span className="font-mono">
          ~{(plan.totalEstimatedTokens / 1000).toFixed(0)}K tokens est
        </span>
      </div>

      <DependencyGraph solutions={plan.solutions} edges={plan.dependencyEdges} />
      <ConflictWarning conflicts={plan.conflicts} />

      <div className="space-y-1.5">
        {plan.solutions.map((s) => (
          <div key={s.sequenceNumber} className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-[12px]">
            <div className="flex items-baseline gap-2">
              <span className="font-mono text-[10px] text-muted">S{s.sequenceNumber}</span>
              <span className="font-medium">{s.title}</span>
              <span className="ml-auto text-[10px] text-muted">{s.repoName}</span>
            </div>
            {s.description && <div className="text-[11px] text-secondary mt-1">{s.description}</div>}
            {s.dependsOn.length > 0 && (
              <div className="text-[10px] text-muted mt-1">
                Depends on: {s.dependsOn.map((d) => `S${d}`).join(', ')}
              </div>
            )}
            {s.targetFiles.length > 0 && (
              <div className="text-[10px] text-muted mt-0.5 truncate">
                Files: {s.targetFiles.slice(0, 5).join(', ')}{s.targetFiles.length > 5 ? '…' : ''}
              </div>
            )}
          </div>
        ))}
      </div>

      <button
        onClick={() => approvePlan(executionId)}
        className="rounded-sm border border-emerald-500/40 bg-emerald-500/10 text-emerald-400 px-3 py-1.5 text-[12px]"
      >
        Approve &amp; execute
      </button>
    </div>
  );
}
