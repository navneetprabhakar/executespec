import { cn } from '@/lib/utils';
import { STATUS_COLORS } from '@/lib/constants';
import GlowDot from './GlowDot';

interface StatusBadgeProps {
  status: string;
  className?: string;
  /** v2.5.10 — optional explicit reason for the terminal status so the badge
   *  can surface a tooltip like "PARTIAL because FINAL_BUILD_FAILED" instead
   *  of the silent badge that confused users in the v2.5.9 evaluation. */
  statusReason?: string | null;
}

const DISPLAY_NAMES: Record<string, string> = {
  COMPLETE: 'Ready',
  WORKING: 'Building',
  EXECUTING: 'Building',
  PLANNING: 'Planning',
  REVIEWING: 'Reviewing',
  FAILED: 'Error',
  CANCELLED: 'Canceled',
  PARTIAL: 'Partial',
  // Project Launch (ExecutionRun) statuses — friendly copy
  SETUP: 'Setting up',
  ANALYSING: 'Reading code',
  ARCH_REVIEW: 'Architecture review',
  AWAITING_APPROVAL: 'Plan ready',
  // Batch path (planned V2 batch implementation)
  QUEUED_BATCH: 'Batch Queued',
  IN_PROGRESS_BATCH: 'Batch Running',
  BATCH_COMPLETE: 'Batch Done',
};

/**
 * Copy table for status_reason — shown as a native HTML `title` tooltip so it
 * renders everywhere the badge is used without pulling in a tooltip library.
 * Handles the dynamic "TESTS_FAILED:N_OF_M" suffix too.
 */
function explainReason(reason: string | null | undefined): string | null {
  if (!reason) return null;
  if (reason.startsWith('TESTS_FAILED:')) {
    // Format: TESTS_FAILED:<n>_OF_<m>
    const tail = reason.substring('TESTS_FAILED:'.length);
    const parts = tail.split('_OF_');
    if (parts.length === 2) {
      return `${parts[0]} of ${parts[1]} tests failed`;
    }
    return 'Some tests failed';
  }
  switch (reason) {
    case 'CLEAN_COMPLETION':
      return 'All checks green — build passed, tests passed, reviewer clean.';
    case 'CLEAN_WITH_INTERMEDIATE_WARNINGS':
      return 'Completed; some intermediate smoke warnings along the way. Final state is clean.';
    case 'FINAL_BUILD_FAILED':
      return 'Final build did not pass — code may be unusable.';
    case 'REVIEWER_UNRESOLVED':
      return 'Reviewer flagged unresolved issues. Code compiled but may have bugs.';
    case 'REVIEWER_FAILED':
      return 'Reviewer could not complete its pass.';
    case 'ALL_TASKS_FAILED':
      return 'Every task failed — nothing usable was produced.';
    case 'EXECUTION_ABORTED':
      return 'Run was aborted mid-execution.';
    case 'LEGACY_UNKNOWN':
      return 'Status reason unavailable (pre-v2.5.10 run).';
    default:
      return reason;
  }
}

export default function StatusBadge({ status, className, statusReason }: StatusBadgeProps) {
  const colors = STATUS_COLORS[status] ?? STATUS_COLORS['QUEUED'];
  const tooltip = explainReason(statusReason);
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-xs font-medium tracking-wide uppercase',
        colors.text,
        tooltip && 'cursor-help',
        className
      )}
      title={tooltip ?? undefined}
    >
      <GlowDot status={status} size="sm" />
      {DISPLAY_NAMES[status] ?? status.replace(/_/g, ' ')}
    </span>
  );
}
