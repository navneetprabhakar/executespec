import {describe, expect, it} from 'vitest';

import {
  billingUsageSummary,
  invoiceDisplay,
  managedKind,
  money,
  paymentAuditToRow,
  planCardsFrom,
  planMoney,
} from './billingUtils';
import type * as api from '@/lib/api';
import type {BillingPlan} from '@/lib/api/billing';

describe('RR-359 — payment audit rows map to invoice display', () => {
  it('converts minor units, provider and captured event into a Paid invoice, not amount — / Pending', () => {
    const row = paymentAuditToRow({
      id: 'p1',
      amountMinor: 42500,
      currency: 'INR',
      gatewayProvider: 'razorpay',
      gatewayPaymentId: 'pay_abc',
      eventType: 'PAYMENT_CAPTURED',
      createdAt: '2026-07-01T00:00:00Z',
    });
    expect(row.amount).toBe(425);
    expect(row.gateway).toBe('razorpay');
    expect(row.paidAt).toBe('2026-07-01T00:00:00Z');
    const display = invoiceDisplay(row);
    expect(display.amount).toBe('INR 425.00');
    expect(display.paid).toBe(true);
    expect(display.number).toBe('pay_abc');
  });

  it('leaves a non-captured lifecycle event unpaid but still shows the real amount', () => {
    const row = paymentAuditToRow({
      id: 'p2',
      amountMinor: 2000,
      currency: 'USD',
      gatewayProvider: 'stripe',
      eventType: 'PAYMENT_FAILED',
      createdAt: '2026-07-02T00:00:00Z',
    });
    expect(row.amount).toBe(20);
    expect(row.paidAt).toBeUndefined();
    expect(invoiceDisplay(row).paid).toBe(false);
    expect(invoiceDisplay(row).amount).toBe('USD 20.00');
  });

  it('renders a null amount as — rather than crashing', () => {
    const row = paymentAuditToRow({id: 'p3', amountMinor: null, eventType: 'SUBSCRIPTION_CREATED'});
    expect(row.amount).toBeUndefined();
    expect(invoiceDisplay(row).amount).toBe('—');
  });
});

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
