package com.navneet.executespec.billing.lifecycle;

/**
 * v2.6.0 — tier / plan lifecycle state. Applied independently to
 * {@code tier_config.lifecycle_state} and {@code billing_plan.lifecycle_state}.
 *
 * <p>Public-surface resolution rule: a plan is shown to new buyers when BOTH
 * the plan row AND its owning tier row are {@code LISTED}. Hide either one,
 * the plan disappears from {@code GET /api/billing/plans} (unless the caller
 * is SUPER_ADMIN and passes {@code ?includeHidden=true}).
 *
 * <p>State-transition rules are owned by {@link TierLifecycleService}; this
 * enum only names the states. Full design doc at
 * {@code docs/plans/tier-lifecycle-plan.md}.
 */
public enum LifecycleState {

    /**
     * Visible on every pricing surface, accepting new signups. Default for
     * every newly inserted row.
     */
    LISTED,

    /**
     * Hidden from {@code GET /api/billing/plans} for non-admin callers and
     * from PlansPage / UpgradeModal / PricingPage. Existing subscriptions on
     * this tier/plan continue to renew untouched.
     */
    HIDDEN,

    /**
     * All {@code HIDDEN} behaviour PLUS renewal cancellation has been queued
     * on the payment gateway for every active subscription on this tier/plan.
     * Existing subscribers keep access until their current period ends, then
     * auto-downgrade to FREE via the existing
     * {@code PeriodEndDowngradeScheduler}.
     */
    SUNSETTING;

    /**
     * Parse a DB string to an enum, returning {@link #LISTED} if the input is
     * null or unknown (defensive — the DB CHECK constraint should prevent
     * any other value reaching Java, but we never crash on stray data).
     */
    public static LifecycleState fromDbValue(String s) {
        if (s == null) return LISTED;
        try {
            return valueOf(s);
        } catch (IllegalArgumentException e) {
            return LISTED;
        }
    }
}
