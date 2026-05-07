package com.navneet.executespec.landing;

/**
 * Selling-point #7 — sample-run gallery card for the landing page.
 *
 * <p>Anonymized — no user / org / project names leak. Just the spec text (truncated to 90 chars),
 * the verdict, the verified score, the file count, and the duration. Returned by {@code GET
 * /api/v3/landing/sample-runs}.
 *
 * @param spec truncated user requirement text
 * @param verdict "READY" / "NEEDS_REVIEW" / "FAILED"
 * @param verifiedScore 0-100 (null when not yet measured / older row)
 * @param filesGenerated number of files the run produced
 * @param durationMs start → terminal ms (null when not yet completed)
 * @param prOpened true if a GitHub PR url is set on the run
 */
public record SampleRun(
    String spec,
    String verdict,
    Integer verifiedScore,
    Integer filesGenerated,
    Long durationMs,
    boolean prOpened) {}
