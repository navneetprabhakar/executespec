import { useState } from 'react';
import { Lock } from 'lucide-react';
import { useAuthStore } from '@/stores/useAuthStore';
import UpgradeModal from './UpgradeModal';

type Tier = 'FREE' | 'PRO' | 'ENTERPRISE';

const TIER_RANK: Record<Tier, number> = { FREE: 0, PRO: 1, ENTERPRISE: 2 };

interface LockedFeatureProps {
  children: React.ReactNode;
  /** Minimum tier required to unlock this feature. */
  requiredTier: Tier;
  /** Human-readable name shown in the upgrade prompt. */
  featureName: string;
}

/**
 * Renders children normally if the user's tier meets the requirement.
 * Otherwise renders children with reduced opacity and a lock overlay
 * that opens an upgrade modal on click.
 */
export default function LockedFeature({ children, requiredTier, featureName }: LockedFeatureProps) {
  const tier = useAuthStore((s) => s.user?.tier ?? 'FREE') as Tier;
  const [showUpgrade, setShowUpgrade] = useState(false);

  const isUnlocked = TIER_RANK[tier] >= TIER_RANK[requiredTier];

  if (isUnlocked) {
    return <>{children}</>;
  }

  return (
    <>
      <div className="relative group cursor-pointer" onClick={() => setShowUpgrade(true)}>
        {/* Feature content — dimmed */}
        <div className="opacity-50 pointer-events-none select-none">
          {children}
        </div>

        {/* Lock overlay */}
        <div className="absolute inset-0 flex items-center justify-center bg-[var(--gray-200)] rounded-sm opacity-0 group-hover:opacity-100 transition-opacity">
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-sm bg-[var(--gray-200)] border border-[var(--border-default)] shadow-lg">
            <Lock className="h-3.5 w-3.5 text-accent-amber" />
            <span className="text-xs text-secondary">
              {requiredTier} required
            </span>
          </div>
        </div>
      </div>

      <UpgradeModal
        isOpen={showUpgrade}
        onClose={() => setShowUpgrade(false)}
        featureName={featureName}
        requiredTier={requiredTier}
      />
    </>
  );
}
