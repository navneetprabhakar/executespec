package com.navneet.executespec.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Full detail DTO for a single pipeline run, including task list and review data.
 *
 * <p>Converted from a record to a {@code @Builder} class to support Tier 8's
 * optional {@code hasFrontend}, {@code hasApiSpec}, and {@code previewStatus} fields
 * without requiring every constructor callsite to pass them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunDetail {

    String runId;
    String status;
    String userRequest;
    String currentGate;
    Integer queuePosition;
    String plannerModel;
    String workerModel;
    String reviewerModel;
    Integer totalInputTokens;
    Integer totalOutputTokens;
    Instant startedAt;
    Instant completedAt;
    String repoUrl;
    String repoMode;
    String failureReason;
    String planJson;
    String reviewJson;
    List<TaskSummary> tasks;
    IntegrationSource integrationSource;   // null for manual runs

    // ── Tier 8: Compile, API spec, preview ───────────────────────────────────
    Boolean hasFrontend;     // true once PreviewService builds the frontend
    Boolean hasApiSpec;      // true once ApiTestSuiteAgent writes openapi.yaml
    String  previewStatus;   // null | "READY" | "FAILED"

    /** Billing flag: SYSTEM | OWN_KEYS | HYBRID. Null for pre-migration runs. */
    String keySource;

    /** Actual cost in USD computed after run completion. Null until complete. */
    Double actualCostUsd;

    /** Comma-separated tech stack IDs derived from the plan (e.g. "react,typescript,nodejs"). */
    String techStack;

    /** Human-readable project name. Null if projectId is null or project was deleted. */
    String projectName;

    // ── Per-agent token breakdown (V19) ─────────────────────────────────────
    /** Token usage + actual provider/model for pipeline agents (planner, reviewer, compiler). */
    List<AgentTokenEntry> agentTokens;

    /** Present if this run was triggered by an external integration. */
    public record IntegrationSource(String sourceType, String sourceId, String sourceUrl) {}

    /** Token usage for a non-task agent (planner, reviewer, compiler). */
    public record AgentTokenEntry(String role, String provider, String model,
                                  int inputTokens, int outputTokens) {}
}
