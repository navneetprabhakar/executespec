package com.navneet.executespec.onboarding;

/**
 * Wave 2.2 — one of the 3 canned sample specs available on /welcome.
 *
 * <p>Loaded from {@code src/main/resources/sample-specs/*.yml} at startup. Fields map 1:1 to the
 * YAML schema; see {@code ttfv-reduction-plan.md §3} for the category → projectType → provider
 * mapping.
 */
public record SampleSpec(
    String id,
    String title,
    String category,
    String projectType,
    String preferredProvider,
    String complexity,
    String spec) {}
