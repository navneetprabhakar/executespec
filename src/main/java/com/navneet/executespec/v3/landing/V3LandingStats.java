package com.navneet.executespec.v3.landing;

/**
 * Selling-point #2 — landing-page social-proof counter.
 *
 * Returned by {@code GET /api/v3/landing/stats}. Public (no auth) so the
 * marketing surface can render it pre-signup. All numbers are aggregates
 * across the platform (no per-org / per-user leakage).
 *
 * @param specsThisWeek number of completed runs in the last 7 days (any verdict)
 * @param verifiedScorePercent share of those runs with verifiedScore >= 80
 *                              (as a 0-100 integer); null if no qualifying runs
 * @param totalRunsAllTime cumulative completed-run count across the platform
 */
public record V3LandingStats(
        long specsThisWeek,
        Integer verifiedScorePercent,
        long totalRunsAllTime
) {}
