package com.navneet.executespec.analytics;

/**
 * Wave 2.1 — 6-event activation funnel.
 *
 * <p>Each event fires at most once per user (tracked via {@code user_activation_event} table). The
 * first-time firing is the signal used by {@code EventTrackingService.isFirstTime} — repeat actions
 * don't double-count in the headline funnel.
 *
 * <p>Full plan: {@code docs/plans/activation-instrumentation-plan.md}.
 *
 * <p>Event ordering (each is a "step" in the funnel):
 *
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
  PR_MERGED,

  // ── R-160 (v3) — greenfield-redesign conversion funnel ────────────────────
  // These mirror the v2 events for the same user actions but discriminate
  // the v3 surface so we can A/B the conversion lift cleanly. Each fires
  // at most once per user (same user_activation_event ledger as v2).

  /** Anonymous visitor submitted the homepage textarea (no signup yet). */
  V3_LANDING_TEXTAREA_SUBMIT,

  /** First v3 run reached PLANNING (signup boundary may have been crossed). */
  V3_FIRST_RUN_STARTED,

  /** First v3 run reached the "Ready to merge" verdict. */
  V3_FIRST_RUN_READY,

  /** Second-or-later run submitted in v3 (retention signal). */
  V3_REPEAT_RUN_SUBMITTED,

  /** Free user upgraded to a paid tier from a v3 nudge. */
  V3_TIER_UPGRADED,

  /** First teammate seat added under a v3 Team plan. */
  V3_TEAM_SEAT_ADDED,

  /** Enterprise inquiry submitted from /v3/trust or /v3/pricing. */
  V3_ENTERPRISE_INQUIRY_SUBMITTED,

  /** Trust Pack PDF downloaded from /v3/trust. */
  V3_TRUST_PAGE_PDF_DOWNLOADED
}
