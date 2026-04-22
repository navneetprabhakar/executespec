import { useEffect, useState } from 'react';
import { ChevronDown, ChevronRight, ExternalLink } from 'lucide-react';
import { Link } from 'react-router-dom';
import type { SolutionDto, SolutionRun } from '@/types/execution';
import { executionApi } from '@/lib/execution-api';
import { cn } from '@/lib/utils';

const STATUS_COLOR: Record<string, string> = {
  DONE: 'bg-status-success/20 text-status-success border-status-success/40',
  RUNNING: 'bg-accent-blue/20 text-accent-blue border-accent-blue/40',
  VALIDATING: 'bg-accent-blue/20 text-accent-blue border-accent-blue/40',
  PR_RAISED: 'bg-status-waiting/20 text-status-waiting border-status-waiting/40',
  FAILED: 'bg-status-error/20 text-accent-red border-status-error/40',
  SKIPPED: 'bg-[var(--gray-200)] text-muted border-[var(--border-default)]',
  QUEUED: 'bg-[var(--gray-200)] text-muted border-[var(--border-default)]',
};

interface Props {
  executionId: string;
  solution: SolutionDto;
}

/**
 * Expandable solution section. Solution groups 1..N V2 pipeline runs (initial
 * + retries + manual fixes). The header shows rolled-up status / tokens / PR /
 * JIRA; expanded body lists each V2 run with a link into the full RunDetail
 * page. No duplicate run UI.
 */
export default function SolutionSection({ executionId, solution }: Props) {
  const [expanded, setExpanded] = useState(false);
  const [runs, setRuns] = useState<SolutionRun[] | null>(null);
  const color = STATUS_COLOR[solution.status] ?? STATUS_COLOR.QUEUED;
  const totalTokens = runs == null
      ? solution.actualTokens
      : runs.reduce((acc, r) => acc + (r.inputTokens ?? 0) + (r.outputTokens ?? 0), 0);

  useEffect(() => {
    if (!expanded || runs !== null) return;
    executionApi.listSolutionRuns(executionId, solution.id)
        .then(setRuns)
        .catch(() => setRuns([]));
  }, [expanded, executionId, solution.id, runs]);

  return (
    <div className="rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)]">
      <button
        onClick={() => setExpanded((v) => !v)}
        className="w-full flex items-center gap-2 px-3 py-2 text-left text-sm"
      >
        {expanded ? <ChevronDown className="h-3 w-3" /> : <ChevronRight className="h-3 w-3" />}
        <span className={cn('text-[11px] px-1.5 py-0.5 rounded border', color)}>{solution.status}</span>
        <span className="font-mono text-[11px] text-muted">S{solution.sequenceNumber}</span>
        <span className="truncate flex-1">{solution.title}</span>
        <span className="text-[11px] font-mono text-muted">~{totalTokens ?? 0} tok</span>
        {runs && runs.length > 0 && (
          <span className="text-[11px] text-muted">{runs.length} run{runs.length > 1 ? 's' : ''}</span>
        )}
        {solution.prUrl && (
          <a href={solution.prUrl} target="_blank" rel="noreferrer"
             onClick={(e) => e.stopPropagation()}
             className="text-[11px] text-accent-blue hover:underline flex items-center gap-0.5">
            PR #{solution.prNumber}<ExternalLink className="h-2.5 w-2.5" />
          </a>
        )}
        {solution.jiraTicketId && (
          <span className="text-[11px] text-muted font-mono">{solution.jiraTicketId}</span>
        )}
      </button>

      {expanded && (
        <div className="border-t border-[var(--border-default)] px-3 py-2 space-y-2 text-xs">
          {solution.description && <div className="text-secondary">{solution.description}</div>}
          {solution.failureReason && (
            <div className="text-accent-red">
              <span className="font-medium">Failed ({solution.failurePhase}):</span>{' '}
              {solution.failureReason}
            </div>
          )}
          {solution.dependencyIds.length > 0 && (
            <div className="text-muted">Depends on {solution.dependencyIds.length} upstream solution(s)</div>
          )}

          <div>
            <div className="text-[11px] uppercase tracking-wide text-muted mb-1">Runs</div>
            {runs == null ? (
              <div className="text-muted">Loading runs…</div>
            ) : runs.length === 0 ? (
              <div className="text-muted">No runs yet.</div>
            ) : (
              <div className="space-y-1">
                {runs.map((r) => (
                  <div key={r.id}
                    className="flex items-center gap-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-2 py-1">
                    <span className="font-mono text-muted">#{r.attemptNumber}</span>
                    <span className={cn('text-[11px] px-1.5 py-0.5 rounded border',
                        r.terminalStatus === 'COMPLETE' ? 'text-status-success border-status-success/40'
                        : r.terminalStatus === 'PARTIAL' ? 'text-status-waiting border-status-waiting/40'
                        : r.terminalStatus ? 'text-accent-red border-status-error/40'
                        : 'text-muted border-[var(--border-default)]')}>
                      {r.terminalStatus ?? 'RUNNING'}
                    </span>
                    <span className="text-muted">
                      {(r.inputTokens ?? 0) + (r.outputTokens ?? 0)} tok
                    </span>
                    <Link
                      to={`/runs/${r.pipelineRunId}`}
                      className="ml-auto text-accent-blue hover:underline flex items-center gap-0.5"
                    >
                      Open run <ExternalLink className="h-2.5 w-2.5" />
                    </Link>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
