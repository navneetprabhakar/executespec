import type { PrSummary } from '@/types/project';
import { ExternalLink, GitPullRequest } from 'lucide-react';
import EmptyState from '@/components/shared/EmptyState';

// v2.5.12 — token-aligned (was raw emerald-/red- Tailwind hex).
const STATUS_COLOR: Record<string, string> = {
  OPEN:   'text-accent-blue border-accent-blue/40 bg-accent-blue/10',
  MERGED: 'text-status-success border-status-success/40 bg-status-success/10',
  CLOSED: 'text-status-error border-status-error/40 bg-status-error/10',
};

export default function ProjectPrList({ prs }: { prs: PrSummary[] }) {
  if (!prs || prs.length === 0) {
    return (
      <EmptyState
        compact
        icon={GitPullRequest}
        title="No pull requests yet"
        description="PRs will appear here once a Launch finishes."
      />
    );
  }
  const merged = prs.filter((p) => p.status === 'MERGED').length;
  const total = prs.length;
  return (
    <div className="space-y-1.5">
      <div className="text-[11px] text-muted font-mono tabular-nums">
        {merged}/{total} merged · {Math.round((merged / total) * 100)}%
      </div>
      <ul className="space-y-1">
        {prs.map((pr) => (
          <li key={`${pr.repoFullName}-${pr.prNumber}`}
            className="flex items-center gap-2 text-xs rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] hover:bg-[var(--gray-200)] px-2 py-1 transition-colors">
            <span className={`text-[9px] uppercase tracking-wide px-1.5 py-0.5 rounded border ${STATUS_COLOR[pr.status] ?? 'text-muted border-[var(--border-default)]'}`}>
              {pr.status}
            </span>
            <a
              href={pr.prUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="font-mono text-accent-blue hover:underline truncate flex items-center gap-1"
            >
              {pr.repoFullName}#{pr.prNumber}
              <ExternalLink className="h-2.5 w-2.5 flex-shrink-0" />
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
