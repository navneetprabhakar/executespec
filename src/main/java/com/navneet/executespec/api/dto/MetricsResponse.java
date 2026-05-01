package com.navneet.executespec.api.dto;

public record MetricsResponse(
    long totalRuns,
    long completedRuns,
    long failedRuns,
    long activeRuns,
    long queuedRuns,
    long totalInputTokens,
    long totalOutputTokens) {}
