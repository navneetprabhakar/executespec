import {describe, expect, it} from 'vitest';

import {
  billingUsageSummary,
  invoiceDisplay,
  managedKind,
  money,
  planCardsFrom,
  planMoney,
} from './billingUtils';
import type * as api from '@/lib/api';
import type {BillingPlan} from '@/lib/api/billing';

describe('billingUtils', () => {
  it('formats dashboard and plan money values', () => {
    expect(money(null)).toBe('—');
    expect(money(1.5)).toBe('$1.50');
    expect(planMoney(null, 'USD')).toBe('Custom');
    expect(planMoney(0, 'USD')).toBe('Free');
    expect(planMoney(29, 'USD')).toBe('$29');
    expect(planMoney(3399, 'INR')).toBe('₹3399');
  });

  it('maps managed AI routing states to badge kinds', () => {
    expect(managedKind('FULL')).toBe('ok');
    expect(managedKind('VALUE_MODE')).toBe('warn');
    expect(managedKind('FRONTIER_LOCKED')).toBe('warn');
    expect(managedKind('CHEAP_ONLY')).toBe('warn');
    expect(managedKind('EXHAUSTED')).toBe('bad');
  });

  it('derives run usage from usage first and permissions fallback', () => {
    expect(
      billingUsageSummary(
        usage({totalRuns: 7, totalCost: 2.5}),
        {
          currentRunCount: 3,
          monthlyRunLimit: 30,
        },
        'PRO',
      ),
    ).toEqual({
      periodRuns: 7,
      runCap: 30,
      totalSpend: 2.5,
      planStatus: 'Subscription active',
    });

    expect(billingUsageSummary(null, {currentRunCount: 1, monthlyRunLimit: -1}, 'FREE')).toEqual({
      periodRuns: 1,
      runCap: 'AI credits',
      totalSpend: 0,
      planStatus: 'No subscription',
    });

    expect(billingUsageSummary(null, {currentRunCount: 4, monthlyRunLimit: -1}, 'TEAM')).toEqual({
      periodRuns: 4,
      runCap: '∞',
      totalSpend: 0,
      planStatus: 'Subscription active',
    });

    expect(
      billingUsageSummary(
        null,
        {
          currentRunCount: 2,
          monthlyRunLimit: -1,
          quotaModel: 'MANAGED_AI_CREDITS',
          includedAiCredits: 2000,
          unlimitedAiCredits: false,
        },
        'PRO',
      ),
    ).toEqual({
      periodRuns: 2,
      runCap: 'AI credits',
      totalSpend: 0,
      planStatus: 'Subscription active',
    });
  });

  it('normalizes invoice display fields', () => {
    expect(invoiceDisplay({id: 42, amount: 19, status: 'paid'})).toEqual({
      number: 'INV-42',
      amount: 'USD 19.00',
      paid: true,
      statusLabel: 'Paid',
    });
    expect(invoiceDisplay({number: 'N-1', currency: 'INR', status: 'created'})).toEqual({
      number: 'N-1',
      amount: '—',
      paid: false,
      statusLabel: 'created',
    });
  });

  it('groups active plan rows, prefers USD, computes yearly discount, and synthesizes bookends', () => {
    const cards = planCardsFrom([
      plan({tier: 'PRO', period: 'MONTHLY', baseAmountUsd: 2900}),
      plan({tier: 'PRO', period: 'ANNUAL', baseAmountUsd: 28900}),
      plan({tier: 'TEAM', period: 'MONTHLY', perSeatInr: 339900}),
      plan({tier: 'TEAM', period: 'MONTHLY', perSeatUsd: 3900}),
      plan({tier: 'HIDDEN', active: false, period: 'MONTHLY', baseAmountUsd: 9900}),
      plan({tier: 'EXPERIMENT', period: 'MONTHLY', baseAmountUsd: 1200}),
    ]);

    expect(cards.map((card) => card.tier)).toEqual([
      'FREE',
      'PRO',
      'TEAM',
      'ENTERPRISE',
      'EXPERIMENT',
    ]);
    expect(cards.find((card) => card.tier === 'PRO')).toMatchObject({
      monthly: 29,
      yearly: 289,
      yearlySavePct: 17,
      currency: 'USD',
      perSeat: false,
    });
    expect(cards.find((card) => card.tier === 'TEAM')).toMatchObject({
      monthly: 39,
      currency: 'USD',
      perSeat: true,
      recommended: true,
    });
    expect(cards.find((card) => card.tier === 'ENTERPRISE')?.monthly).toBeNull();
  });

  // RR-357: the previous test here PINNED the stale invented ladder (PRO $29/$289, TEAM
  // $39/seat, no BYOK) as expected behaviour, while V253's canonical rows are PRO $20,
  // PRO+ $100, BYOK $50 and TEAM $50/seat. Commerce now fails closed instead: when the
  // plans API cannot be trusted there are no prices to render and no checkout to enter.
  it('exposes no hardcoded saleable price ladder', async () => {
    const mod = (await import('./billingUtils')) as Record<string, unknown>;
    expect(mod.FALLBACK_PLAN_CARDS).toBeUndefined();
  });

  // planCardsFrom synthesizes the FREE and ENTERPRISE bookends unconditionally, so an empty
  // API response still produces two cards with no price. Callers must therefore detect the
  // pricing-unavailable state from the raw rows, not from the derived card count — IdeBilling
  // keys `pricingUnavailable` off `planRows.length === 0` for exactly this reason.
  it('yields only unpriced bookends for an empty plan list', () => {
    const cards = planCardsFrom([]);
    expect(cards.map((card) => card.tier)).toEqual(['FREE', 'ENTERPRISE']);
    expect(cards.every((card) => card.monthly == null || card.monthly === 0)).toBe(true);
    expect(cards.some((card) => (card.monthly ?? 0) > 0)).toBe(false);
  });
});

function plan(overrides: Partial<BillingPlan>): BillingPlan {
  return {
    id: `${overrides.tier ?? 'PRO'}-${overrides.period ?? 'MONTHLY'}`,
    name: overrides.tier ?? 'PRO',
    tier: 'PRO',
    keyMode: 'PLATFORM',
    period: 'MONTHLY',
    baseAmountInr: 0,
    baseAmountUsd: 0,
    perSeatInr: 0,
    perSeatUsd: 0,
    includedSeats: 1,
    runLimit: 30,
    annualDiscountPct: 0,
    founderPricing: false,
    active: true,
    limits: {},
    ...overrides,
  };
}

function usage(overrides: Partial<api.UsageMetrics>): api.UsageMetrics {
  return {
    totalRuns: 0,
    successfulRuns: 0,
    failedRuns: 0,
    partialRuns: 0,
    runsByDay: [],
    runsByProject: {},
    avgTimeToCompleteSeconds: 0,
    totalCost: 0,
    costByModel: {},
    ...overrides,
  };
}
