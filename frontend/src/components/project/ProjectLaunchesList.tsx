import {errMsg} from '@/lib/utils';
import {useEffect, useState} from 'react';
import {Link} from 'react-router-dom';
import {Rocket} from 'lucide-react';
import {executionApi} from '@/lib/execution-api';
import type {ExecutionRunDto} from '@/types/execution';
import StatusBadge from '@/components/shared/StatusBadge';
import EmptyState from '@/components/shared/EmptyState';

export default function ProjectLaunchesList({projectId}: {projectId: string}) {
  const [launches, setLaunches] = useState<ExecutionRunDto[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    executionApi
      .listProjectLaunches(projectId)
      .then((page) => setLaunches(page.content))
      .catch((e: unknown) => setError(errMsg(e) ?? 'Failed to load launches'));
  }, [projectId]);

  if (error) return <div className='text-xs text-status-error'>{error}</div>;
  if (launches == null) return <div className='text-xs text-muted'>Loading launches…</div>;
  if (launches.length === 0) {
    return (
      <EmptyState
        compact
        icon={Rocket}
        title='No launches yet'
        description='Click Launch above to start a cross-repo run.'
      />
    );
  }

  return (
    <div className='space-y-1.5'>
      {launches.map((l) => {
        const doneCount = l.solutions.filter((s) => s.status === 'DONE').length;
        return (
          <Link
            key={l.id}
            to={`/projects/${projectId}/executions/${l.id}`}
            className='flex items-center gap-2 rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-xs hover:bg-[var(--gray-200)]'
          >
            <StatusBadge status={l.status} className='text-[11px]' />
            <span className='font-mono text-[11px] text-muted'>{l.id.substring(0, 8)}</span>
            <span className='truncate flex-1'>{l.requirement.split('\n')[0]}</span>
            <span className='text-[11px] text-muted'>
              repos × {l.solutions.length} · {doneCount}/{l.solutions.length} done ·{' '}
              {l.totalActualTokens ?? 0} tok
            </span>
            {l.jiraEnabled && <span className='text-[11px] text-accent-blue'>JIRA</span>}
          </Link>
        );
      })}
    </div>
  );
}
