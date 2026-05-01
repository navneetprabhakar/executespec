import {cn} from '@/lib/utils';
import IntegrationLogo from './IntegrationLogo';

interface IntegrationCardProps {
  integrationKey: string;
  name: string;
  description: string;
  enabled: boolean;
}

export default function IntegrationCard({
  integrationKey,
  name,
  description,
  enabled,
}: IntegrationCardProps) {
  return (
    <div
      className={cn(
        'p-4 rounded-sm border transition-all',
        enabled
          ? 'border-accent-blue/30 bg-accent-blue/[0.04]'
          : 'border-[var(--border-default)] bg-[var(--gray-100)] opacity-60 grayscale',
      )}
    >
      <div className='flex items-center justify-between mb-2'>
        <div className='flex items-center gap-2.5'>
          <IntegrationLogo integration={integrationKey} size={28} />
          <div>
            <p className='text-sm font-medium text-primary'>{name}</p>
            <p className='text-xs text-muted'>{description}</p>
          </div>
        </div>
        <span
          className={cn(
            'text-xs px-2 py-0.5 rounded-sm font-medium',
            enabled ? 'text-accent-green bg-accent-green/10' : 'text-muted bg-[var(--gray-400)]/10',
          )}
        >
          {enabled ? 'Enabled' : 'Disabled'}
        </span>
      </div>
    </div>
  );
}
