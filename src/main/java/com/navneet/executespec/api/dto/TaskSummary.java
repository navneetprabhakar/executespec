package com.navneet.executespec.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record TaskSummary(
    String taskId,
    String title,
    String complexity,
    String status,
    String assignedModel,
    /** Requested/tier-resolved provider for this task. */
    String provider,
    /** Requested/tier-resolved model for this task. */
    String model,
    /** Physical provider proven by the successful-attempt ledger; null for MIXED/UNATTRIBUTED. */
    String effectiveProvider,
    /** Physical model proven by the successful-attempt ledger; null for MIXED/UNATTRIBUTED. */
    String effectiveModel,
    /** LEDGER, MIXED, or UNATTRIBUTED. */
    String effectiveRouteAttribution,
    /** True when the physical route differs from the requested task route. */
    Boolean routeDiverged,
    /** Persisted LIGHTWEIGHT, WORKHORSE, or PLANNING tier selected at dispatch. */
    String routingTier,
    /** Persisted semantic routing category such as GREENFIELD_BUILD or BUG_FIX. */
    String routingTaskCategory,
    /** Durable selection authority such as DEFAULT_MATRIX, ORG_OVERRIDE, or RUN_OVERRIDE. */
    String routingSource,
    String branchName,
    Boolean readmeUpdated,
    Long inputTokens,
    Long outputTokens,
    Long durationMs,
    Instant startedAt,
    Instant completedAt,
    /** Human-readable reason why this task is PARTIAL / BLOCKED / FAILED. */
    String failureReason,
    /** Structured goal-doneness decision from the executor task boundary. */
    JsonNode goalDoneness,
    /** Structured continuation / step-up / provider-change advice from the executor. */
    JsonNode continuationDecision,
    /** Durable history of continuation actions attempted or skipped for this task. */
    JsonNode continuationActions,
    /**
     * The objective/description from the planner's TaskDefinition. Shown in the UI when expanded.
     */
    String objective,
    /** The worker's summary of what was implemented. */
    String completionNotes,
    /** Parent task ID if this is a subtask (null for top-level tasks). */
    String parentTaskId,
    /** 1-based index within parent's subtask list (null for top-level tasks). */
    Integer subtaskIndex,
    /** Total number of subtasks in the parent (null for top-level tasks). */
    Integer subtaskTotal) {}
