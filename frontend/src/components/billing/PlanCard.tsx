import type { BillingPlan } from '@/lib/billing-api';

const TIER_RANK: Record<string, number> = { FREE: 0, STARTER: 1, PRO: 2, MAX: 3, ENTERPRISE: 4 };

interface PlanCardProps {
  plan: BillingPlan;
  isCurrentPlan: boolean;
  showInr: boolean;
  onSelect: (planId: string) => void;
  userTier?: string;
}

export function PlanCard({ plan, isCurrentPlan, showInr, onSelect, userTier }: PlanCardProps) {
  const userRank = TIER_RANK[userTier ?? 'FREE'] ?? 0;
  const planRank = TIER_RANK[plan.tier] ?? 0;
  const isDowngrade = planRank < userRank;
  const isUpgrade = planRank > userRank;
  const price = showInr
    ? `\u20B9${(plan.baseAmountInr / 100).toLocaleString('en-IN')}`
    : `$${(plan.baseAmountUsd / 100).toFixed(0)}`;

  const isPopular = plan.tier === 'PRO';
  const isMax = plan.tier === 'MAX';
  const runsLabel = plan.runLimit === -1 ? 'Unlimited runs/month' : `${plan.runLimit} runs/month`;
  const keyModeLabel = plan.keyMode === 'BYOK' ? 'Bring Your Own Keys' : 'Managed Keys';

  return (
    <div className={`relative rounded-md border p-6 flex flex-col ${
      isMax ? 'border-emerald-600' : isPopular ? 'border-accent-blue' : 'border-[var(--border-default)]'
    } ${isCurrentPlan ? 'ring-2 ring-accent-blue' : ''}`}>

      {isPopular && (
        <span className="absolute -top-3 left-1/2 -translate-x-1/2 bg-accent-blue text-primary text-xs font-semibold px-3 py-1 rounded-sm">
          POPULAR
        </span>
      )}

      {isMax && !isCurrentPlan && (
        <span className="absolute -top-3 left-1/2 -translate-x-1/2 bg-emerald-600 text-white text-xs font-semibold px-3 py-1 rounded-sm">
          BEST VALUE
        </span>
      )}

      {isCurrentPlan && (
        <span className="absolute -top-3 right-4 bg-[var(--status-success)] text-primary text-xs font-semibold px-3 py-1 rounded-sm">
          CURRENT
        </span>
      )}

      <h3 className="text-lg font-semibold text-primary">{plan.tier === 'MAX' ? 'Max' : 'Pro'}</h3>
      <p className="text-sm text-muted mt-1">{keyModeLabel}</p>

      <div className="mt-4">
        <span className="text-3xl font-bold text-primary">{price}</span>
        <span className="text-sm text-muted">/mo</span>
        {showInr && <p className="text-xs text-muted mt-1">+ 18% GST</p>}
      </div>

      <ul className="mt-6 space-y-3 flex-1 text-sm text-secondary">
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {runsLabel}
        </li>
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {plan.includedSeats} seat{plan.includedSeats > 1 ? 's' : ''} included
        </li>
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {plan.limits?.PROJECTS === -1 ? '\u221E' : plan.limits?.PROJECTS || '\u221E'} projects
        </li>
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {((plan.limits?.STORAGE_MB || 0) >= 1024)
            ? `${((plan.limits?.STORAGE_MB || 0) / 1024).toFixed(0)} GB`
            : `${plan.limits?.STORAGE_MB || 0} MB`
          } storage
        </li>
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          All products included
        </li>
        {isMax && (
          <>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              3 concurrent runs
            </li>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              Your own API keys — full control
            </li>
          </>
        )}
        {isPopular && (
          <>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              2 concurrent runs
            </li>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              Org management & RBAC
            </li>
          </>
        )}
      </ul>

      <button
        onClick={() => onSelect(plan.id)}
        disabled={isCurrentPlan || isDowngrade}
        className={`mt-6 w-full py-2.5 rounded-md text-sm font-semibold transition ${
          isCurrentPlan
            ? 'bg-accent-blue/10 text-accent-blue cursor-default'
            : isDowngrade
              ? 'bg-[var(--gray-200)] text-muted cursor-not-allowed'
              : isMax
                ? 'bg-emerald-600 text-white hover:bg-emerald-700'
                : isPopular
                  ? 'bg-accent-blue text-primary hover:bg-accent-blue-hover'
                  : 'bg-[var(--gray-300)] text-primary hover:bg-[var(--gray-400)]'
        }`}
      >
        {isCurrentPlan ? 'Current Plan' : isUpgrade ? 'Upgrade' : isDowngrade ? 'Current plan is higher' : 'Get Started'}
      </button>
    </div>
  );
}
