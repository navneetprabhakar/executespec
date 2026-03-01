package com.navneet.agentteam.state.entity;

import com.navneet.agentteam.tenant.TenantInterceptor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "pipeline_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRun {

    @Id
    private String runId;               // UUID, set by harness

    @Column(columnDefinition = "TEXT")
    private String userRequest;         // original natural language request

    private String status;              // PLANNING | AWAITING_APPROVAL | WORKING |
                                        // REVIEWING | AWAITING_MERGE | COMPLETE | FAILED

    @Column(columnDefinition = "TEXT")
    private String planJson;            // full TaskPlan JSON from planner

    @Column(columnDefinition = "TEXT")
    private String reviewJson;          // ReviewReport JSON

    private String plannerModel;
    private String workerModel;
    private String reviewerModel;

    private String sourceType;          // MANUAL | JIRA | CONFLUENCE | SERVICENOW | PAGERDUTY
    private String sourceId;            // e.g. "PROJ-1234" for Jira (null for manual)

    private Instant startedAt;
    private Instant completedAt;

    private Integer totalInputTokens;   // accumulated across all agents
    private Integer totalOutputTokens;

    @Column(columnDefinition = "TEXT")
    private String failureReason;       // set on FAILED state

    // ── Tier 3 fields ─────────────────────────────────────────────────────────
    private String repoUrl;             // HTTPS clone URL (null if no repo)
    private String repoMode;            // "NEW" | "EXISTING" | null
    private String workspacePath;       // per-run path: ./workspace/projects/{runId}/
    private Integer queuePosition;      // 0 if running/done, >0 if queued
    private String currentGate;         // "PLAN_APPROVAL" | "MERGE_APPROVAL" | null

    // ── Tier 7 fields ─────────────────────────────────────────────────────────
    @Builder.Default
    @Column(columnDefinition = "boolean default false")
    private boolean skipApproval = false; // true = auto-approve plan gate (integration-triggered runs)

    // ── Auto-restart tracking ─────────────────────────────────────────────────
    /**
     * How many times this run has been auto-restarted due to transient failures
     * (rate-limiting, provider unavailability, etc.).
     * Capped at {@code agent.recovery.max-auto-restarts} to prevent infinite loops.
     */
    @Builder.Default
    @Column(columnDefinition = "integer default 0")
    private int autoRestartCount = 0;

    // ── Tier 8: Compile, API spec, frontend preview ───────────────────────────

    /** True once PreviewService successfully builds and serves the frontend. */
    @Builder.Default
    @Column(columnDefinition = "boolean default false")
    private boolean hasFrontend = false;

    /** True once ApiTestSuiteAgent writes api-docs/openapi.yaml to the workspace. */
    @Builder.Default
    @Column(columnDefinition = "boolean default false")
    private boolean hasApiSpec = false;

    /** Preview build status: null | "READY" | "FAILED" */
    private String previewStatus;

    /** Workspace-relative directory from which static files are served, e.g. "dist" or "" (root). */
    private String previewServeDir;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

}
