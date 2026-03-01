import { cn } from '@/lib/utils';
import { STATUS_COLORS, ACTIVE_STATUSES } from '@/lib/constants';

interface GlowDotProps {
  status: string;
  size?: 'sm' | 'md';
  className?: string;
}

export default function GlowDot({ status, size = 'md', className }: GlowDotProps) {
  const colors = STATUS_COLORS[status] ?? STATUS_COLORS['QUEUED'];
  const isActive = ACTIVE_STATUSES.has(status);
  const dim = size === 'sm' ? 'h-2 w-2' : 'h-2.5 w-2.5';

  return (
    <span className={cn('relative flex items-center justify-center', className)}>
      {isActive && (
        <span
          className={cn(
            'absolute inline-flex rounded-full opacity-75 animate-ping',
            dim,
            colors.dot
          )}
        />
      )}
      <span className={cn('relative inline-flex rounded-full', dim, colors.dot, colors.glow)} />
    </span>
  );
}
