import { useMemo } from 'react';
import { useSseStore } from '@/stores/useSseStore';
import { TestTube2, Check, Loader2, Circle } from 'lucide-react';
import { cn } from '@/lib/utils';

interface Props {
  runId: string;
  status: string;
}

interface Step {
  key: string;
  label: string;
  startEvent: string;
  doneEvent: string;
  failEvent?: string;
}

const STEPS: Step[] = [
  { key: 'plan', label: 'Planning tests', startEvent: 'TEST_PLANNING_STARTED', doneEvent: 'TEST_PLANNING_COMPLETED' },
  { key: 'gen', label: 'Generating test classes', startEvent: 'TEST_GENERATION_STARTED', doneEvent: 'TEST_COMPILATION_STARTED' },
  { key: 'compile', label: 'Compiling tests', startEvent: 'TEST_COMPILATION_STARTED', doneEvent: 'TEST_COMPILATION_PASSED', failEvent: 'TEST_COMPILATION_FAILED' },
  { key: 'exec', label: 'Running against preview', startEvent: 'TEST_EXECUTION_STARTED', doneEvent: 'TEST_REPORT_READY', failEvent: 'TEST_EXECUTION_SKIPPED' },
];

export default function TestProgressTracker({ runId, status }: Props) {
  const entries = useSseStore((s) => s.entries);

  const eventTypes = useMemo(() => {
    const types = new Set<string>();
    for (const e of entries) {
      if (e.type) types.add(e.type);
    }
    return types;
  }, [entries]);

  const hasStarted = eventTypes.has('TEST_GEN_STARTED');
  const hasFailed = eventTypes.has('TEST_PIPELINE_FAILED');
  const hasReport = eventTypes.has('TEST_REPORT_READY');

  // Don't render anything if test gen hasn't started
  if (!hasStarted) return null;

  return (
    <div className="mx-4 mt-3 rounded-sm border border-white/[0.08] bg-white/[0.02] p-3">
      <div className="flex items-center gap-2 mb-2">
        <TestTube2 className="h-4 w-4 text-violet-400" />
        <span className="text-sm font-medium text-zinc-200">Integration Tests</span>
        <span className="px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wider rounded bg-accent-violet/15 text-accent-violet border border-accent-violet/25">Beta</span>
        {hasFailed && (
          <span className="ml-auto text-xs text-red-400">Pipeline failed</span>
        )}
        {hasReport && (
          <span className="ml-auto text-xs text-emerald-400">Complete</span>
        )}
      </div>

      <div className="space-y-1.5">
        {STEPS.map((step) => {
          const started = eventTypes.has(step.startEvent);
          const done = eventTypes.has(step.doneEvent);
          const failed = step.failEvent ? eventTypes.has(step.failEvent) : false;

          let state: 'pending' | 'active' | 'done' | 'failed' = 'pending';
          if (failed) state = 'failed';
          else if (done) state = 'done';
          else if (started) state = 'active';

          return (
            <div key={step.key} className="flex items-center gap-2 text-xs">
              {state === 'done' && <Check className="h-3.5 w-3.5 text-emerald-400" />}
              {state === 'active' && <Loader2 className="h-3.5 w-3.5 text-violet-400 animate-spin" />}
              {state === 'pending' && <Circle className="h-3.5 w-3.5 text-zinc-600" />}
              {state === 'failed' && <Circle className="h-3.5 w-3.5 text-red-400" />}
              <span className={cn(
                state === 'done' && 'text-zinc-400',
                state === 'active' && 'text-zinc-200',
                state === 'pending' && 'text-zinc-600',
                state === 'failed' && 'text-red-400',
              )}>
                {step.label}
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
