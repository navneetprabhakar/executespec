/**
 * R-141 / R-150 — single source of v3 pricing copy.
 *
 * Defaults applied per D-141a/b (greenfield §7.1):
 *   Pro  = $20/mo  (matches Cursor anchor; no psychological cents)
 *   Team = $39/seat/mo, min 2 seats
 *   Enterprise = Talk to sales (footer-only, no card on the grid)
 *
 * Currency: USD by default; INR surfaces when geo-detect matches
 * Asia/Kolkata or navigator.language starts with `en-in` / `hi`.
 *
 * Both /billing and /pricing read from this module so the
 * customer-facing prices and the in-app upsell stay in lockstep.
 */

export type TierKey = 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';

export interface PricingCard {
  tier: TierKey;
  name: string;
  /** Monthly USD; null for FREE / ENTERPRISE. */
  priceUsd: number | null;
  /** Monthly INR; null when not localised. */
  priceInr: number | null;
  /** "/mo total" / "/seat/mo" / "" — copy that follows the price. */
  priceUnit: string;
  /** Tag shown above price (e.g. "Most popular"). null when none. */
  badge: string | null;
  /** One-line subhead under the tier name. */
  subhead: string;
  /** Bullet list of inclusions — keep tight (4-7 bullets). */
  bullets: string[];
  /** Primary CTA copy. */
  ctaLabel: string;
}

export const PRICING_CARDS: PricingCard[] = [
  {
    tier: 'FREE',
    name: 'Free',
    priceUsd: 0,
    priceInr: 0,
    priceUnit: '',
    badge: null,
    subhead: '5 lifetime runs · invite friends for +5 each',
    bullets: [
      '5 lifetime runs',
      '+5 per friend (cap 25)',
      '1 project · 1 repo',
      'All providers available',
      'Anonymous trial — no signup',
    ],
    ctaLabel: 'Try free →',
  },
  {
    tier: 'PRO',
    name: 'Pro',
    priceUsd: 20,
    priceInr: 1700,
    priceUnit: '/ month',
    // [F-28] (audit Sprint 1 PR-4, 2026-04-30) — PRO is HIDDEN per
    // `tier_config.PRO.lifecycle_state = 'HIDDEN'` (v2.6.1 Wave 1).
    // New buyers should choose TEAM or MAX (latter is power-individual
    // BYOK at $29). PRO continues to bill for grandfathered subscribers
    // only — the card stays visible so existing users recognise their
    // plan, but the badge + subhead now name the grandfathered status
    // explicitly instead of selling PRO as "Most popular".
    badge: 'Grandfathered',
    subhead: 'Existing subscribers only — new buyers, choose TEAM or MAX',
    bullets: [
      'Unlimited runs / month',
      'All providers (BYOK option)',
      'Unlimited projects + repos',
      'Plan & review gate',
      'Priority email support',
    ],
    ctaLabel: 'Manage subscription →',
  },
  {
    tier: 'TEAM',
    name: 'Team',
    priceUsd: 39,
    priceInr: 3300,
    priceUnit: '/ seat / month',
    badge: null,
    subhead: 'For 2-20 engineers · shared run pool',
    bullets: [
      'Everything in Pro',
      'Org SSO (SAML / OIDC)',
      'Audit log export',
      'Activity feed',
      'Shared spec templates',
      'Min 2 seats · max 20',
    ],
    ctaLabel: 'Add seats →',
  },
];

/** Enterprise sits below the grid; not a card. */
export const ENTERPRISE_FOOTER = {
  headline: 'Need more? Talk to sales for Enterprise (SSO + SOC 2 + DPA).',
  ctaLabel: 'Talk to sales →',
  ctaHref: '/trust',
};

/** Yearly pricing — straight 17% discount per greenfield §7. */
export const ANNUAL_DISCOUNT_PCT = 17;

export function annualPriceUsd(monthly: number | null): number | null {
  if (monthly == null) return null;
  return Math.round(monthly * 12 * (1 - ANNUAL_DISCOUNT_PCT / 100));
}

/** Format like "$20" / "₹1,700". Returns "—" for null. */
export function formatPrice(amount: number | null, currency: 'USD' | 'INR'): string {
  if (amount == null) return '—';
  if (amount === 0) return currency === 'USD' ? '$0' : '₹0';
  if (currency === 'USD') return `$${amount}`;
  // INR with thousands separators
  return '₹' + new Intl.NumberFormat('en-IN').format(amount);
}

/** D-141b — geo-detect preferred currency. SSR-safe (returns USD when window absent). */
export function detectDefaultCurrency(): 'USD' | 'INR' {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return 'USD';
  try {
    const tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (tz === 'Asia/Kolkata') return 'INR';
  } catch {
    /* ignore */
  }
  const lang = (navigator.language || '').toLowerCase();
  if (lang.startsWith('en-in') || lang.startsWith('hi')) return 'INR';
  return 'USD';
}
