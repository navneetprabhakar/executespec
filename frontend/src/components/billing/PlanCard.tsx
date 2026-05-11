import {useEffect, useRef, useState} from 'react';
import type {BillingPlan, FounderOffer} from '@/lib/billing-api';

// V165 — MAX dropped; ladder is FREE / STARTER / PRO / TEAM / ENTERPRISE.
const TIER_RANK: Record<string, number> = {
  FREE: 0,
  STARTER: 1,
  PRO: 2,
  TEAM: 3,
  ENTERPRISE: 4,
};

interface PlanCardProps {
  plan: BillingPlan;
  isCurrentPlan: boolean;
  showInr: boolean;
  onSelect: (planId: string, seats?: number) => void;
  userTier?: string;
  /** M-002 — when arriving from /admin?seats=N, pre-fill the TEAM seat picker. */
  initialSeats?: number;
  /** M-002 — highlight ring + scroll target when this card matches the
   *  ?tier=... param. */
  highlight?: boolean;
  /**
   * GA-readiness P2.4 — when set, render a founder-pricing badge + discounted
   * price preview. The actual coupon application happens server-side at
   * checkout (BillingService.subscribe consults FounderPricingService);
   * the frontend only renders the offer.
   */
  founderOffer?: FounderOffer | null;
}

// v2.6.0 — TEAM self-serve seat range (must match backend OrgService).
// M-045 (Wave 2) — default flipped 3 → 2 per parked decision #9 to match
// `included_seats=2` on the V123/V144 billing_plan rows (so the picker
// initial value matches what the buyer's first invoice will show).
const TEAM_MIN_SEATS = 2;
const TEAM_MAX_SEATS = 20;
const TEAM_DEFAULT_SEATS = 2;

export function PlanCard({
  plan,
  isCurrentPlan,
  showInr,
  onSelect,
  userTier,
  initialSeats,
  highlight,
  founderOffer,
}: PlanCardProps) {
  const userRank = TIER_RANK[userTier ?? 'FREE'] ?? 0;
  const planRank = TIER_RANK[plan.tier] ?? 0;
  const isDowngrade = planRank < userRank;
  const isUpgrade = planRank > userRank;

  const isTeam = plan.tier === 'TEAM';
  const seedSeats =
    isTeam && initialSeats
      ? Math.max(TEAM_MIN_SEATS, Math.min(TEAM_MAX_SEATS, initialSeats))
      : TEAM_DEFAULT_SEATS;
  const [seats, setSeats] = useState(seedSeats);
  const cardRef = useRef<HTMLDivElement | null>(null);

  // M-002 — when the page is opened with ?tier=team and this card matches,
  // scroll it into view + briefly flash a ring so the buyer lands on the
  // exact card they came for (instead of the top of /plans).
  useEffect(() => {
    if (!highlight) return;
    cardRef.current?.scrollIntoView({behavior: 'smooth', block: 'center'});
  }, [highlight]);
  const effectiveSeats = isTeam ? seats : 1;

  // TEAM price = per-seat × seats. Other tiers use base amount.
  const perSeatInr = plan.perSeatInr ?? 0;
  const perSeatUsd = plan.perSeatUsd ?? 0;
  const totalInrPaise = isTeam ? perSeatInr * effectiveSeats : plan.baseAmountInr;
  const totalUsdCents = isTeam ? perSeatUsd * effectiveSeats : plan.baseAmountUsd;
  const price = showInr
    ? `\u20B9${(totalInrPaise / 100).toLocaleString('en-IN')}`
    : `$${(totalUsdCents / 100).toFixed(0)}`;

  const isPopular = plan.tier === 'PRO';
  // V165 — TEAM is now the BYOK-first headline plan ($39/seat).
  const isHeadline = isTeam && plan.keyMode === 'BYOK';
  const runsLabel = plan.runLimit === -1 ? 'Unlimited runs/month' : `${plan.runLimit} runs/month`;
  const keyModeLabel = plan.keyMode === 'BYOK' ? 'Bring Your Own Keys' : 'Managed Keys';

  return (
    <div
      ref={cardRef}
      className={`relative rounded-md border p-4 sm:p-6 flex flex-col ${
        isHeadline
          ? 'border-status-success'
          : isPopular
            ? 'border-accent-blue'
            : 'border-[var(--border-default)]'
      } ${isCurrentPlan ? 'ring-2 ring-accent-blue' : ''} ${highlight && !isCurrentPlan ? 'ring-2 ring-accent-blue' : ''}`}
    >
      {isPopular && (
        <span className='absolute -top-3 left-1/2 -translate-x-1/2 bg-accent-blue text-primary text-xs font-semibold px-3 py-1 rounded-sm'>
          POPULAR
        </span>
      )}

      {/* V165 — BEST VALUE chip moves to TEAM-BYOK ($39/seat, unlimited
          on the customer's own keys). MAX deprecated; the lowest-cost-of-
          entry headline is now the BYOK Team plan. */}
      {isHeadline && !isCurrentPlan && (
        <span className='absolute -top-3 left-1/2 -translate-x-1/2 bg-status-success text-white text-xs font-semibold px-3 py-1 rounded-sm'>
          BEST VALUE
        </span>
      )}

      {isCurrentPlan && (
        <span className='absolute -top-3 right-4 bg-[var(--status-success)] text-primary text-xs font-semibold px-3 py-1 rounded-sm'>
          CURRENT
        </span>
      )}

      {/* GA-readiness P2.4 — founder pricing badge. Renders when an offer
          for this tier is active and not sold out. The badge shows the
          discount + remaining slots so the buyer knows it's a finite
          window without exposing raw current/max counts. */}
      {founderOffer && founderOffer.tier === plan.tier && founderOffer.remainingCapacity > 0 && (
        <span
          className='absolute -top-3 right-4 bg-[var(--status-warning)] text-primary text-xs font-semibold px-3 py-1 rounded-sm'
          title={`First ${founderOffer.remainingCapacity} subscribers get ${founderOffer.discountPct}% off${founderOffer.isLifetime ? ' for life' : ''}`}
        >
          {founderOffer.discountPct}% FOUNDER · {founderOffer.remainingCapacity} LEFT
        </span>
      )}

      <h3 className='text-lg font-semibold text-primary'>
        {plan.tier === 'TEAM' ? 'Team' : plan.tier === 'ENTERPRISE' ? 'Enterprise' : 'Pro'}
      </h3>
      <p className='text-sm text-muted mt-1'>{keyModeLabel}</p>

      <div className='mt-4'>
        <span className='text-3xl font-bold text-primary'>{price}</span>
        <span className='text-sm text-muted'>{isTeam ? '/mo total' : '/mo'}</span>
        {isTeam && (
          <p className='text-xs text-muted mt-1'>
            {showInr
              ? `\u20B9${(perSeatInr / 100).toLocaleString('en-IN')}`
              : `$${(perSeatUsd / 100).toFixed(0)}`}
            /seat/month &middot; {effectiveSeats} seat{effectiveSeats > 1 ? 's' : ''}
          </p>
        )}
        {showInr && <p className='text-xs text-muted mt-1'>+ 18% GST</p>}
      </div>

      {isTeam && (
        <div className='mt-4 flex items-center gap-3'>
          <label className='text-xs text-muted font-mono uppercase tracking-wide'>Seats</label>
          <input
            type='number'
            min={TEAM_MIN_SEATS}
            max={TEAM_MAX_SEATS}
            value={seats}
            onChange={(e) => {
              const n = parseInt(e.target.value, 10);
              if (!Number.isNaN(n)) setSeats(Math.max(TEAM_MIN_SEATS, Math.min(TEAM_MAX_SEATS, n)));
            }}
            className='w-20 px-2 py-1 bg-[var(--gray-200)] border border-[var(--border-default)] rounded-sm text-sm text-primary focus:outline-none focus:ring-1 focus:ring-accent-blue'
          />
          <span className='text-xs text-muted'>
            ({TEAM_MIN_SEATS}-{TEAM_MAX_SEATS} &middot; larger &rarr; Enterprise)
          </span>
        </div>
      )}

      <ul className='mt-6 space-y-3 flex-1 text-sm text-secondary'>
        <li className='flex items-center gap-2'>
          <span className='text-accent-blue'>&#10003;</span>
          {runsLabel}
        </li>
        {!isTeam && (
          <li className='flex items-center gap-2'>
            <span className='text-accent-blue'>&#10003;</span>
            {plan.includedSeats} seat{plan.includedSeats > 1 ? 's' : ''} included
          </li>
        )}
        {isTeam && (
          <>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>
              Shared credit pool across your team
            </li>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>
              Google OIDC sign-in
            </li>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>
              Org admin dashboard &middot; seat management
            </li>
          </>
        )}
        <li className='flex items-center gap-2'>
          <span className='text-accent-blue'>&#10003;</span>
          {plan.limits?.PROJECTS === -1 ? '\u221E' : plan.limits?.PROJECTS || '\u221E'} projects
        </li>
        <li className='flex items-center gap-2'>
          <span className='text-accent-blue'>&#10003;</span>
          {(plan.limits?.STORAGE_MB || 0) >= 1024
            ? `${((plan.limits?.STORAGE_MB || 0) / 1024).toFixed(0)} GB`
            : `${plan.limits?.STORAGE_MB || 0} MB`}{' '}
          storage
        </li>
        <li className='flex items-center gap-2'>
          <span className='text-accent-blue'>&#10003;</span>
          All products included
        </li>
        {isHeadline && (
          <>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>3 concurrent runs per seat
            </li>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>
              Your own API keys — full control
            </li>
          </>
        )}
        {isPopular && (
          <>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>2 concurrent runs
            </li>
            <li className='flex items-center gap-2'>
              <span className='text-accent-blue'>&#10003;</span>
              Org management & RBAC
            </li>
          </>
        )}
      </ul>

      <button
        onClick={() => onSelect(plan.id, isTeam ? effectiveSeats : undefined)}
        disabled={isCurrentPlan || isDowngrade}
        className={`mt-6 w-full py-2.5 rounded-md text-sm font-semibold transition ${
          isCurrentPlan
            ? 'bg-accent-blue/10 text-accent-blue cursor-default'
            : isDowngrade
              ? 'bg-[var(--gray-200)] text-muted cursor-not-allowed'
              : isHeadline
                ? 'bg-status-success text-white hover:bg-status-success/80'
                : isPopular
                  ? 'bg-accent-blue text-primary hover:bg-accent-blue-hover'
                  : 'bg-[var(--gray-300)] text-primary hover:bg-[var(--gray-400)]'
        }`}
      >
        {isCurrentPlan
          ? 'Current Plan'
          : isUpgrade
            ? 'Upgrade'
            : isDowngrade
              ? 'Current plan is higher'
              : 'Get Started'}
      </button>
    </div>
  );
}
