package com.navneet.executespec.state.entity;

import com.navneet.executespec.tenant.TenantInterceptor;
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

    // ── Per-agent token tracking (V19) ──────────────────────────────────────
    @Builder.Default private Integer plannerInputTokens  = 0;
    @Builder.Default private Integer plannerOutputTokens = 0;
    private String plannerProvider;      // actual provider that served planning (may differ from config due to failover)
    private String plannerActualModel;   // actual model used

    @Builder.Default private Integer reviewerInputTokens  = 0;
    @Builder.Default private Integer reviewerOutputTokens = 0;
    private String reviewerProvider;
    private String reviewerActualModel;

    @Builder.Default private Integer compilerInputTokens  = 0;
    @Builder.Default private Integer compilerOutputTokens = 0;
    private String compilerProvider;
    private String compilerActualModel;

    /** Pre-run cost estimate (low/high USD range). Set at plan creation, shown at approval gate. */
    @Column(name = "cost_estimate_low")
    private Double costEstimateLow;
    @Column(name = "cost_estimate_high")
    private Double costEstimateHigh;

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

    /** "PROTOTYPE" | "PRODUCTION". Controls speed-vs-quality tradeoffs. */
    @Builder.Default
    @Column(name = "execution_mode", length = 20)
    private String executionMode = "PRODUCTION";

    /** User-selected LLM provider (copied from Project at submission time). */
    @Builder.Default
    @Column(name = "ai_provider", length = 20)
    private String aiProvider = "OPENAI";

    public com.navneet.executespec.router.LlmProvider getAiProviderEnum() {
        try { return com.navneet.executespec.router.LlmProvider.valueOf(aiProvider); }
        catch (Exception e) { return com.navneet.executespec.router.LlmProvider.OPENAI; }
    }

    public com.navneet.executespec.harness.ExecutionMode getExecutionModeEnum() {
        try { return com.navneet.executespec.harness.ExecutionMode.valueOf(executionMode); }
        catch (Exception e) { return com.navneet.executespec.harness.ExecutionMode.PRODUCTION; }
    }

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

    /** User's subscription tier at run creation time — FREE | PRO | ENTERPRISE. Used for async budget/model enforcement. */
    @Column(name = "user_tier", length = 20)
    private String userTier;

    /**
     * Comma-separated tech stack IDs detected from the plan JSON, e.g. "react,typescript,nodejs".
     * Computed after planning from task titles, objectives, and boundaries.
     * Used by the frontend for accurate tech badges that reflect what the planner chose,
     * not just what the user requested.
     */
    @Column(name = "tech_stack")
    private String techStack;

    /** JSON-encoded ui_style config (theme, design system, layout). Set during planning. */
    @Column(name = "ui_style", columnDefinition = "TEXT")
    private String uiStyle;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private String tenantId = "default";

    // ── Run title + summary (V36) ──────────────────────────────────────────────

    /** Short 3-8 word title generated by the planner (e.g. "Todo REST API"). Null until planning completes. */
    @Column(name = "run_title", length = 200)
    private String runTitle;

    /** One-sentence summary from the planner's plan_summary field. Null until planning completes. */
    @Column(name = "run_summary", length = 500)
    private String runSummary;

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

    /** URL of the pull request created for existing-repo runs (e.g. "https://github.com/owner/repo/pull/1"). */
    @Deprecated
    @Column(name = "github_pr_url", length = 512)
    private String githubPrUrl;

    // ── SCM-agnostic fields ──────────────────────────────────────────────────

    /** Canonical PR URL — replaces githubPrUrl. Works for GitHub and Bitbucket. */
    @Column(name = "scm_pr_url", length = 1024)
    private String scmPrUrl;

    /** Which SCM provider was used for this run (GITHUB, BITBUCKET, or null for legacy runs). */
    @Column(name = "scm_provider", length = 20)
    private String scmProvider;

    // ── Bitbucket credentials (captured from user at submission) ─────────────

    @Column(name = "bitbucket_username")
    private String bitbucketUsername;

    @Column(name = "bitbucket_app_password")
    private String bitbucketAppPassword;

    @Column(name = "bitbucket_workspace")
    private String bitbucketWorkspace;

    @Column(name = "bitbucket_repo_slug")
    private String bitbucketRepoSlug;

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
     * How many times apply-suggestion (chat copilot or review) has been invoked on this run.
     * Shared counter for both COPILOT and REVIEW sources. Capped by tier_config.max_apply_suggestions.
     */
    @Builder.Default
    @Column(name = "apply_suggestion_count", columnDefinition = "integer default 0")
    private int applySuggestionCount = 0;

    /**
     * When this run was created as an iteration of a previous run, this field
     * holds the source run's ID so the harness can copy the workspace.
     */
    @Column(name = "source_run_id")
    private String sourceRunId;

    /** How many times a preview has been started for this run. Capped by tier_config.max_preview_starts. */
    @Builder.Default
    @Column(name = "preview_start_count", columnDefinition = "integer default 0")
    private int previewStartCount = 0;

    /** Current plan version (incremented on each plan edit / apply-suggestion). */
    @Builder.Default
    @Column(name = "plan_version")
    private int planVersion = 1;

    /** How many times this run has been auto-retried at the run level (distinct from per-task retry). */
    @Builder.Default
    @Column(name = "run_retry_count", columnDefinition = "integer default 0")
    private int runRetryCount = 0;

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

    // ── User archive ─────────────────────────────────────────────────────────

    /**
     * Timestamp when the user archived this run. Null for active (non-archived) runs.
     * Archived runs are permanently deleted after 90 days.
     * This is irreversible — once archived, a run cannot be unarchived.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;

    public void markArchived() {
        this.archivedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

}
