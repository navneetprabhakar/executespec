import { useState } from 'react';
import { ChevronDown, RefreshCw, BookOpen, AlertTriangle } from 'lucide-react';
import GlowDot from '@/components/shared/GlowDot';
import { fmtTokens, fmtMs, cn } from '@/lib/utils';
import { useRunStore } from '@/stores/useRunStore';
import type { TaskSummary } from '@/lib/api';
import RepoChip, { extractRepoShortName } from './RepoChip';

interface TaskCardProps {
  task: TaskSummary;
  runId: string;
  /** Pass the parent run's status so PENDING/LOCKED tasks show retry only when the run is stuck. */
  runStatus?: string;
  /** Subtasks belonging to this parent task (only provided for parent tasks). */
  subtasks?: TaskSummary[];
}

/** Statuses that are always retryable (backend validates these). */
const ALWAYS_RETRYABLE = new Set(['PARTIAL', 'BLOCKED', 'FAILED']);

/** Statuses that are retryable only when the run itself is PARTIAL or FAILED. */
const STUCK_RETRYABLE  = new Set(['PENDING', 'LOCKED']);

/** Run statuses that indicate the pipeline is stuck and needs manual intervention. */
const STUCK_RUN = new Set(['PARTIAL', 'FAILED']);

function isRetryable(taskStatus: string, runStatus?: string): boolean {
  if (ALWAYS_RETRYABLE.has(taskStatus)) return true;
  if (STUCK_RETRYABLE.has(taskStatus) && runStatus && STUCK_RUN.has(runStatus)) return true;
  return false;
}

/** Colour theme for the failure-reason box based on status. */
function reasonColorClass(status: string) {
  if (status === 'FAILED')  return 'border-accent-red/40  bg-accent-red/[0.06]  text-accent-red';
  if (status === 'BLOCKED') return 'border-accent-amber/40 bg-accent-amber/[0.06] text-accent-amber';
  if (status === 'PENDING' || status === 'LOCKED')
    return 'border-[var(--gray-400)] bg-[var(--gray-100)] text-secondary';
  return 'border-accent-amber/30 bg-accent-amber/[0.04] text-accent-amber/80'; // PARTIAL
}

function statusLabel(status: string): string {
  if (status === 'FAILED')  return 'Task failed';
  if (status === 'BLOCKED') return 'Task blocked';
  if (status === 'PENDING') return 'Task never started';
  if (status === 'LOCKED')  return 'Task stalled before execution';
  return 'Task incomplete';
}

export default function TaskCard({ task, runId, runStatus, subtasks }: TaskCardProps) {
  const { retryTask } = useRunStore();

  const isSubtask  = !!task.parentTaskId;
  const hasSubtasks = subtasks && subtasks.length > 0;
  const retryable = isRetryable(task.status, runStatus);
  const hasReason  = !!task.failureReason?.trim();

  // Auto-expand cards that have an actionable problem
  const [open, setOpen] = useState(retryable && (hasReason || STUCK_RETRYABLE.has(task.status)));

  return (
    <div className={cn(
      'rounded-sm border border-[var(--border-default)] bg-[var(--gray-100)] overflow-hidden',
      isSubtask && 'ml-6 border-l-2 border-l-[var(--gray-400)]'
    )}>
      <button
        onClick={() => setOpen((v) => !v)}
        className="w-full flex items-center gap-3 px-4 py-3 text-left hover:bg-[var(--gray-100)]"
      >
        <GlowDot status={task.status} size="sm" className="flex-shrink-0" />
        <div className="flex-1 min-w-0">
          <p className={cn('text-primary truncate', isSubtask ? 'text-xs' : 'text-sm')}>{task.title}</p>
          <div className="flex items-center gap-2 mt-0.5 flex-wrap">
            <span className="text-xs text-muted font-mono">{task.taskId.slice(0, 8)}</span>
            <RepoChip name={extractRepoShortName(task.title, task.objective, task.completionNotes)} />
            {task.branchName && !isSubtask && (
              <span className="text-xs text-muted font-mono">{task.branchName}</span>
            )}
            {/* Subtask progress badge for parent tasks */}
            {hasSubtasks && (
              <span className="text-xs text-muted">
                {subtasks.filter(s => s.status === 'COMPLETE').length}/{subtasks.length} files
              </span>
            )}
            {/* Subtask index badge */}
            {isSubtask && task.subtaskIndex && task.subtaskTotal && (
              <span className="text-xs text-muted">
                file {task.subtaskIndex}/{task.subtaskTotal}
              </span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-3 text-right flex-shrink-0">
          {(task.inputTokens + task.outputTokens) > 0 && (
            <span className="text-xs text-muted">
              {fmtTokens(task.inputTokens + task.outputTokens)}
            </span>
          )}
          {task.durationMs > 0 && (
            <span className="text-xs text-muted">{fmtMs(task.durationMs)}</span>
          )}
          {task.readmeUpdated && (
            <span title="README updated">
              <BookOpen className="h-3.5 w-3.5 text-accent-green" />
            </span>
          )}
          {/* Collapsed-row warning badge */}
          {retryable && !open && (
            <AlertTriangle className="h-3.5 w-3.5 text-accent-amber" />
          )}
          <ChevronDown
            className={cn('h-4 w-4 text-muted transition-transform', open && 'rotate-180')}
          />
        </div>
      </button>

      {open && (
        <div className="px-4 py-3 border-t border-[var(--border-default)] space-y-3">
          {/* ── Task objective / description ─────────────────────────────── */}
          {task.objective && (
            <p className="text-xs text-secondary leading-relaxed whitespace-pre-wrap">
              {task.objective}
            </p>
          )}

          {/* ── Completion Notes ───────────────────────────────────────── */}
          {task.completionNotes && (
            <div className="bg-accent-green/[0.04] border border-accent-green/20 rounded-md p-3">
              <span className="text-xs font-medium text-accent-green mb-1 block">Implementation Notes</span>
              <p className="text-xs text-secondary leading-relaxed whitespace-pre-wrap">
                {task.completionNotes}
              </p>
            </div>
          )}

          {/* ── Failure / stuck reason ───────────────────────────────────── */}
          {retryable && (hasReason || STUCK_RETRYABLE.has(task.status)) && (
            <div
              className={cn(
                'flex gap-2 rounded-md border px-3 py-2 text-xs',
                reasonColorClass(task.status),
              )}
            >
              <AlertTriangle className="h-3.5 w-3.5 mt-0.5 flex-shrink-0" />
              <div className="space-y-1">
                <p className="font-medium">{statusLabel(task.status)}</p>
                {hasReason ? (
                  <p className="opacity-80 leading-snug whitespace-pre-wrap">
                    {task.failureReason}
                  </p>
                ) : (
                  <p className="opacity-60 leading-snug">
                    This task did not execute. Click Retry to run it now.
                  </p>
                )}
              </div>
            </div>
          )}

          {/* ── Retry button ─────────────────────────────────────────────── */}
          {retryable && (
            <button
              onClick={() => retryTask(runId, task.taskId)}
              className="flex items-center gap-1.5 text-xs text-accent-amber hover:text-accent-amber/80 transition-colors"
            >
              <RefreshCw className="h-3 w-3" />
              Retry task
            </button>
          )}
        </div>
      )}
    </div>
  );
}
