import {useMemo} from 'react';
import {useExecutionStore} from '@/stores/useExecutionStore';
import DependencyGraph from './DependencyGraph';
import ConflictWarning from './ConflictWarning';
import type {ExecutionPlan} from '@/types/execution';

/**
 * V188 (2026-05-13) — unified V2 plan shape. The planner emits this when the execution is
 * dispatched via the unified path (one V2 run for the whole execution); the V3 wrapper mirrors
 * V2's planJson directly onto executionRun.executionPlan. Keep optional fields tolerant — older
 * V2 plans may not have requirements/classification.
 */
interface V2PlannedTask {
  id?: string;
  title: string;
  description?: string;
  complexity?: string;
  touchesFiles?: string[];
  hardConstraints?: string[];
  dependsOn?: string[];
  taskType?: 'LEAF' | 'EPIC';
  meetsRequirements?: string[];
}
interface V2Plan {
  planTitle?: string;
  planSummary?: string;
  projectType?: string;
  buildCommand?: string;
  tasks: V2PlannedTask[];
  classification?: {strategy?: string};
}

type ParsedPlan =
  | {kind: 'v3'; plan: ExecutionPlan}
  | {kind: 'v2'; plan: V2Plan}
  | {kind: 'unknown'};

function detectPlan(raw: string | null | undefined): ParsedPlan {
  if (!raw) return {kind: 'unknown'};
  try {
    const obj = JSON.parse(raw);
    if (obj && Array.isArray(obj.solutions)) {
      return {kind: 'v3', plan: obj as ExecutionPlan};
    }
    if (obj && Array.isArray(obj.tasks)) {
      return {kind: 'v2', plan: obj as V2Plan};
    }
    return {kind: 'unknown'};
  } catch {
    return {kind: 'unknown'};
  }
}

export default function ExecutionPlanReview({executionId}: {executionId: string}) {
  const {executionRun, approvePlan} = useExecutionStore();
  const parsed = useMemo<ParsedPlan>(
    () => detectPlan(executionRun?.executionPlan),
    [executionRun?.executionPlan],
  );

  if (parsed.kind === 'unknown') {
    return <div className='text-sm text-muted'>Waiting for the planner to produce a plan…</div>;
  }

  // V188 unified-dispatch view — render V2's task list directly. No Solution dependency graph
  // (V2 owns task-level deps internally); no FileConflictDetector warnings (V2's
  // CoherencePlannerAgent resolves cross-task conflicts before execution).
  if (parsed.kind === 'v2') {
    const plan = parsed.plan;
    return (
      <div className='space-y-4'>
        <div className='flex items-center gap-3 text-xs text-muted'>
          <span className='font-mono'>{plan.tasks.length} task(s)</span>
          {plan.classification?.strategy && (
            <span className='font-mono'>strategy: {plan.classification.strategy}</span>
          )}
          {plan.projectType && <span className='font-mono'>{plan.projectType}</span>}
        </div>
        {plan.planSummary && <div className='text-xs text-secondary'>{plan.planSummary}</div>}
        <div className='space-y-1.5'>
          {plan.tasks.map((t, i) => (
            <div
              key={t.id ?? i}
              className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm'
            >
              <div className='flex items-baseline gap-2'>
                <span className='font-mono text-[11px] text-muted'>T{i + 1}</span>
                <span className='font-medium'>{t.title}</span>
                {t.complexity && (
                  <span className='ml-auto text-[11px] text-muted'>{t.complexity}</span>
                )}
              </div>
              {t.description && (
                <div className='text-xs text-secondary mt-1 line-clamp-3'>{t.description}</div>
              )}
              {t.dependsOn && t.dependsOn.length > 0 && (
                <div className='text-[11px] text-muted mt-1'>
                  Depends on: {t.dependsOn.join(', ')}
                </div>
              )}
              {t.touchesFiles && t.touchesFiles.length > 0 && (
                <div className='text-[11px] text-muted mt-0.5 truncate'>
                  Files: {t.touchesFiles.slice(0, 5).join(', ')}
                  {t.touchesFiles.length > 5 ? '…' : ''}
                </div>
              )}
            </div>
          ))}
        </div>
        <button
          onClick={() => approvePlan(executionId)}
          className='rounded-sm border border-status-success/40 bg-status-success/10 text-status-success px-3 py-1.5 text-sm'
        >
          Approve &amp; execute
        </button>
      </div>
    );
  }

  // Legacy V3 multi-Solution plan view — retained for in-flight executions created before V188.
  const plan = parsed.plan;
  return (
    <div className='space-y-4'>
      <div className='flex items-center gap-3 text-xs text-muted'>
        <span className='font-mono'>{plan.solutions.length} solutions</span>
        <span className='font-mono'>
          ~{(plan.totalEstimatedTokens / 1000).toFixed(0)}K tokens est
        </span>
      </div>

      <DependencyGraph solutions={plan.solutions} edges={plan.dependencyEdges} />
      <ConflictWarning conflicts={plan.conflicts} />

      <div className='space-y-1.5'>
        {plan.solutions.map((s) => (
          <div
            key={s.sequenceNumber}
            className='rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] px-3 py-2 text-sm'
          >
            <div className='flex items-baseline gap-2'>
              <span className='font-mono text-[11px] text-muted'>S{s.sequenceNumber}</span>
              <span className='font-medium'>{s.title}</span>
              <span className='ml-auto text-[11px] text-muted'>{s.repoName}</span>
            </div>
            {s.description && <div className='text-xs text-secondary mt-1'>{s.description}</div>}
            {s.dependsOn.length > 0 && (
              <div className='text-[11px] text-muted mt-1'>
                Depends on: {s.dependsOn.map((d) => `S${d}`).join(', ')}
              </div>
            )}
            {s.targetFiles.length > 0 && (
              <div className='text-[11px] text-muted mt-0.5 truncate'>
                Files: {s.targetFiles.slice(0, 5).join(', ')}
                {s.targetFiles.length > 5 ? '…' : ''}
              </div>
            )}
          </div>
        ))}
      </div>

      <button
        onClick={() => approvePlan(executionId)}
        className='rounded-sm border border-status-success/40 bg-status-success/10 text-status-success px-3 py-1.5 text-sm'
      >
        Approve &amp; execute
      </button>
    </div>
  );
}
