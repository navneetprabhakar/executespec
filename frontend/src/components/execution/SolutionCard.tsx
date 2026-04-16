import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import type { SolutionDto } from '@/types/execution';
import { cn } from '@/lib/utils';

const STATUS_COLOR: Record<string, string> = {
  DONE: 'bg-emerald-500/20 text-emerald-400 border-emerald-500/40',
  RUNNING: 'bg-accent-blue/20 text-accent-blue border-accent-blue/40',
  VALIDATING: 'bg-accent-blue/20 text-accent-blue border-accent-blue/40',
  PR_RAISED: 'bg-yellow-500/20 text-yellow-400 border-yellow-500/40',
  FAILED: 'bg-red-500/20 text-accent-red border-red-500/40',
  SKIPPED: 'bg-[var(--gray-200)] text-muted border-[var(--border-default)]',
  QUEUED: 'bg-[var(--gray-200)] text-muted border-[var(--border-default)]',
};

export default function SolutionCard({ solution }: { solution: SolutionDto }) {
  const [expanded, setExpanded] = useState(false);
  const color = STATUS_COLOR[solution.status] ?? STATUS_COLOR.QUEUED;

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
        {solution.prUrl && (
          <a
            href={solution.prUrl}
            target="_blank"
            rel="noreferrer"
            onClick={(e) => e.stopPropagation()}
            className="text-[11px] text-accent-blue hover:underline"
          >
            PR #{solution.prNumber}
          </a>
        )}
      </button>
      {expanded && (
        <div className="border-t border-[var(--border-default)] px-3 py-2 text-xs text-secondary space-y-1">
          {solution.description && <div>{solution.description}</div>}
          {solution.dependencyIds.length > 0 && (
            <div className="text-muted">Depends on {solution.dependencyIds.length} upstream solution(s)</div>
          )}
          {solution.failureReason && (
            <div className="text-accent-red">
              <span className="font-medium">Failed ({solution.failurePhase}):</span> {solution.failureReason}
            </div>
          )}
          <div className="text-muted">
            Tokens: {solution.actualTokens}
            {solution.estimatedTokens ? ` / ~${solution.estimatedTokens} est` : ''}
            {solution.complexity ? ` · ${solution.complexity}` : ''}
          </div>
        </div>
      )}
    </div>
  );
}
