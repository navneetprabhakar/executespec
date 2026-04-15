import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { executionApi } from '@/lib/execution-api';
import type { ExecutionRunDto } from '@/types/execution';

const STATUS_COLOR: Record<string, string> = {
  COMPLETE: 'text-emerald-400 border-emerald-500/40',
  EXECUTING: 'text-accent-blue border-accent-blue/40',
  PAUSED: 'text-yellow-400 border-yellow-500/40',
  FAILED: 'text-accent-red border-red-500/40',
  ABORTED: 'text-accent-red border-red-500/40',
};

export default function ProjectLaunchesList({ projectId }: { projectId: string }) {
  const [launches, setLaunches] = useState<ExecutionRunDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    executionApi.listProjectLaunches(projectId)
        .then((page) => setLaunches(page.content))
        .catch((e: any) => setError(e?.message ?? 'Failed to load launches'));
  }, [projectId]);

  if (error) return <div className="text-[11px] text-accent-red">{error}</div>;
  if (launches == null) return <div className="text-[11px] text-muted">Loading launches…</div>;
  if (launches.length === 0) {
    return <div className="text-[11px] text-muted">No launches yet. Click "Launch" to start a cross-repo run.</div>;
  }

  return (
    <div className="space-y-1.5">
      {launches.map((l) => {
        const color = STATUS_COLOR[l.status] ?? 'text-muted border-[var(--border-default)]';
        const doneCount = l.solutions.filter(s => s.status === 'DONE').length;
        return (
          <Link
            key={l.id}
            to={`/projects/${projectId}/executions/${l.id}`}
            className="flex items-center gap-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-[11px] hover:bg-[var(--gray-200)]"
          >
            <span className={`text-[10px] px-1.5 py-0.5 rounded border ${color}`}>{l.status}</span>
            <span className="font-mono text-[10px] text-muted">{l.id.substring(0, 8)}</span>
            <span className="truncate flex-1">{l.requirement.split('\n')[0]}</span>
            <span className="text-[10px] text-muted">
              {doneCount}/{l.solutions.length} solutions · {l.totalActualTokens ?? 0} tok
            </span>
            {l.jiraEnabled && (
              <span className="text-[10px] text-accent-blue">JIRA</span>
            )}
          </Link>
        );
      })}
    </div>
  );
}
