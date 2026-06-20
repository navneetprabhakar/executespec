import type * as api from '@/lib/api';
import type {BillingPlan} from '@/lib/billing-api';

export type BadgeKind = 'ok' | 'warn' | 'bad';

export interface PaymentRowLite {
  id?: string | number;
  invoiceNumber?: string;
  number?: string;
  amount?: number;
  currency?: string;
  status?: string;
  gateway?: string;
  createdAt?: string;
  paidAt?: string;
}

export interface PlanCard {
  tier: string;
  monthly: number | null;
  yearly: number | null;
  yearlySavePct: number | null;
  currency: string;
  perSeat: boolean;
  recommended?: boolean;
}

export interface BillingUsageSummary {
  periodRuns: number;
  runCap: number | '∞';
  totalSpend: number;
  planStatus: string;
}

export interface InvoiceDisplay {
  number: string;
  amount: string;
  paid: boolean;
  statusLabel: string;
}

export const PLAN_FEATURES: Record<string, string[]> = {
  FREE: [
    '✓ 15 work items / month',
    '✓ Small specs (1 credit)',
    '✓ Email support',
    '○ $3 per-work cost cap',
    '○ 1 project · 1 repo',
  ],
  PRO: [
    '✓ 30 work items / month on managed keys',
    '✓ Anthropic · OpenAI · Gemini',
    '✓ PR review + code review (4 scans/mo)',
    '✓ 2 projects · 2 repos each',
    '○ $5 per-work cap — provider keys lift it',
  ],
  TEAM: [
    '✓ Unlimited work on your keys',
    '✓ No platform cost cap',
    '✓ Org spend caps + approval thresholds',
    '✓ 5 projects · cross-repo work',
    '✓ Unlimited code reviews + sharing',
    '✓ Autofix: PR review · tech debt · code scans',
  ],
  ENTERPRISE: [
    '✓ Unlimited work · no cost cap',
    '✓ Model matrix override + per-run model pins',
    '✓ OpenRouter gateway models',
    '✓ Standards · Contracts · ADRs',
    '✓ Governance: audit trail · repo allowlists',
    '✓ Dedicated support',
  ],
};

export const PLAN_SUBCOPY: Record<string, string> = {
  FREE: 'For solo developers · 15 work items/month · $3/work cap',
  PRO: 'For individuals · 30 work items/month on our keys · $5/work cap · provider keys lift the cap',
  TEAM: 'Unlimited work on your own keys · 1–20 seats · no per-work cap',
  ENTERPRISE: 'SSO · SLA · custom models · dedicated support',
};

const TIER_ORDER = ['FREE', 'PRO', 'TEAM', 'ENTERPRISE'];

export const FALLBACK_PLAN_CARDS: PlanCard[] = [
  {tier: 'FREE', monthly: 0, yearly: null, yearlySavePct: null, currency: 'USD', perSeat: false},
  {tier: 'PRO', monthly: 29, yearly: 289, yearlySavePct: 17, currency: 'USD', perSeat: false},
  {
    tier: 'TEAM',
    monthly: 39,
    yearly: null,
    yearlySavePct: null,
    currency: 'USD',
    perSeat: true,
    recommended: true,
  },
  {
    tier: 'ENTERPRISE',
    monthly: null,
    yearly: null,
    yearlySavePct: null,
    currency: 'USD',
    perSeat: false,
  },
];

export function money(n?: number | null): string {
  return n == null ? '—' : `$${n.toFixed(2)}`;
}

export function planMoney(amount: number | null, currency: string): string {
  if (amount == null) return 'Custom';
  if (amount === 0) return 'Free';
  const symbol = currency === 'USD' ? '$' : currency === 'INR' ? '₹' : `${currency} `;
  return `${symbol}${amount}`;
}

export function managedKind(state?: string | null): BadgeKind {
  if (state === 'EXHAUSTED') return 'bad';
  if (state === 'CHEAP_ONLY' || state === 'FRONTIER_LOCKED' || state === 'VALUE_MODE')
    return 'warn';
  return 'ok';
}

export function billingUsageSummary(
  usage: api.UsageMetrics | null | undefined,
  permissions:
    | {currentRunCount?: number | null; monthlyRunLimit?: number | null}
    | null
    | undefined,
  tier: string,
): BillingUsageSummary {
  const periodRuns = usage?.totalRuns ?? permissions?.currentRunCount ?? 0;
  const rawCap = permissions?.monthlyRunLimit ?? -1;
  return {
    periodRuns,
    runCap: rawCap && rawCap !== -1 ? rawCap : '∞',
    totalSpend: usage?.totalCost ?? 0,
    planStatus: tier === 'FREE' ? 'No subscription' : 'Subscription active',
  };
}

export function invoiceDisplay(row: PaymentRowLite): InvoiceDisplay {
  const number = row.invoiceNumber || row.number || (row.id != null ? `INV-${row.id}` : 'INV-—');
  const amount = row.amount != null ? `${row.currency || 'USD'} ${row.amount.toFixed(2)}` : '—';
  const paid = (row.status || '').toLowerCase() === 'paid' || !!row.paidAt;
  return {
    number,
    amount,
    paid,
    statusLabel: paid ? 'Paid' : row.status || 'pending',
  };
}

export function planCardsFrom(rows: BillingPlan[]): PlanCard[] {
  interface Bucket {
    monthly: number | null;
    yearly: number | null;
    savePct: number | null;
    monthlyPerSeat: boolean | null;
    yearlyPerSeat: boolean | null;
  }
  const buckets = new Map<string, Bucket>();
  const apiTierOrder: string[] = [];

  for (const row of rows) {
    if (!row || row.active === false) continue;
    const tier = (row.tier || '').toUpperCase();
    if (!tier) continue;
    const seatUsd = row.perSeatUsd ?? 0;
    const baseUsd = row.baseAmountUsd ?? 0;
    const seatInr = row.perSeatInr ?? 0;
    const baseInr = row.baseAmountInr ?? 0;
    let currency: 'USD' | 'INR';
    let perSeat: boolean;
    let amount: number;
    if (seatUsd > 0 || baseUsd > 0) {
      currency = 'USD';
      perSeat = seatUsd > 0;
      amount = (perSeat ? seatUsd : baseUsd) / 100;
    } else if (seatInr > 0 || baseInr > 0) {
      currency = 'INR';
      perSeat = seatInr > 0;
      amount = (perSeat ? seatInr : baseInr) / 100;
    } else {
      continue;
    }
    const period = (row.period || '').toUpperCase();
    const isYearly = period === 'YEARLY' || period === 'ANNUAL';
    if (!isYearly && period !== 'MONTHLY') continue;
    const key = `${tier}:${currency}`;
    let bucket = buckets.get(key);
    if (!bucket) {
      bucket = {
        monthly: null,
        yearly: null,
        savePct: null,
        monthlyPerSeat: null,
        yearlyPerSeat: null,
      };
      buckets.set(key, bucket);
    }
    if (isYearly) {
      if (bucket.yearly == null) {
        bucket.yearly = amount;
        bucket.yearlyPerSeat = perSeat;
        const pct = row.annualDiscountPct ?? 0;
        if (pct > 0) bucket.savePct = pct;
      }
    } else if (bucket.monthly == null) {
      bucket.monthly = amount;
      bucket.monthlyPerSeat = perSeat;
    }
    if (!apiTierOrder.includes(tier)) apiTierOrder.push(tier);
  }

  const tiers = [...TIER_ORDER, ...apiTierOrder.filter((tier) => !TIER_ORDER.includes(tier))];
  const cards: PlanCard[] = [];
  for (const tier of tiers) {
    const usd = buckets.get(`${tier}:USD`);
    const bucket = usd ?? buckets.get(`${tier}:INR`);
    if (bucket) {
      let savePct = bucket.savePct;
      if (
        savePct == null &&
        bucket.monthly != null &&
        bucket.monthly > 0 &&
        bucket.yearly != null
      ) {
        const computed = Math.round((1 - bucket.yearly / (bucket.monthly * 12)) * 100);
        if (computed > 0) savePct = computed;
      }
      cards.push({
        tier,
        monthly: bucket.monthly,
        yearly: bucket.yearly,
        yearlySavePct: savePct,
        currency: usd ? 'USD' : 'INR',
        perSeat: bucket.monthlyPerSeat ?? bucket.yearlyPerSeat ?? false,
        recommended: tier === 'TEAM',
      });
    } else if (tier === 'FREE') {
      cards.push({
        tier,
        monthly: 0,
        yearly: null,
        yearlySavePct: null,
        currency: 'USD',
        perSeat: false,
      });
    } else if (tier === 'ENTERPRISE') {
      cards.push({
        tier,
        monthly: null,
        yearly: null,
        yearlySavePct: null,
        currency: 'USD',
        perSeat: false,
      });
    }
  }
  return cards;
}
