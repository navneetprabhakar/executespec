/**
 * Vitest-shape spec for {@link ./tierDisplay}. The frontend has no
 * test runner wired up at the moment; this file is shaped for vitest
 * (`describe` / `it` / `expect`) so that the moment a runner lands
 * (`pnpm vitest`) these checks start gating regressions.
 *
 * Until then, the same logic is also exercised live in the browser
 * preview during the audit run for Task 1 + Task 4 of the
 * 2026-05-01 fix list.
 */

// @ts-expect-error — test runner not yet installed; types resolve once vitest is added
import { describe, expect, it } from 'vitest';
import {
  MAX_DISPLAYABLE_BALANCE,
  canonicalDisplayName,
  canonicalTier,
  formatRunUsage,
  isPaidTier,
  normalizeTier,
} from './tierDisplay';
import type { V3Entitlements } from './api';

const baseEnt: V3Entitlements = {
  tierKey: 'FREE',
  displayName: 'Free',
  isUnlimited: false,
  runLimitMonthly: 10,
  projectsLimit: 1,
  storageLimitMb: -1,
  softCostBudgetUsd: 20,
  hardCostCapUsd: 30,
  currentPeriodRunCount: 0,
  currentPeriodCostUsd: null,
  billingCycleAnchor: null,
  lifetimeRunCreditBalance: 5,
  lifetimeRunCreditCap: 25,
};

describe('normalizeTier', () => {
  it('coerces lowercase and whitespace', () => {
    expect(normalizeTier(' pro ')).toBe('PRO');
  });
  it('collapses MAX → TEAM (V141 path)', () => {
    expect(normalizeTier('MAX')).toBe('TEAM');
  });
  it('defaults null/empty/unknown → FREE', () => {
    expect(normalizeTier(null)).toBe('FREE');
    expect(normalizeTier('')).toBe('FREE');
    expect(normalizeTier('garbage')).toBe('FREE');
  });
});

describe('canonicalTier', () => {
  it('prefers me.tier over ent.tierKey', () => {
    expect(canonicalTier('PRO', 'FREE')).toBe('PRO');
  });
  it('falls back to ent.tierKey when me.tier missing', () => {
    expect(canonicalTier(null, 'TEAM')).toBe('TEAM');
  });
  it('defaults FREE when both missing', () => {
    expect(canonicalTier(null, null)).toBe('FREE');
  });
});

describe('canonicalDisplayName', () => {
  it('formats each tier', () => {
    expect(canonicalDisplayName('FREE')).toBe('Free');
    expect(canonicalDisplayName('PRO')).toBe('Pro');
    expect(canonicalDisplayName('TEAM')).toBe('Team');
    expect(canonicalDisplayName('ENTERPRISE')).toBe('Enterprise');
  });
});

describe('isPaidTier', () => {
  it('FREE is unpaid', () => {
    expect(isPaidTier('FREE')).toBe(false);
  });
  it('PRO/TEAM/ENT are paid', () => {
    expect(isPaidTier('PRO')).toBe(true);
    expect(isPaidTier('TEAM')).toBe(true);
    expect(isPaidTier('ENTERPRISE')).toBe(true);
  });
});

describe('formatRunUsage', () => {
  it('FREE with sane monthly cap renders remaining over cap', () => {
    const out = formatRunUsage({ ...baseEnt, runLimitMonthly: 10, currentPeriodRunCount: 3 }, 'FREE');
    expect(out.line).toBe('7 of 10 runs left this month');
    expect(out.unlimited).toBe(false);
    expect(out.showBalanceBar).toBe(true);
  });

  it('FREE clamps used > cap (used at full)', () => {
    const out = formatRunUsage({ ...baseEnt, runLimitMonthly: 10, currentPeriodRunCount: 50 }, 'FREE');
    expect(out.line).toBe('0 of 10 runs left this month');
  });

  it('FREE renders Loading state when cap is 0', () => {
    const out = formatRunUsage({ ...baseEnt, runLimitMonthly: 0 }, 'FREE');
    expect(out.line).toBe('Loading run balance…');
    expect(out.showBalanceBar).toBe(false);
  });

  it('FREE cap = -1 (unlimited sentinel) renders Unlimited', () => {
    const out = formatRunUsage({ ...baseEnt, runLimitMonthly: -1 }, 'FREE');
    expect(out.line).toBe('Unlimited runs · 0 this period');
    expect(out.unlimited).toBe(true);
  });

  it('FREE cap at MAX_VALUE-shape is treated as unlimited (sentinel guard)', () => {
    const out = formatRunUsage(
      { ...baseEnt, runLimitMonthly: 2147483647 },
      'FREE'
    );
    expect(out.line).toBe('Unlimited runs · 0 this period');
    expect(out.unlimited).toBe(true);
    expect(out.showBalanceBar).toBe(false);
  });

  it('PRO renders Unlimited regardless of cap noise', () => {
    const out = formatRunUsage(baseEnt, 'PRO');
    expect(out.line).toBe('Unlimited runs · 0 this period');
    expect(out.unlimited).toBe(true);
    expect(out.showBalanceBar).toBe(false);
  });

  it('TEAM renders Unlimited even when ent payload says FREE', () => {
    const out = formatRunUsage(
      { ...baseEnt, tierKey: 'FREE', displayName: 'Free', runLimitMonthly: 5, lifetimeRunCreditBalance: 2147483647 },
      'TEAM'
    );
    expect(out.line).toBe('Unlimited runs · 0 this period');
    expect(out.unlimited).toBe(true);
  });

  it('ENTERPRISE renders Unlimited and includes period count', () => {
    const out = formatRunUsage({ ...baseEnt, currentPeriodRunCount: 42 }, 'ENTERPRISE');
    expect(out.line).toBe('Unlimited runs · 42 this period');
  });

  it('non-numeric currentPeriodRunCount is coerced to 0', () => {
    const ent = { ...baseEnt, currentPeriodRunCount: NaN as unknown as number };
    const out = formatRunUsage(ent, 'PRO');
    expect(out.line).toBe('Unlimited runs · 0 this period');
  });

  it('threshold sanity — cap just under MAX_DISPLAYABLE_BALANCE keeps fractional render', () => {
    const out = formatRunUsage(
      { ...baseEnt, runLimitMonthly: MAX_DISPLAYABLE_BALANCE - 1 },
      'FREE'
    );
    expect(out.unlimited).toBe(false);
    expect(out.showBalanceBar).toBe(true);
  });
});
