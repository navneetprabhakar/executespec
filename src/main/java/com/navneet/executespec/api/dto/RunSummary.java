package com.navneet.executespec.api.dto;

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
    String keySource,
    /** Comma-separated tech stack IDs derived from the plan (e.g. "react,typescript,nodejs"). Null until planning completes. */
    String techStack,
    /** Human-readable project name. Null if projectId is null or project was deleted. */
    String projectName,
    /** Display name of the user who created this run (e.g. "John Doe"). Null for pre-ownership runs. */
    String createdByName
) {}
