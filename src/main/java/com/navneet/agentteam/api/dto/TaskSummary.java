package com.navneet.agentteam.api.dto;

import java.time.Instant;

public record TaskSummary(
    String taskId,
    String title,
    String complexity,
    String status,
    String assignedModel,
    String branchName,
    Boolean readmeUpdated,
    Long inputTokens,
    Long outputTokens,
    Long durationMs,
    Instant startedAt,
    Instant completedAt,
    /** Human-readable reason why this task is PARTIAL / BLOCKED / FAILED. */
    String failureReason
) {}
