import {useState} from 'react';
import {ChevronDown, ChevronRight, ListTodo} from 'lucide-react';
import type {TaskSummary} from '@/types/project';
import {cn} from '@/lib/utils';
import {getRun, type RunDetail} from '@/lib/api';
import InlineRunDetail from './InlineRunDetail';
import StatusBadge from '@/components/shared/StatusBadge';
import EmptyState from '@/components/shared/EmptyState';

function scoreColor(score: number | null | undefined): string {
  // v2.5.12 — token-aligned with ValidationReport / SignalBreakdown.
  if (score == null) return 'text-muted';
  if (score >= 90) return 'text-status-success';
  if (score >= 75) return 'text-status-waiting';
  return 'text-status-error';
}

export default function ProjectTaskList({tasks}: {tasks: TaskSummary[]}) {
  const [expandedRunId, setExpandedRunId] = useState<string | null>(null);
  const [runCache, setRunCache] = useState<Record<string, RunDetail | 'loading' | 'error'>>({});

  const loadRun = async (runId: string) => {
    setRunCache((c) => ({...c, [runId]: 'loading'}));
    try {
      const run = await getRun(runId);
      setRunCache((c) => ({...c, [runId]: run}));
    } catch {
      setRunCache((c) => ({...c, [runId]: 'error'}));
    }
  };

  const toggle = async (runId: string) => {
    if (expandedRunId === runId) {
      setExpandedRunId(null);
      return;
    }
    setExpandedRunId(runId);
    if (!runCache[runId]) await loadRun(runId);
  };

  if (!tasks || tasks.length === 0) {
    return (
      <EmptyState
        compact
        icon={ListTodo}
        title='No runs yet'
        description='Submit a Launch from this project to see runs here.'
      />
    );
  }
  return (
    <div className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden'>
      <table className='w-full text-xs'>
        <thead className='bg-[var(--gray-200)]'>
          <tr className='text-[11px] font-medium text-muted'>
            <th className='w-6' />
            <th className='text-left px-2.5 py-1.5'>Run</th>
            <th className='text-left px-2.5 py-1.5'>Status</th>
            <th className='text-right px-2.5 py-1.5'>Score</th>
            <th className='text-right px-2.5 py-1.5'>Started</th>
          </tr>
        </thead>
        <tbody>
          {tasks.map((t) => {
            const expanded = expandedRunId === t.runId;
            const detail = runCache[t.runId];
            return (
              <>
                <tr
                  key={t.taskId}
                  onClick={() => toggle(t.runId)}
                  className='border-t border-[var(--border-default)] hover:bg-[var(--gray-200)] transition-colors cursor-pointer'
                >
                  <td className='px-1.5 py-1.5 text-muted'>
                    {expanded ? (
                      <ChevronDown className='h-3 w-3' />
                    ) : (
                      <ChevronRight className='h-3 w-3' />
                    )}
                  </td>
                  <td className='px-2.5 py-1.5 truncate max-w-md text-accent-blue'>
                    {t.name || t.runId.slice(0, 8)}
                  </td>
                  <td className='px-2.5 py-1.5'>
                    <StatusBadge status={t.status} />
                  </td>
                  <td
                    className={cn(
                      'px-2.5 py-1.5 text-right font-mono tabular-nums',
                      scoreColor(t.score),
                    )}
                  >
                    {t.score != null ? `${t.score}` : '—'}
                  </td>
                  <td className='px-2.5 py-1.5 text-right text-muted font-mono'>
                    {t.createdAt ? new Date(t.createdAt).toLocaleString() : '—'}
                  </td>
                </tr>
                {expanded && (
                  <tr className='border-t border-[var(--border-default)] bg-[var(--gray-200)]'>
                    <td colSpan={5} className='px-3 py-3'>
                      {detail === 'loading' && (
                        <span className='text-xs text-muted'>Loading run…</span>
                      )}
                      {detail === 'error' && (
                        <span className='text-xs text-accent-red'>Failed to load run.</span>
                      )}
                      {detail && detail !== 'loading' && detail !== 'error' && (
                        <InlineRunDetail run={detail} onRefresh={() => loadRun(t.runId)} />
                      )}
                    </td>
                  </tr>
                )}
              </>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
