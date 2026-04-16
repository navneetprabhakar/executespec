import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function fmtTokens(n: number): string {
  if (!n) return '0';
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

export function fmtMs(ms: number): string {
  if (!ms) return '0ms';
  if (ms < 1000) return `${ms}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.floor(ms / 60_000);
  const s = Math.round((ms % 60_000) / 1000);
  return s > 0 ? `${m}m ${s}s` : `${m}m`;
}

export function fmtCost(usd: number): string {
  if (!usd) return '$0.00';
  if (usd < 0.01) return `$${usd.toFixed(4)}`;
  return `$${usd.toFixed(2)}`;
}

export function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime();
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}m ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}h ago`;
  return `${Math.floor(diff / 86_400_000)}d ago`;
}

export function truncate(s: string | null | undefined, n: number): string {
  if (s == null || typeof s !== 'string') return '';
  return s.length > n ? s.slice(0, n) + '…' : s;
}

/**
 * Returns a human-readable status description for a pipeline run.
 * Provides context-aware detail when tasks are available.
 */
export function describeRunStatus(
  status: string,
  tasks?: { status: string; title: string }[],
  failureReason?: string | null,
  executionMode?: string | null,
): string {
  const total = tasks?.length ?? 0;
  const completed = tasks?.filter(t => t.status === 'COMPLETE').length ?? 0;
  const failed = tasks?.filter(t => t.status === 'FAILED').length ?? 0;
  const retryable = tasks?.filter(t =>
    ['FAILED', 'PARTIAL', 'BLOCKED'].includes(t.status)
  ).length ?? 0;
  const working = tasks?.find(t => t.status === 'IN_PROGRESS' || t.status === 'WORKING');

  switch (status) {
    case 'QUEUED':
      return 'Waiting in queue';
    case 'PLANNING':
      return 'Generating execution plan';
    case 'AWAITING_APPROVAL':
      return total > 0
        ? `Plan ready — ${total} task${total !== 1 ? 's' : ''} proposed`
        : 'Plan ready for review';
    case 'AWAITING_CLARIFICATION':
      return 'Needs your input to proceed';
    case 'WORKING':
      if (working && total > 0) {
        return `Task ${completed + 1} of ${total} — ${truncate(working.title, 40)}`;
      }
      if (total > 0) {
        return `Building — ${completed}/${total} tasks done`;
      }
      return 'Executing tasks';
    case 'REVIEWING':
      return `Reviewing ${total} task${total !== 1 ? 's' : ''} for quality`;
    case 'AWAITING_MERGE':
      return 'Code ready — awaiting merge approval';
    case 'MERGING':
      return 'Merging changes to main';
    case 'COMPLETE':
      if (total > 0) {
        const modeLabel = executionMode === 'PROTOTYPE' ? 'Prototype' : '';
        return `${modeLabel ? modeLabel + ' — ' : ''}Code generated — ${total} task${total !== 1 ? 's' : ''} complete`.trim();
      }
      return 'Code generated';
    case 'FAILED':
      if (failureReason) return truncate(failureReason, 50);
      if (retryable > 0 && total > 0) return `Failed — ${retryable} task${retryable !== 1 ? 's' : ''} can be retried`;
      if (failed > 0 && total > 0) return `Failed — ${failed}/${total} tasks errored`;
      return 'Run failed';
    case 'PARTIAL':
      // v2.5.7 UX-fix: pluralisation + fallback when task list is missing so
      // sidebar doesn't show "0/0 tasks completed" on a PARTIAL run whose
      // RunSummary doesn't include the tasks array.
      if (total === 0) return 'Some tasks need manual retry';
      if (retryable > 0) return `${completed}/${total} tasks done — ${retryable} ${retryable === 1 ? 'needs' : 'need'} manual retry`;
      return `${completed}/${total} tasks completed`;
    case 'PAUSED':
      if (total > 0) return `Paused — ${completed}/${total} tasks done`;
      return 'Run paused';
    case 'CANCELLED':
      return 'Run cancelled';
    case 'STUCK':
      return 'Pipeline stalled — may need retry';
    default:
      return status.replace(/_/g, ' ').toLowerCase();
  }
}
