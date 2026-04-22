import { useExecutionStore } from '@/stores/useExecutionStore';

/**
 * v2.5.2 (Phase 2.3 / UX-5.1) — live cancel cleanup feedback.
 *
 * Renders compensation steps emitted by ExecutionOrchestrator.runCompensations
 * via EXEC_COMPENSATION_UPDATE / EXEC_COMPENSATION_DONE. Empty render until
 * the first event arrives so it doesn't flash on regular ABORTED views from
 * older runs.
 */
export default function CancellationPanel() {
  const { compensations, compensationDoneSummary } = useExecutionStore();
  if (compensations.length === 0 && !compensationDoneSummary) return null;

  const inProgress = compensationDoneSummary === null;

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] p-3 space-y-2">
      <div className="flex items-center gap-2">
        <h3 className="text-sm font-medium text-primary">
          {inProgress ? 'Cleaning up…' : 'Cleanup complete'}
        </h3>
        {compensationDoneSummary && (
          <span className="text-[11px] text-muted">
            {compensationDoneSummary.completed} of {compensationDoneSummary.total} succeeded
            {compensationDoneSummary.failed > 0 ? `, ${compensationDoneSummary.failed} failed` : ''}
          </span>
        )}
      </div>

      <div className="space-y-1">
        {compensations.map((s, i) => {
          const icon = s.status === 'COMPLETE' ? '✓'
                     : s.status === 'FAILED' ? '✗'
                     : s.status === 'RUNNING' ? '◉'
                     : '○';
          const cls = s.status === 'COMPLETE' ? 'text-status-success'
                    : s.status === 'FAILED' ? 'text-accent-red'
                    : s.status === 'RUNNING' ? 'text-accent-blue'
                    : 'text-muted';
          const label = s.type === 'PR_CLOSE'
              ? `Closing PR #${s.prNumber} on ${s.repo}`
              : `${s.type} on ${s.repo}`;
          return (
            <div key={`${s.type}-${s.repo}-${s.prNumber}-${i}`}
                 className="flex items-center gap-2 text-xs">
              <span className={`font-mono w-3 ${cls}`}>{icon}</span>
              <span className="flex-1 text-secondary truncate">{label}</span>
              {s.status === 'FAILED' && s.prNumber && (
                <a
                  href={`https://github.com/${s.repo}/pull/${s.prNumber}`}
                  target="_blank" rel="noopener noreferrer"
                  className="text-[11px] text-accent-blue hover:underline shrink-0"
                >
                  Close on GitHub ↗
                </a>
              )}
            </div>
          );
        })}
      </div>

      {compensations.some((s) => s.status === 'FAILED') && (
        <div className="text-[11px] text-accent-amber pt-1 border-t border-[var(--border-default)]">
          Some compensations failed — open them on GitHub to clean up manually.
        </div>
      )}
    </div>
  );
}
