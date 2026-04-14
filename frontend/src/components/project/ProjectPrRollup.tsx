import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ExternalLink } from 'lucide-react';
import { executionApi } from '@/lib/execution-api';
import type { PrRollupEntry } from '@/types/execution';

export default function ProjectPrRollup({ projectId }: { projectId: string }) {
  const [prs, setPrs] = useState<PrRollupEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    executionApi.listProjectPrs(projectId)
        .then(setPrs)
        .catch((e: any) => setError(e?.message ?? 'Failed to load PRs'));
  }, [projectId]);

  if (error) return <div className="text-[11px] text-accent-red">{error}</div>;
  if (prs == null) return <div className="text-[11px] text-muted">Loading PRs…</div>;
  if (prs.length === 0) {
    return <div className="text-[12px] text-muted">No pull requests yet across any launch.</div>;
  }

  // Group by launchId so the reviewer can scan progress per launch.
  const byLaunch: Record<string, PrRollupEntry[]> = {};
  prs.forEach((p) => {
    (byLaunch[p.launchId] ??= []).push(p);
  });

  return (
    <div className="space-y-3">
      {Object.entries(byLaunch).map(([launchId, group]) => (
        <div key={launchId}>
          <div className="flex items-center gap-2 mb-1 text-[11px] text-muted">
            <span>Launch</span>
            <Link to={`/projects/${projectId}/executions/${launchId}`}
              className="font-mono text-accent-blue hover:underline">
              {launchId.substring(0, 8)}
            </Link>
            <span>· {group.length} PR{group.length > 1 ? 's' : ''}</span>
          </div>
          <div className="space-y-1">
            {group.map((pr) => (
              <a key={pr.prUrl} href={pr.prUrl} target="_blank" rel="noreferrer"
                className="flex items-center gap-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-1.5 text-[11px] hover:bg-[var(--gray-200)]">
                <span className={pr.status === 'MERGED'
                    ? 'text-[10px] px-1.5 py-0.5 rounded border text-emerald-400 border-emerald-500/40'
                    : 'text-[10px] px-1.5 py-0.5 rounded border text-accent-blue border-accent-blue/40'}>
                  {pr.status}
                </span>
                <span className="font-mono">#{pr.prNumber ?? '—'}</span>
                <span className="truncate flex-1">{pr.repoFullName ?? pr.prUrl}</span>
                <ExternalLink className="h-3 w-3 text-muted" />
              </a>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
