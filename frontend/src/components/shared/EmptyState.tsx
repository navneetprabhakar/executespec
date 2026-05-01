import {Rocket} from 'lucide-react';
import type {LucideIcon} from 'lucide-react';

interface EmptyStateProps {
  title?: string;
  description?: string;
  action?: React.ReactNode;
  /** Override the default Rocket icon. */
  icon?: LucideIcon;
  /**
   * v2.5.12 — compact variant for use inside panels (Launches list, Repos list,
   * notification panel, etc). Smaller padding, no oversized icon block. Use
   * when the empty state lives inside a card; default (full) when it sits at
   * page level.
   */
  compact?: boolean;
}

export default function EmptyState({
  title = 'Nothing here yet',
  description = 'Get started by creating something new.',
  action,
  icon: Icon = Rocket,
  compact = false,
}: EmptyStateProps) {
  if (compact) {
    return (
      <div className='flex flex-col items-center justify-center py-6 gap-2 text-center'>
        <Icon className='h-5 w-5 text-muted' />
        <div>
          <p className='text-xs font-medium text-secondary'>{title}</p>
          {description && <p className='text-xs text-muted mt-0.5'>{description}</p>}
        </div>
        {action && <div className='mt-1'>{action}</div>}
      </div>
    );
  }
  return (
    <div className='flex flex-col items-center justify-center py-20 gap-4 text-center'>
      <div className='rounded-md bg-[var(--gray-200)] p-4 border border-[var(--border-default)]'>
        <Icon className='h-8 w-8 text-muted' />
      </div>
      <div>
        <p className='text-base font-semibold text-primary'>{title}</p>
        <p className='text-sm text-muted mt-1'>{description}</p>
      </div>
      {action && <div className='mt-2'>{action}</div>}
    </div>
  );
}
