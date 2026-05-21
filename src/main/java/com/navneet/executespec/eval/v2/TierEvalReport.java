package com.navneet.executespec.eval.v2;

import java.time.Instant;
import java.util.List;

import lombok.Builder;

/**
 * Aggregate report for one tier-eval-v2 run. Persisted as JSON via the controller and rendered to
 * markdown by the bash wrapper. The shape is intentionally flat — `results` is the full grid, the
 * caller pivots however it likes.
 */
@Builder
public record TierEvalReport(
    String evaluationId, Instant startedAt, long durationMs, List<TierEvalResult> results) {}
