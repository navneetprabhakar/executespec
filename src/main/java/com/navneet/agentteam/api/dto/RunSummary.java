package com.navneet.agentteam.api.dto;

import java.time.Instant;

public record RunSummary(
    String runId,
    String status,
    String userRequest,
    String currentGate,
    Integer queuePosition,
    String plannerModel,
    String workerModel,
    String reviewerModel,
    Integer totalInputTokens,
    Integer totalOutputTokens,
    Instant startedAt,
    Instant completedAt,
    String repoUrl,
    String repoMode
) {}
