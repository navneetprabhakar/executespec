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

    private Double actualCostUsd;       // computed post-run with cache-aware pricing (null until complete)

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

    @Builder.Default
    @Column(columnDefinition = "boolean default false")
    private boolean autoMerge = false; // true = auto-approve merge gate (skip human merge review)

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

    /** Billing flag: which key source was used — SYSTEM | OWN_KEYS | HYBRID. */
    @Column(name = "key_source")
    private String keySource;

    /**
     * Comma-separated tech stack IDs detected from the plan JSON, e.g. "react,typescript,nodejs".
     * Computed after planning from task titles, objectives, and boundaries.
     * Used by the frontend for accurate tech badges that reflect what the planner chose,
     * not just what the user requested.
     */
    @Column(name = "tech_stack")
    private String techStack;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    // ── Ownership / RBAC ──────────────────────────────────────────────────────
    /** ID of the user who submitted this run. */
    @Column(name = "created_by_user_id")
    private String createdByUserId;

    /** Org this run belongs to (null for free-tier personal runs). */
    @Column(name = "org_id")
    private String orgId;

    /** Project this run belongs to within the org (null for free-tier personal runs). */
    @Column(name = "project_id")
    private String projectId;

    // ── GitHub credentials (captured from user at submission) ─────────────────

    /** Submitting user's GitHub PAT — captured at run creation for immutability. */
    @Column(name = "github_pat")
    private String githubPat;

    /** GitHub username or org to create/push repos under. */
    @Column(name = "github_owner")
    private String githubOwner;

    // ── Post-run conversation ──────────────────────────────────────────────────

    /**
     * Conversation history as a JSON array of {@code {role, content}} objects.
     * Roles: "user" | "assistant".  Null until the first message is sent.
     */
    @Column(name = "chat_history_json", columnDefinition = "TEXT")
    private String chatHistoryJson;

    /**
     * Total number of messages exchanged (user + assistant combined).
     * Capped at 20 in the conversation controller (10 user + 10 assistant).
     */
    @Builder.Default
    @Column(name = "chat_message_count", columnDefinition = "integer default 0")
    private int chatMessageCount = 0;

    /**
     * When this run was created as an iteration of a previous run, this field
     * holds the source run's ID so the harness can copy the workspace.
     */
    @Column(name = "source_run_id")
    private String sourceRunId;

    // ── GCS workspace archiving ────────────────────────────────────────────────

    /**
     * GCS object key for the archived workspace zip, e.g. {@code workspaces/{runId}.zip}.
     * Null while the workspace is still on local disk (active or recently completed).
     * Set by {@code WorkspaceArchiveJob} after the zip has been successfully uploaded.
     */
    @Column(name = "gcs_workspace_key")
    private String gcsWorkspaceKey;

    /**
     * Timestamp when this run's workspace was archived to GCS and the local directory deleted.
     * Null while local files still exist.
     */
    @Column(name = "gcs_archived_at")
    private Instant gcsArchivedAt;

}
