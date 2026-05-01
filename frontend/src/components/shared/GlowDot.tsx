import {cn} from '@/lib/utils';
import {STATUS_COLORS, ACTIVE_STATUSES} from '@/lib/constants';

interface GlowDotProps {
  status: string;
  size?: 'sm' | 'md';
  className?: string;
}

export default function GlowDot({status, size = 'md', className}: GlowDotProps) {
  const colors = STATUS_COLORS[status] ?? STATUS_COLORS['QUEUED'];
  const isWorking = status === 'WORKING';
  const dim = size === 'sm' ? 'h-1.5 w-1.5' : 'h-2 w-2';

  return (
    <span className={cn('relative flex items-center justify-center', className)}>
      {isWorking && (
        <span
          className={cn(
            'absolute inline-flex rounded-full opacity-60 animate-status-pulse',
            dim,
            colors.dot,
          )}
        />
      )}
      <span className={cn('relative inline-flex rounded-full', dim, colors.dot)} />
    </span>
  );
}
