/**
 * RunTimeline — one vertical, status-driven step timeline shared by the plan gate
 * (IdePlan) and the run-result Timeline tab (RL-2026-05-052, feedback #6: "combine
 * Plan and timeline in one go and show status via the timeline itself"). At the gate
 * every step is Pending; during/after a run the same steps light up
 * running/done/failed. Pure presentational — takes any task-like list.
 */
type TimelineTask = {taskId?: string | null; title?: string | null; status?: string | null};

function nodeState(status?: string | null): 'done' | 'failed' | 'running' | 'pending' {
  const s = (status || '').toUpperCase();
  if (['COMPLETE', 'COMPLETED', 'DONE', 'PASSED'].includes(s)) return 'done';
  if (['FAILED', 'ERROR'].includes(s)) return 'failed';
  if (['RUNNING', 'EXECUTING', 'IN_PROGRESS', 'ACTIVE', 'REVIEWING', 'BUILDING'].includes(s))
    return 'running';
  return 'pending';
}

const STATE_LABEL: Record<string, string> = {
  done: 'Done',
  failed: 'Failed',
  running: 'Running…',
  pending: 'Pending',
};

export function RunTimeline({tasks}: {tasks: TimelineTask[]}) {
  if (!tasks?.length) return <div className='ide-empty'>No steps yet.</div>;
  return (
    <ol className='ide-timeline'>
      {tasks.map((t, i) => {
        const state = nodeState(t.status);
        return (
          <li className='ide-timeline-node' key={t.taskId || i}>
            <span className={`ide-timeline-dot ${state}`} aria-hidden='true' />
            <div className='ide-timeline-body'>
              <span className='ide-timeline-title'>{t.title || `Step ${i + 1}`}</span>
              <span className={`ide-timeline-state ${state}`}>{STATE_LABEL[state]}</span>
            </div>
          </li>
        );
      })}
    </ol>
  );
}
