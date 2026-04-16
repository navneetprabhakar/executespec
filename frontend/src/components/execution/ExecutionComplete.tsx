import { useExecutionStore } from '@/stores/useExecutionStore';

export default function ExecutionComplete() {
  const { executionRun } = useExecutionStore();
  if (!executionRun) return null;
  const prs = executionRun.solutions.filter((s) => s.prUrl);

  return (
    <div className="space-y-3">
      <h3 className="text-[13px] font-medium text-emerald-400">Execution complete</h3>
      <div className="text-sm text-secondary">
        {executionRun.solutions.length} solution(s) executed · ~{executionRun.totalActualTokens} tokens · ${executionRun.totalCostUsd?.toFixed?.(4) ?? '0.0000'}
      </div>
      {prs.length > 0 && (
        <div>
          <h4 className="text-xs uppercase tracking-wide text-muted mb-1">Pull requests</h4>
          <ul className="space-y-1 text-sm">
            {prs.map((s) => (
              <li key={s.id}>
                <a href={s.prUrl!} target="_blank" rel="noreferrer" className="text-accent-blue hover:underline">
                  S{s.sequenceNumber} · PR #{s.prNumber}
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
