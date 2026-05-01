import {useMemo} from 'react';

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
  status: 'done' | 'active' | 'pending' | 'warning' | 'failed';
  startedAt?: Date;
  completedAt?: Date;
  detail?: string;
}

export function RunTimeline({events, status, taskCount, completedTaskCount}: RunTimelineProps) {
  const phases = useMemo<Phase[]>(() => {
    const findEvent = (type: string) => events.find((e) => e.type === type);
    const findEventTime = (type: string) => {
      const e = findEvent(type);
      return e ? new Date(e.timestamp) : undefined;
    };

    const isTerminal = ['COMPLETE', 'FAILED', 'PARTIAL', 'CANCELLED'].includes(status);
    const currentPhase = mapStatusToPhase(status);

    // Post-COMPLETE background phases — driven by SSE events, not run status
    const previewReady = !!findEvent('PREVIEW_READY');
    const testGenStarted = !!findEvent('TEST_GEN_STARTED');
    const testReportDone = !!findEvent('TEST_REPORT_READY');
    const reportDone = !!findEvent('VALIDATION_REPORT_READY');

    return [
      {
        name: 'Planning',
        status: phaseStatus('planning', currentPhase, isTerminal),
        startedAt: findEventTime('RUN_STATUS'),
        completedAt: findEventTime('GATE_OPENED'),
      },
      {
        name: 'Approval',
        status: phaseStatus('approval', currentPhase, isTerminal),
        startedAt: findEventTime('GATE_OPENED'),
        completedAt: findEventTime('GATE_RESOLVED'),
      },
      {
        name: `Tasks (${completedTaskCount}/${taskCount})`,
        status: phaseStatus('working', currentPhase, isTerminal),
        startedAt: events.find((e) => e.type === 'TASK_STATUS' && e.payload === 'WORKING')
          ? new Date(
              events.find((e) => e.type === 'TASK_STATUS' && e.payload === 'WORKING')!.timestamp,
            )
          : undefined,
        detail: `${completedTaskCount} of ${taskCount} tasks complete`,
      },
      {
        name: 'Compilation',
        status: phaseStatus('compiling', currentPhase, isTerminal),
        startedAt: findEventTime('COMPILATION_STARTED'),
        completedAt: findEventTime('REVIEW_STARTED'),
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
      },
      // --- Background phases (run is COMPLETE while these execute) ---
      {
        name: 'Preview',
        status: previewReady ? 'done' : isTerminal ? 'pending' : 'pending',
        startedAt: undefined,
        completedAt: findEventTime('PREVIEW_READY'),
      },
      {
        name: 'Testing',
        status: testReportDone ? 'done' : testGenStarted ? 'active' : 'pending',
        startedAt: findEventTime('TEST_GEN_STARTED'),
        completedAt: findEventTime('TEST_REPORT_READY'),
      },
      {
        name: 'Report',
        status: reportDone ? 'done' : isTerminal && !reportDone ? 'active' : 'pending',
        startedAt: findEventTime('REVIEW_COMPLETE'),
        completedAt: findEventTime('VALIDATION_REPORT_READY'),
      },
    ];
  }, [events, status, taskCount, completedTaskCount]);

  // v2.5.13 mobile fix — when terminal status is FAILED/PARTIAL, mark the
  // phase that failed (the one that was active when the run terminated) so the
  // stepper doesn't paint everything as a green check while the badge says
  // PARTIAL/FAILED. mapStatusToPhase returns 'done' for those statuses, so we
  // re-derive the actual failure phase from events here.
  const terminalPhases = useMemo<Phase[]>(() => {
    const isFailed = status === 'FAILED';
    const isPartial = status === 'PARTIAL';
    if (!isFailed && !isPartial) return phases;
    // Find the last 'done' index — that's where the run actually stopped progressing.
    const lastDoneIdx = phases.reduce((acc, p, i) => (p.status === 'done' ? i : acc), -1);
    return phases.map((p, i) => {
      if (i === lastDoneIdx && (isFailed || isPartial)) {
        return {...p, status: isFailed ? 'failed' : 'warning'};
      }
      return p;
    });
  }, [phases, status]);

  return (
    <div className='flex items-center gap-1 w-full overflow-x-auto py-4 px-1 scroll-smooth snap-x'>
      {terminalPhases.map((phase, i) => {
        const isSeparator = i === 6; // divider before background phases
        const nodeBg =
          phase.status === 'done'
            ? 'bg-[var(--text-primary)] text-[var(--geist-bg)]'
            : phase.status === 'active'
              ? 'bg-accent-blue text-primary animate-pulse'
              : phase.status === 'warning'
                ? 'bg-accent-amber text-primary'
                : phase.status === 'failed'
                  ? 'bg-accent-red text-primary'
                  : 'bg-[var(--gray-400)] text-secondary opacity-50';
        const labelColor =
          phase.status === 'active'
            ? 'text-accent-blue font-semibold'
            : phase.status === 'done'
              ? 'text-secondary'
              : phase.status === 'warning'
                ? 'text-accent-amber font-semibold'
                : phase.status === 'failed'
                  ? 'text-accent-red font-semibold'
                  : 'text-muted opacity-50';
        const nodeIcon =
          phase.status === 'done'
            ? '✓'
            : phase.status === 'failed'
              ? '×'
              : phase.status === 'warning'
                ? '!'
                : i + 1;
        return (
          <div key={phase.name} className='flex items-center flex-shrink-0 snap-start'>
            {/* Dashed divider before background phases */}
            {isSeparator && (
              <div className='w-6 flex items-center justify-center mx-1'>
                <div className='w-full border-t border-dashed border-[var(--gray-400)]' />
              </div>
            )}

            {/* Phase node */}
            <div className='flex flex-col items-center'>
              <div
                className={`w-7 h-7 rounded-sm flex items-center justify-center text-xs font-bold ${nodeBg}`}
              >
                {nodeIcon}
              </div>
              <span className={`mt-1 text-[10px] sm:text-xs whitespace-nowrap ${labelColor}`}>
                {phase.name}
              </span>
              {phase.startedAt && phase.completedAt && (
                <span className='text-[10px] text-muted'>
                  {formatDuration(phase.completedAt.getTime() - phase.startedAt.getTime())}
                </span>
              )}
            </div>

            {/* Connector line (skip after last phase and before the dashed separator) */}
            {i < terminalPhases.length - 1 && i !== 5 && (
              <div
                className={`w-6 sm:w-8 h-0.5 mx-1 ${
                  phase.status === 'done'
                    ? 'bg-[var(--text-primary)]'
                    : 'bg-[var(--gray-400)] opacity-50'
                }`}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

function mapStatusToPhase(status: string): string {
  switch (status) {
    case 'PLANNING':
      return 'planning';
    case 'AWAITING_APPROVAL':
    case 'AWAITING_CLARIFICATION':
      return 'approval';
    case 'WORKING':
      return 'working';
    case 'COMPILING':
      return 'compiling';
    case 'REVIEWING':
      return 'reviewing';
    case 'MERGING':
    case 'AWAITING_MERGE':
      return 'merging';
    case 'COMPLETE':
    case 'FAILED':
    case 'PARTIAL':
      return 'done';
    default:
      return 'planning';
  }
}

function phaseStatus(
  phaseName: string,
  currentPhase: string,
  isTerminal: boolean,
): 'done' | 'active' | 'pending' {
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
