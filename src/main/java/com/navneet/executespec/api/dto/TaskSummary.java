package com.navneet.executespec.api.dto;

import java.time.Instant;

public record TaskSummary(
    String taskId,
    String title,
    String complexity,
    String status,
    String assignedModel,
    /** Actual AI provider used for this task (e.g. "anthropic", "openai"). Null if not yet run. */
    String provider,
    /** Actual model used for this task (e.g. "claude-sonnet-4-5"). Null if not yet run. */
    String model,
    String branchName,
    Boolean readmeUpdated,
    Long inputTokens,
    Long outputTokens,
    Long durationMs,
    Instant startedAt,
    Instant completedAt,
    /** Human-readable reason why this task is PARTIAL / BLOCKED / FAILED. */
    String failureReason,
    /** The objective/description from the planner's TaskDefinition. Shown in the UI when expanded. */
    String objective
) {}
