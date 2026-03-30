import { cn } from '@/lib/utils';
import { STATUS_COLORS } from '@/lib/constants';

interface StatusBadgeProps {
  status: string;
  className?: string;
}

const DISPLAY_NAMES: Record<string, string> = {
  COMPLETE: 'CODE GENERATED',
};

export default function StatusBadge({ status, className }: StatusBadgeProps) {
  const colors = STATUS_COLORS[status] ?? STATUS_COLORS['QUEUED'];
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-sm text-xs font-medium',
        colors.bg,
        colors.text,
        className
      )}
    >
      {DISPLAY_NAMES[status] ?? status.replace(/_/g, ' ')}
    </span>
  );
}
