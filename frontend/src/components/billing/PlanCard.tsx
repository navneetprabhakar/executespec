import type { BillingPlan } from '@/lib/billing-api';

interface PlanCardProps {
  plan: BillingPlan;
  isCurrentPlan: boolean;
  showInr: boolean;
  onSelect: (planId: string) => void;
}

export function PlanCard({ plan, isCurrentPlan, showInr, onSelect }: PlanCardProps) {
  const price = showInr
    ? `\u20B9${(plan.baseAmountInr / 100).toLocaleString('en-IN')}`
    : `$${(plan.baseAmountUsd / 100).toFixed(0)}`;

  const periodLabel = plan.period === 'ANNUAL' ? '/mo (billed annually)' : '/mo';

  const isPopular = plan.tier === 'PRO';

  return (
    <div className={`relative rounded-md border p-6 flex flex-col ${
      isPopular ? 'border-accent-blue' : 'border-[var(--border-default)]'
    } ${isCurrentPlan ? 'ring-2 ring-accent-blue' : ''}`}>

      {isPopular && (
        <span className="absolute -top-3 left-1/2 -translate-x-1/2 bg-accent-blue text-primary text-xs font-semibold px-3 py-1 rounded-sm">
          POPULAR
        </span>
      )}

      {isCurrentPlan && (
        <span className="absolute -top-3 right-4 bg-[var(--status-success)] text-primary text-xs font-semibold px-3 py-1 rounded-sm">
          CURRENT
        </span>
      )}

      <h3 className="text-lg font-semibold text-primary">{plan.tier}</h3>
      <p className="text-sm text-muted mt-1">{plan.keyMode === 'BYOK' ? 'Bring Your Own Keys' : 'Platform Keys'}</p>

      <div className="mt-4">
        <span className="text-3xl font-bold text-primary">{price}</span>
        <span className="text-sm text-muted">{periodLabel}</span>
      </div>

      <ul className="mt-6 space-y-3 flex-1 text-sm text-secondary">
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {plan.runLimit} runs/month
        </li>
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {plan.includedSeats} seat{plan.includedSeats > 1 ? 's' : ''} included
        </li>
        <li className="flex items-center gap-2">
          <span className="text-accent-blue">&#10003;</span>
          {plan.limits?.PROJECTS || '\u221E'} projects
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
        {plan.tier === 'PRO' && (
          <>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              Org management & RBAC
            </li>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              Jira/Confluence integration
            </li>
            <li className="flex items-center gap-2">
              <span className="text-accent-blue">&#10003;</span>
              Slack notifications
            </li>
          </>
        )}
      </ul>

      <button
        onClick={() => onSelect(plan.id)}
        disabled={isCurrentPlan}
        className={`mt-6 w-full py-2.5 rounded-md text-sm font-semibold transition ${
          isCurrentPlan
            ? 'bg-accent-blue/10 text-accent-blue cursor-default'
            : isPopular
              ? 'bg-accent-blue text-primary hover:bg-accent-blue-hover'
              : 'bg-[var(--gray-300)] text-primary hover:bg-[var(--gray-400)]'
        }`}
      >
        {isCurrentPlan ? 'Current Plan' : 'Get Started'}
      </button>
    </div>
  );
}
