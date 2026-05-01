/**
 * Tier display helpers — Task 1 + Task 4 of the 2026-05-01 fix list.
 *
 * Two responsibilities:
 *
 *   1. Resolve the **canonical** tier for the current user. The
 *      `/api/v3/entitlements` endpoint can return `tierKey: "FREE"`
 *      for users whose backing org has no real subscription record
 *      (test fixtures, bootstrapped personal orgs). The canonical
 *      tier source-of-truth is `me.tier` from `/api/auth/me`. When
 *      the two disagree, prefer `me.tier`.
 *
 *   2. Format the "X of Y runs left" / "Unlimited" line defensively.
 *      The backend's `LifetimeCreditService.currentBalance()` returns
 *      `Integer.MAX_VALUE` (2^31 − 1 = 2147483647) for paid tiers as
 *      an internal "treat as unlimited" sentinel. That sentinel was
 *      never meant to leave the API. The formatter below treats any
 *      balance ≥ {@link MAX_DISPLAYABLE_BALANCE} as unlimited so the
 *      raw integer can never reach the user.
 */

import type { V3Entitlements } from './api';

/** Anything at or above this threshold is treated as the
 * "unlimited sentinel" leaking through. 1M runs would already be
 * absurd; Integer.MAX_VALUE (2147483647) is well above this. */
export const MAX_DISPLAYABLE_BALANCE = 1_000_000;

export type CanonicalTier = 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';

const TIER_DISPLAY_NAMES: Record<CanonicalTier, string> = {
  FREE: 'Free',
  PRO: 'Pro',
  TEAM: 'Team',
  ENTERPRISE: 'Enterprise',
};

/** Coerce arbitrary tier strings (uppercase, lowercase, legacy "MAX")
 * into one of the four canonical tier keys. Defaults to FREE. */
export function normalizeTier(raw: string | null | undefined): CanonicalTier {
  if (!raw) return 'FREE';
  const upper = raw.trim().toUpperCase();
  if (upper === 'MAX') return 'TEAM'; // V141: MAX collapsed to TEAM
  if (upper === 'PRO' || upper === 'TEAM' || upper === 'ENTERPRISE') return upper;
  return 'FREE';
}

/** Canonical tier source-of-truth: prefer `me.tier`, fall back to
 * `entitlements.tierKey`, default to FREE. */
export function canonicalTier(
  meTier: string | null | undefined,
  entTierKey: string | null | undefined
): CanonicalTier {
  return normalizeTier(meTier ?? entTierKey);
}

/** Display name for a canonical tier — "Free / Pro / Team /
 * Enterprise". Use this for the hero "You're on X." line and for
 * the tier pill, instead of `entitlements.displayName` which can
 * lag behind the user's actual tier. */
export function canonicalDisplayName(tier: CanonicalTier): string {
  return TIER_DISPLAY_NAMES[tier];
}

/** True iff the canonical tier is anything paid. Used to decide
 * whether to show the lifetime-balance progress bar / referral
 * prompt (FREE only) and whether the run line should be
 * "Unlimited" vs a fraction. */
export function isPaidTier(tier: CanonicalTier): boolean {
  return tier !== 'FREE';
}

export interface RunUsageDisplay {
  /** The line rendered under the "You're on X." headline. */
  line: string;
  /** Whether the formatter decided this is an unlimited tier (drives
   * pill tone + balance-bar visibility). */
  unlimited: boolean;
  /** Whether to show the lifetime-balance progress bar (FREE-only,
   * with sane balance/cap). */
  showBalanceBar: boolean;
}

/**
 * Compute the run-usage line + visibility flags for the billing
 * hero. Defensive against backend sentinels: if the monthly cap
 * comes back at MAX_VALUE-shape, treat as unlimited and skip the
 * math entirely.
 *
 * For FREE the canonical run-limit source is
 * {@link V3Entitlements#runLimitMonthly} (driven by
 * {@code tier_config.monthly_run_limit}; V145 raised this to 10).
 * The legacy lifetime-credit balance (`lifetimeRunCreditBalance` /
 * `lifetimeRunCreditCap`) is still tracked server-side as a
 * separate gate but is no longer surfaced in the hero — Task 3
 * (2026-05-01 fix list) collapsed all FREE tier surfaces onto the
 * monthly model so the four places a FREE user sees their cap
 * (top-bar badge, billing hero, pricing card, sidebar) agree on
 * one number.
 */
export function formatRunUsage(
  ent: V3Entitlements,
  tier: CanonicalTier
): RunUsageDisplay {
  const periodRuns = Number.isFinite(ent.currentPeriodRunCount)
    ? ent.currentPeriodRunCount
    : 0;

  // F-21 (round-3 audit follow-on): trust `runLimitMonthly` as the
  // source of truth for paid tiers too. The previous code hardcoded
  // "Unlimited" for any paid tier, which was correct as long as the
  // F-14 resolver bug kept everyone falling back to FREE. After F-14
  // PRO users correctly resolve to PRO — but PRO has a 20/month cap
  // per `tier_config.monthly_run_limit`, so the hardcode would have
  // shown "Unlimited runs" to a tier that bills at 20/month. Read
  // the cap once and branch on the sentinel below for both paid and
  // free tiers.
  const rawCap = ent.runLimitMonthly;

  // Unlimited path — `-1` is the canonical sentinel; anything at or
  // above MAX_DISPLAYABLE_BALANCE means the unlimited marker leaked
  // through. Both render the same way regardless of the tier.
  if (rawCap >= MAX_DISPLAYABLE_BALANCE || rawCap === -1) {
    return {
      line: `Unlimited runs · ${periodRuns} this period`,
      unlimited: true,
      showBalanceBar: false,
    };
  }

  // Cap not yet resolved (0 / null shape) — render a benign
  // loading-style line and skip the bar.
  if (!rawCap || rawCap <= 0) {
    return {
      line: 'Loading run balance…',
      unlimited: false,
      showBalanceBar: false,
    };
  }

  const used = Math.max(0, Math.min(periodRuns, rawCap));
  const remaining = Math.max(0, rawCap - used);
  // showBalanceBar stays FREE-only — paid tiers shouldn't surface the
  // lifetime-credit affordance even when the monthly cap is finite.
  return {
    line: `${remaining} of ${rawCap} runs left this month`,
    unlimited: false,
    showBalanceBar: !isPaidTier(tier),
  };
}

/** True iff the lifetime balance bar should render — FREE only,
 * with a sane balance and cap. Wraps {@link formatRunUsage} for
 * call sites that just need the boolean. */
export function shouldShowBalanceBar(
  ent: V3Entitlements,
  tier: CanonicalTier
): boolean {
  return formatRunUsage(ent, tier).showBalanceBar;
}
