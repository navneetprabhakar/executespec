import { useNavigate } from 'react-router-dom';
import { ArrowUpRight, Check } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import { cn } from '@/lib/utils';
import ResponsiveModal from '@/components/shared/ResponsiveModal';
import Button from '@/components/shared/Button';

type Tier = 'FREE' | 'PRO' | 'ENTERPRISE';

interface UpgradeModalProps {
  isOpen: boolean;
  onClose: () => void;
  featureName?: string;
  requiredTier?: Tier;
}

const TIERS: { id: Tier; name: string; price: string; features: string[] }[] = [
  {
    id: 'FREE',
    name: 'Free',
    price: '$0',
    features: ['5 runs/month', '1 concurrent run', 'Basic features', 'Community support'],
  },
  {
    id: 'PRO',
    name: 'Pro',
    price: '$29/mo',
    features: ['100 runs/month', '3 concurrent runs', 'Integrations (JIRA, Slack)', 'Priority support', 'Org management'],
  },
  {
    id: 'ENTERPRISE',
    name: 'Enterprise',
    price: 'Custom',
    features: ['Unlimited runs', '10 concurrent runs', 'Own API keys', 'Dedicated infrastructure', 'SSO & SAML', 'Custom SLA'],
  },
];

const TIER_RANK: Record<Tier, number> = { FREE: 0, PRO: 1, ENTERPRISE: 2 };

export default function UpgradeModal({ isOpen, onClose, featureName, requiredTier }: UpgradeModalProps) {
  const navigate = useNavigate();
  const currentTier = (useAuthStore((s) => s.user?.tier) ?? 'FREE') as Tier;

  return (
    <ResponsiveModal open={isOpen} onClose={onClose} title="Upgrade Your Plan" maxWidth="max-w-2xl">
      {featureName && (
        <p className="text-xs text-muted mb-4">
          <span className="text-accent-amber font-medium">{featureName}</span> requires{' '}
          {requiredTier ?? 'PRO'} or higher
        </p>
      )}

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {TIERS.map((tier) => {
          const isCurrent = tier.id === currentTier;
          const isRequired = tier.id === requiredTier;
          const isUpgrade = TIER_RANK[tier.id] > TIER_RANK[currentTier];

          return (
            <div
              key={tier.id}
              className={cn(
                'rounded-sm border p-4 flex flex-col',
                isRequired
                  ? 'border-accent-blue/50 bg-accent-blue/[0.05]'
                  : 'border-[var(--border-default)]',
                isCurrent && 'ring-1 ring-accent-green/30',
              )}
            >
              <div className="flex items-center justify-between mb-2">
                <h3 className="text-sm font-semibold text-primary">{tier.name}</h3>
                {isCurrent && (
                  <span className="text-[10px] px-1.5 py-0.5 rounded bg-accent-green/15 text-accent-green font-medium">
                    Current
                  </span>
                )}
              </div>
              <p className="text-lg font-bold text-primary mb-3">{tier.price}</p>
              <ul className="space-y-1.5 flex-1">
                {tier.features.map((f) => (
                  <li key={f} className="flex items-start gap-1.5 text-xs text-secondary">
                    <Check className="h-3 w-3 text-accent-green mt-0.5 flex-shrink-0" />
                    {f}
                  </li>
                ))}
              </ul>
              {isUpgrade && (
                <Button
                  onClick={() => {
                    navigate('/plans');
                    onClose();
                  }}
                  size="sm"
                  variant={isRequired ? 'primary' : 'tertiary'}
                  fullWidth
                  className="mt-4"
                >
                  Upgrade Now
                  <ArrowUpRight className="h-3 w-3" />
                </Button>
              )}
            </div>
          );
        })}
      </div>

      <div className="mt-4 pt-3 border-t border-[var(--border-default)] flex justify-end">
        <Button onClick={onClose} variant="ghost" size="sm">
          Maybe Later
        </Button>
      </div>
    </ResponsiveModal>
  );
}
