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
    // Audit r5 (P1-2, 2026-05-06) — synced with DB. V145 had bumped FREE
    // to 10/month, but V167 reverted to 5/month and the pricing copy
    // wasn't updated, leaving four user-facing surfaces (top-bar,
    // billing hero, settings tier banner, pricing card) claiming "10"
    // while the actual quota was 5. Cards now use the runtime
    // `V3Entitlements.runLimitMonthly` whenever a logged-in caller
    // passes one in via `pricingCardsWith()`; this default copy stays
    // accurate for anonymous /pricing viewers.
    subhead: '5 runs / month · no credit card',
    bullets: [
      '5 runs / month',
      '1 project · 1 repo',
      'All providers available',
      'Anonymous trial — no signup',
    ],
    ctaLabel: 'Try free →',
  },
  {
    // V176 (2026-05-06) renamed `tier_config.PRO` display_name "Pro" → "Solo"
    // and re-LISTED it as the bridge between FREE and TEAM at $29/mo with
    // 30 credits/month. The TierKey enum still says 'PRO' because the
    // database PK didn't change; the user-facing copy is "Solo". Audit r5
    // (P1-2, 2026-05-06): the frontend pricing.ts hadn't caught up to V176
    // — it still sold PRO as $20 / 20 credits / Grandfathered. Synced.
    tier: 'PRO',
    name: 'Solo',
    priceUsd: 29,
    priceInr: 2499,
    priceUnit: '/ month',
    badge: null,
    subhead: 'For solo developers — managed keys, plan & review gate',
    bullets: [
      '30 credits / month',
      '2 projects · 2 repos each',
      'All providers (BYOK option)',
      'Plan & review gate',
      'Priority email support',
    ],
    ctaLabel: 'Upgrade to Solo →',
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

/**
 * Audit r5 (P1-2, 2026-05-06) — overlay live entitlements onto the FREE
 * card so logged-in users always see their actual cap. The runtime
 * `V3Entitlements.runLimitMonthly` is fed from `tier_config`, so this
 * closes the "PRICING_CARDS drift" class of bugs without forcing every
 * caller to re-read from the API.
 *
 * Anonymous /pricing viewers (no entitlements) get the static default
 * copy, which we keep synced to the DB seed via PR review.
 */
export function pricingCardsWith(freeRunLimitMonthly: number | null | undefined): PricingCard[] {
  if (freeRunLimitMonthly == null || freeRunLimitMonthly < 0) return PRICING_CARDS;
  const n = freeRunLimitMonthly;
  return PRICING_CARDS.map((card) => {
    if (card.tier !== 'FREE') return card;
    return {
      ...card,
      subhead: `${n} runs / month · no credit card`,
      bullets: card.bullets.map((b) => (/^\d+ runs \/ month$/.test(b) ? `${n} runs / month` : b)),
    };
  });
}

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
