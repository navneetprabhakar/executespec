import { useExecutionStore } from '@/stores/useExecutionStore';

export default function ExecutionAnalysis() {
  const { analysisProgress } = useExecutionStore();
  const rows = analysisProgress.length === 0
    ? [{ repoId: '-', repoName: 'Regenerating architecture doc…', codemapStatus: 'RUNNING' as const, archDocStatus: 'RUNNING' as const }]
    : analysisProgress;

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-medium text-primary">Analysing repos</h3>
      <div className="space-y-1.5">
        {rows.map((r) => (
          <div key={r.repoId} className="flex items-center justify-between text-xs border border-[var(--border-default)] rounded-sm px-2.5 py-1.5">
            <span className="font-mono">{r.repoName}</span>
            <span className="flex items-center gap-2">
              <StatusChip label="codemap" status={r.codemapStatus} />
              <StatusChip label="arch" status={r.archDocStatus} />
            </span>
          </div>
        ))}
      </div>
      <p className="text-xs text-muted">The unified architecture doc appears once all repos complete.</p>
    </div>
  );
}

function StatusChip({ label, status }: { label: string; status: string }) {
  const color =
    status === 'DONE' ? 'bg-status-success/20 text-status-success'
    : status === 'RUNNING' ? 'bg-accent-blue/20 text-accent-blue'
    : 'bg-[var(--gray-200)] text-muted';
  return (
    <span className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[11px] ${color}`}>
      {label}: {status.toLowerCase()}
    </span>
  );
}
