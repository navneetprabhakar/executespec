import { cn } from '@/lib/utils';
import { STATUS_COLORS } from '@/lib/constants';
import GlowDot from './GlowDot';

interface StatusBadgeProps {
  status: string;
  className?: string;
}

const DISPLAY_NAMES: Record<string, string> = {
  COMPLETE: 'Ready',
  WORKING: 'Building',
  PLANNING: 'Planning',
  COMPILING: 'Compiling',
  REVIEWING: 'Reviewing',
  FAILED: 'Error',
  CANCELLED: 'Canceled',
  QUEUED_BATCH: 'Batch Queued',
  IN_PROGRESS_BATCH: 'Batch Running',
  BATCH_COMPLETE: 'Batch Done',
};

export default function StatusBadge({ status, className }: StatusBadgeProps) {
  const colors = STATUS_COLORS[status] ?? STATUS_COLORS['QUEUED'];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 text-xs font-medium tracking-wide uppercase',
        colors.text,
        className
      )}
    >
      <GlowDot status={status} size="sm" />
      {DISPLAY_NAMES[status] ?? status.replace(/_/g, ' ')}
    </span>
  );
}
