package com.navneet.executespec.v3.entitlements;

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
    int lifetimeRunCreditCap // -1 for paid tiers
    ) {}
