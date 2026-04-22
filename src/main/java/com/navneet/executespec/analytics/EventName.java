package com.navneet.executespec.analytics;

/**
 * Wave 2.1 — 6-event activation funnel.
 *
 * <p>Each event fires at most once per user (tracked via
 * {@code user_activation_event} table). The first-time firing is the signal
 * used by {@code EventTrackingService.isFirstTime} — repeat actions don't
 * double-count in the headline funnel.
 *
 * <p>Full plan: {@code docs/plans/activation-instrumentation-plan.md}.
 *
 * <p>Event ordering (each is a "step" in the funnel):
 * <pre>
 *   SIGNUP → PROJECT_CREATED → SPEC_SUBMITTED → PLAN_APPROVED → RUN_COMPLETE → PR_MERGED
 * </pre>
 */
public enum EventName {

    /** User registered (email/password or OAuth new-user branch). */
    SIGNUP,

    /** User created their first project. */
    PROJECT_CREATED,

    /** User submitted their first run (POST /api/runs returned 202). */
    SPEC_SUBMITTED,

    /** User approved their first plan gate (crosses the "I'm willing to spend a credit" bar). */
    PLAN_APPROVED,

    /** User's first run reached a terminal status (COMPLETE or PARTIAL). */
    RUN_COMPLETE,

    /** First PR originating from the user's run was merged on GitHub. */
    PR_MERGED
}
