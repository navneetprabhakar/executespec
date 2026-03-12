import { useMemo } from 'react';

interface TimelineEvent {
  type: string;
  taskId?: string;
  payload?: string;
  timestamp: string;
}

interface RunTimelineProps {
  events: TimelineEvent[];
  status: string;
  taskCount: number;
  completedTaskCount: number;
}

interface Phase {
  name: string;
  status: 'done' | 'active' | 'pending';
  startedAt?: Date;
  completedAt?: Date;
  detail?: string;
}

export function RunTimeline({ events, status, taskCount, completedTaskCount }: RunTimelineProps) {

  const phases = useMemo<Phase[]>(() => {
    const findEvent = (type: string) => events.find(e => e.type === type);
    const findEventTime = (type: string) => {
      const e = findEvent(type);
      return e ? new Date(e.timestamp) : undefined;
    };

    const isTerminal = ['COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED'].includes(status);
    const currentPhase = mapStatusToPhase(status);

    return [
      {
        name: 'Planning',
        status: phaseStatus('planning', currentPhase, isTerminal),
        startedAt: findEventTime('RUN_STATUS'),
        completedAt: findEventTime('GATE_OPENED'),
        detail: undefined,
      },
      {
        name: 'Approval',
        status: phaseStatus('approval', currentPhase, isTerminal),
        startedAt: findEventTime('GATE_OPENED'),
        completedAt: findEventTime('GATE_RESOLVED'),
        detail: undefined,
      },
      {
        name: `Tasks (${completedTaskCount}/${taskCount})`,
        status: phaseStatus('working', currentPhase, isTerminal),
        startedAt: events.find(e => e.type === 'TASK_STATUS' && e.payload === 'WORKING')
          ? new Date(events.find(e => e.type === 'TASK_STATUS' && e.payload === 'WORKING')!.timestamp)
          : undefined,
        completedAt: undefined,
        detail: `${completedTaskCount} of ${taskCount} tasks complete`,
      },
      {
        name: 'Compilation',
        status: phaseStatus('compiling', currentPhase, isTerminal),
        startedAt: findEventTime('COMPILATION_STARTED'),
        completedAt: findEventTime('REVIEW_STARTED'),
        detail: undefined,
      },
      {
        name: 'Review',
        status: phaseStatus('reviewing', currentPhase, isTerminal),
        startedAt: findEventTime('REVIEW_STARTED'),
        completedAt: findEventTime('REVIEW_COMPLETE'),
        detail: findEvent('REVIEW_COMPLETE')?.payload?.split('|')[1] || undefined,
      },
      {
        name: 'Merge',
        status: phaseStatus('merging', currentPhase, isTerminal),
        startedAt: findEventTime('REVIEW_COMPLETE'),
        completedAt: findEventTime('COMPLETE'),
        detail: undefined,
      },
    ];
  }, [events, status, taskCount, completedTaskCount]);

  return (
    <div className="flex items-center gap-1 w-full overflow-x-auto py-4">
      {phases.map((phase, i) => (
        <div key={phase.name} className="flex items-center flex-shrink-0">
          {/* Phase node */}
          <div className="flex flex-col items-center">
            <div className={`w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold
              ${phase.status === 'done' ? 'bg-green-500 text-white' :
                phase.status === 'active' ? 'bg-indigo-500 text-white animate-pulse' :
                'bg-zinc-700 text-zinc-400'}`}>
              {phase.status === 'done' ? '✓' : i + 1}
            </div>
            <span className={`mt-1 text-xs whitespace-nowrap
              ${phase.status === 'active' ? 'text-indigo-400 font-semibold' :
                phase.status === 'done' ? 'text-zinc-300' : 'text-zinc-500'}`}>
              {phase.name}
            </span>
            {phase.startedAt && phase.completedAt && (
              <span className="text-[10px] text-zinc-500">
                {formatDuration(phase.completedAt.getTime() - phase.startedAt.getTime())}
              </span>
            )}
          </div>

          {/* Connector line */}
          {i < phases.length - 1 && (
            <div className={`w-8 h-0.5 mx-1 ${
              phase.status === 'done' ? 'bg-green-400' : 'bg-zinc-700'
            }`} />
          )}
        </div>
      ))}
    </div>
  );
}

function mapStatusToPhase(status: string): string {
  switch (status) {
    case 'PLANNING': return 'planning';
    case 'AWAITING_APPROVAL': case 'AWAITING_CLARIFICATION': return 'approval';
    case 'WORKING': return 'working';
    case 'REVIEWING': return 'reviewing';
    case 'AWAITING_MERGE': return 'merging';
    case 'COMPLETE': case 'FAILED': case 'PARTIAL': return 'done';
    default: return 'planning';
  }
}

function phaseStatus(phaseName: string, currentPhase: string, isTerminal: boolean): 'done' | 'active' | 'pending' {
  const order = ['planning', 'approval', 'working', 'compiling', 'reviewing', 'merging', 'done'];
  const currentIdx = order.indexOf(currentPhase);
  const phaseIdx = order.indexOf(phaseName);

  if (isTerminal) return phaseIdx <= currentIdx ? 'done' : 'pending';
  if (phaseIdx < currentIdx) return 'done';
  if (phaseIdx === currentIdx) return 'active';
  return 'pending';
}

function formatDuration(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  const remaining = seconds % 60;
  return `${minutes}m ${remaining}s`;
}
