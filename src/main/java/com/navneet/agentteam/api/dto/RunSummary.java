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
    String repoMode,
    /** Project this run belongs to, or null for un-grouped runs. */
    String projectId,
    /** Billing flag: SYSTEM | OWN_KEYS | HYBRID. Null for pre-migration runs. */
    String keySource
) {}
