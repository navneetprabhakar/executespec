import { cn } from '@/lib/utils';
import IntegrationLogo from './IntegrationLogo';

interface IntegrationCardProps {
  integrationKey: string;
  name: string;
  description: string;
  enabled: boolean;
}

export default function IntegrationCard({ integrationKey, name, description, enabled }: IntegrationCardProps) {
  return (
    <div className={cn(
      'p-4 rounded-xl border transition-all',
      enabled
        ? 'border-accent-violet/30 bg-accent-violet/[0.04]'
        : 'border-white/[0.06] bg-white/[0.02] opacity-60 grayscale'
    )}>
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2.5">
          <IntegrationLogo integration={integrationKey} size={28} />
          <div>
            <p className="text-sm font-medium text-zinc-200">{name}</p>
            <p className="text-xs text-zinc-500">{description}</p>
          </div>
        </div>
        <span className={cn(
          'text-xs px-2 py-0.5 rounded-full font-medium',
          enabled ? 'text-accent-emerald bg-accent-emerald/10' : 'text-zinc-600 bg-zinc-600/10'
        )}>
          {enabled ? 'Enabled' : 'Disabled'}
        </span>
      </div>
    </div>
  );
}
