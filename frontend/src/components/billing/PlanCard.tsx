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
    <div className={`relative rounded-xl border-2 p-6 flex flex-col ${
      isPopular ? 'border-indigo-600 shadow-lg' : 'border-gray-200'
    } ${isCurrentPlan ? 'ring-2 ring-green-500' : ''}`}>

      {isPopular && (
        <span className="absolute -top-3 left-1/2 -translate-x-1/2 bg-indigo-600 text-white text-xs font-semibold px-3 py-1 rounded-full">
          POPULAR
        </span>
      )}

      {isCurrentPlan && (
        <span className="absolute -top-3 right-4 bg-green-500 text-white text-xs font-semibold px-3 py-1 rounded-full">
          CURRENT
        </span>
      )}

      <h3 className="text-lg font-semibold text-gray-900">{plan.tier}</h3>
      <p className="text-sm text-gray-500 mt-1">{plan.keyMode === 'BYOK' ? 'Bring Your Own Keys' : 'Platform Keys'}</p>

      <div className="mt-4">
        <span className="text-3xl font-bold text-gray-900">{price}</span>
        <span className="text-sm text-gray-500">{periodLabel}</span>
      </div>

      <ul className="mt-6 space-y-3 flex-1 text-sm text-gray-700">
        <li className="flex items-center gap-2">
          <span className="text-green-500">&#10003;</span>
          {plan.runLimit} runs/month
        </li>
        <li className="flex items-center gap-2">
          <span className="text-green-500">&#10003;</span>
          {plan.includedSeats} seat{plan.includedSeats > 1 ? 's' : ''} included
        </li>
        <li className="flex items-center gap-2">
          <span className="text-green-500">&#10003;</span>
          {plan.limits?.PROJECTS || '\u221E'} projects
        </li>
        <li className="flex items-center gap-2">
          <span className="text-green-500">&#10003;</span>
          {((plan.limits?.STORAGE_MB || 0) >= 1024)
            ? `${((plan.limits?.STORAGE_MB || 0) / 1024).toFixed(0)} GB`
            : `${plan.limits?.STORAGE_MB || 0} MB`
          } storage
        </li>
        <li className="flex items-center gap-2">
          <span className="text-green-500">&#10003;</span>
          All products included
        </li>
        {plan.tier === 'PRO' && (
          <>
            <li className="flex items-center gap-2">
              <span className="text-green-500">&#10003;</span>
              Org management & RBAC
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">&#10003;</span>
              Jira/Confluence integration
            </li>
            <li className="flex items-center gap-2">
              <span className="text-green-500">&#10003;</span>
              Slack notifications
            </li>
          </>
        )}
      </ul>

      <button
        onClick={() => onSelect(plan.id)}
        disabled={isCurrentPlan}
        className={`mt-6 w-full py-2.5 rounded-lg text-sm font-semibold transition ${
          isCurrentPlan
            ? 'bg-green-100 text-green-700 cursor-default'
            : isPopular
              ? 'bg-indigo-600 text-white hover:bg-indigo-700'
              : 'bg-gray-900 text-white hover:bg-gray-800'
        }`}
      >
        {isCurrentPlan ? 'Current Plan' : 'Get Started'}
      </button>
    </div>
  );
}
