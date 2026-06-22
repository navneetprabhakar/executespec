package com.navneet.executespec.entitlements;

/**
 * R-142 — single-source entitlements payload for v3 surfaces.
 *
 * <p>Rolls up tier + plan + caps + period usage into one read so v3 billing, pricing, composer
 * gating, and run-cap nudges all consult the same source. The strict-review case (org tier
 * ENTERPRISE showing "Free / 286% over budget") cannot recur with this contract.
 *
 * <p>`isUnlimited` is the headline flag — when true, v3 UIs hide the usage bar / soft-budget banner
 * entirely. `lifetimeRunCreditBalance` is meaningful only for tier=FREE; paid tiers always return
 * -1.
 */
public record EntitlementsResponse(
    String tierKey, // FREE / PRO / TEAM / ENTERPRISE
    String displayName, // "Free" / "Pro" / "Team" / "Enterprise"
    boolean isUnlimited, // true for PRO/TEAM/ENTERPRISE
    int runLimitMonthly, // -1 = unlimited
    int projectsLimit, // -1 = unlimited
    long storageLimitMb, // -1 = unlimited
    Double softCostBudgetUsd, // null for unlimited tiers
    Double hardCostCapUsd, // null for unlimited tiers
    int currentPeriodRunCount,
    Double currentPeriodCostUsd,
    String billingCycleAnchor, // ISO instant or null
    int lifetimeRunCreditBalance, // -1 for paid tiers
    int lifetimeRunCreditCap, // -1 for paid tiers
    // Run-count retirement (2026-06-22): managed ES AI credit gate — the v3 spend authority.
    // `runLimitMonthly` above is retained for back-compat but advisory only; spend is gated here.
    boolean managedAiGated, // true for FREE/PRO; false for TEAM/ENTERPRISE (own-keys / unlimited)
    long managedAiCreditsIncluded, // ES AI credit grant this period; -1 when ungated
    long managedAiCreditsUsed,
    long managedAiCreditsRemaining, // -1 when ungated
    String managedAiRoutingState // FULL / VALUE_MODE / FRONTIER_LOCKED / CHEAP_ONLY / EXHAUSTED
    ) {}
