import { useNavigate } from 'react-router-dom';
import { motion, AnimatePresence } from 'framer-motion';
import { ArrowUpRight, Check, X } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import { cn } from '@/lib/utils';

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
    <AnimatePresence>
      {isOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
          style={{ backdropFilter: 'blur(4px)', background: 'rgba(0,0,0,0.6)' }}
          onClick={onClose}
        >
          <motion.div
            initial={{ opacity: 0, scale: 0.96 }}
            animate={{ opacity: 1, scale: 1 }}
            exit={{ opacity: 0, scale: 0.96 }}
            transition={{ duration: 0.15 }}
            onClick={(e) => e.stopPropagation()}
            className="w-full max-w-2xl rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)] overflow-hidden"
          >
            {/* Header */}
            <div className="flex items-center justify-between px-6 py-4 border-b border-[var(--border-default)]">
              <div>
                <h2 className="text-base font-semibold text-primary">Upgrade Your Plan</h2>
                {featureName && (
                  <p className="text-xs text-muted mt-0.5">
                    <span className="text-accent-amber font-medium">{featureName}</span> requires{' '}
                    {requiredTier ?? 'PRO'} or higher
                  </p>
                )}
              </div>
              <button onClick={onClose} className="p-2 -m-1 text-muted hover:text-secondary">
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* Tier comparison */}
            <div className="p-6 grid grid-cols-3 gap-4">
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
                      <button
                        onClick={() => {
                          navigate('/plans');
                          onClose();
                        }}
                        className={cn(
                          'mt-4 w-full py-2 rounded-sm text-xs font-medium flex items-center justify-center gap-1.5 transition-colors',
                          isRequired
                            ? 'bg-accent-blue text-primary hover:bg-accent-blue/80'
                            : 'border border-[var(--border-default)] text-secondary hover:text-primary hover:border-[var(--border-default)]',
                        )}
                      >
                        Upgrade Now
                        <ArrowUpRight className="h-3 w-3" />
                      </button>
                    )}
                  </div>
                );
              })}
            </div>

            {/* Footer */}
            <div className="px-6 py-3 border-t border-[var(--border-default)] flex justify-end">
              <button
                onClick={onClose}
                className="px-4 py-2 text-xs text-secondary hover:text-primary transition-colors"
              >
                Maybe Later
              </button>
            </div>
          </motion.div>
        </div>
      )}
    </AnimatePresence>
  );
}
