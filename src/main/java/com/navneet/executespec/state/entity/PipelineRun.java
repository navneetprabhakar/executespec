package com.navneet.executespec.state.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.tenant.TenantInterceptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(TenantInterceptor.class)
@Table(name = "pipeline_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineRun {

  @Id private String runId; // UUID, set by harness

  @Column(columnDefinition = "TEXT")
  private String userRequest; // original natural language request

  private String status; // PLANNING | AWAITING_APPROVAL | WORKING |

  // REVIEWING | AWAITING_MERGE | COMPLETE | FAILED | PARTIAL

  /**
   * v2.5.10 (V107) — explicit reason for the terminal status so the UI can show "PARTIAL because
   * tests failed" vs "PARTIAL because final build failed" instead of the silent badge today.
   * Populated by {@code RunOrchestrator} unconditionally as of 2026-05-13 (the audit found the
   * {@code scoring.v2-verified} flag was gating the REASON derivation too — net effect: any run
   * with the flag off had a useless statusReason). V108 backfilled historical rows with {@code
   * LEGACY_UNKNOWN}; new runs use {@code NO_REVIEW_RESULT} when the reviewer didn't run. Values:
   * CLEAN_COMPLETION | CLEAN_WITH_INTERMEDIATE_WARNINGS | FINAL_BUILD_FAILED |
   * TESTS_FAILED:&lt;n&gt;_OF_&lt;m&gt; | REVIEWER_UNRESOLVED | REVIEWER_FAILED | ALL_TASKS_FAILED
   * | NO_REVIEW_RESULT | LEGACY_UNKNOWN (V108 backfill only).
   */
  @Column(name = "status_reason", length = 80)
  private String statusReason;

  /**
   * Which pipeline produced this run. "v1" = V1 PipelineHarness; "v2" = RunOrchestrator. Default
   * "v1" (see V76 migration) so legacy rows and any row written without the field set are treated
   * as V1. HarnessVersionResolver reads this for per-run action routing so actions dispatched on a
   * running V2 run always land in V2 code paths.
   */
  @Column(name = "harness_version", length = 16)
  @lombok.Builder.Default
  private String harnessVersion = "v1";

  /**
   * V182 (2026-05-08) — which V2 pipeline shape this run uses. See {@link
   * com.navneet.executespec.state.RunMode} for the authoritative spelling. {@code BUILD} is the
   * default product flow (planner + executor + reviewer); {@code PATCH} is the single thin-agent
   * loop used by the swebench + programbench bench services. Stored as a string for convention
   * parity with {@code harness_version} / {@code execution_mode} / {@code status}. NOT NULL with
   * DEFAULT 'BUILD' on the SQL side, so legacy rows + rows persisted before this column existed
   * read as BUILD without breaking dispatch.
   */
  @Column(name = "run_mode", length = 16, nullable = false)
  @lombok.Builder.Default
  private String runMode = com.navneet.executespec.state.RunMode.BUILD;

  /**
   * V183 (2026-05-08) — when a patch-mode run is dispatched in response to a project-side finding
   * (PR review, tech debt, code review, CI failure), this column stamps the originating finding id
   * so the dashboard can render "Triggered by ..." instead of an orphan run + the {@link
   * com.navneet.executespec.finding.FindingPatchSubmitter} can dedupe re-dispatch. Format {@code
   * <TYPE>:<id>}.
   */
  @Column(name = "triggered_by_finding", length = 128)
  private String triggeredByFinding;

  /**
   * Count of boot-time resume attempts for this run (incremented by {@code StartupRunResumer}).
   * Capped at 3 in code — past the cap a run is permanently FAILED with reason "resume limit
   * reached". Prevents infinite resume loops across repeated deploys.
   */
  @Column(name = "resume_attempts")
  @lombok.Builder.Default
  private Integer resumeAttempts = 0;

  @Column(columnDefinition = "TEXT")
  private String planJson; // full TaskPlan JSON from planner

  /**
   * v2.5.11 (Validation v3 / Pillar 1, V109) — JSON array of {@code RequirementSpec{id,text,kind}}
   * extracted by the planner. Anchors the requirement-graph: each {@link
   * AgentTask#getMeetsRequirementsJson()} references these IDs; the reviewer later emits one {@code
   * RequirementEvidence} row per ID. Null for legacy rows and when the {@code validation.v3-strict}
   * flag was off at plan time.
   */
  @Column(name = "plan_requirements_json", columnDefinition = "TEXT")
  private String planRequirementsJson;

  @Column(columnDefinition = "TEXT")
  private String reviewJson; // TaskReviewReport JSON

  private String plannerModel;
  private String workerModel;
  private String reviewerModel;

  private String sourceType; // MANUAL | JIRA | CONFLUENCE | SERVICENOW | PAGERDUTY
  private String sourceId; // e.g. "PROJ-1234" for Jira (null for manual)

  private Instant startedAt;
  private Instant completedAt;

  private Integer totalInputTokens; // accumulated across all agents
  private Integer totalOutputTokens;

  // ── Per-agent token tracking (V19) ──────────────────────────────────────
  @Builder.Default private Integer plannerInputTokens = 0;
  @Builder.Default private Integer plannerOutputTokens = 0;
  private String
      plannerProvider; // actual provider that served planning (may differ from config due to
  // failover)
  private String plannerActualModel; // actual model used

  @Builder.Default private Integer reviewerInputTokens = 0;
  @Builder.Default private Integer reviewerOutputTokens = 0;
  private String reviewerProvider;
  private String reviewerActualModel;

  @Builder.Default private Integer compilerInputTokens = 0;
  @Builder.Default private Integer compilerOutputTokens = 0;
  private String compilerProvider;
  private String compilerActualModel;

  /** Pre-run cost estimate (low/high USD range). Set at plan creation, shown at approval gate. */
  @Column(name = "cost_estimate_low")
  private Double costEstimateLow;

  @Column(name = "cost_estimate_high")
  private Double costEstimateHigh;

  private Double actualCostUsd; // computed post-run with cache-aware pricing (null until complete)

  /** Run credits charged (proportional to estimated cost). Default 1. */
  @Builder.Default
  @Column(name = "credits_charged")
  private Integer creditsCharged = 1;

  /** Hard cap in USD: creditsCharged × creditMultiplier × budgetMargin. Silent safety net. */
  @Column(name = "hard_cap_usd")
  private Double hardCapUsd;

  /**
   * GA-readiness P3.2 (V178, 2026-05-06) — optional customer-supplied tighter ceiling. When
   * present, {@code RunOrchestrator.persistEstimate} clamps {@link #hardCapUsd} to {@code
   * min(platformCap, userCostCap)} so the customer's "Stop at $X" input is honoured. Null on legacy
   * runs / runs without an explicit override.
   */
  @Column(name = "user_cost_cap_usd")
  private Double userCostCap;

  // ── Batch execution ──────────────────────────────────────────────────────
  /** "REALTIME" or "BATCH" — user's choice at plan approval. */
  @Builder.Default
  @Column(name = "approved_execution_mode", length = 10)
  private String approvedExecutionMode = "REALTIME";

  /** Provider batch API ID (e.g. Anthropic msgbatch_xxx, OpenAI batch_xxx). */
  @Column(name = "batch_id")
  private String batchId;

  /** Provider used for batch submission. */
  @Column(name = "batch_provider", length = 20)
  private String batchProvider;

  /** Number of tasks completed in the batch so far. */
  @Column(name = "batch_progress_completed")
  private Integer batchProgressCompleted;

  /** Total tasks in the batch. */
  @Column(name = "batch_progress_total")
  private Integer batchProgressTotal;

  /** When the batch was submitted to the provider. */
  @Column(name = "batch_submitted_at")
  private java.time.Instant batchSubmittedAt;

  /** Batch credits (with discount). Null if batch not available. */
  @Column(name = "batch_credits")
  private Integer batchCredits;

  /** True if this run qualifies for batch execution. */
  @Column(name = "batch_available")
  private Boolean batchAvailable;

  /** Credits saved by choosing batch over real-time. */
  @Column(name = "credit_savings")
  private Integer creditSavings;

  @Column(columnDefinition = "TEXT")
  private String failureReason; // set on FAILED state

  // ── Tier 3 fields ─────────────────────────────────────────────────────────
  private String repoUrl; // HTTPS clone URL (null if no repo)
  private String repoMode; // "NEW" | "EXISTING" | null
  private String workspacePath; // per-run path: ./workspace/projects/{runId}/
  private Integer queuePosition; // 0 if running/done, >0 if queued
  private String currentGate; // "PLAN_APPROVAL" | "MERGE_APPROVAL" | null

  // ── Tier 7 fields ─────────────────────────────────────────────────────────
  @Builder.Default
  @Column(columnDefinition = "boolean default false")
  private boolean skipApproval =
      false; // true = auto-approve plan gate (integration-triggered runs)

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

  // ── Hierarchical Planner (v2.6.2 — V130) ─────────────────────────────────
  /**
   * Strategy chosen by {@code SmartClassifierAgent}: SINGLE_SHOT | SMALL | MEDIUM | LARGE | LEGACY.
   * Drives which planner phases run downstream. Null on runs created before slice 1 of the
   * hierarchical rollout.
   */
  @Column(name = "plan_strategy", length = 32)
  private String planStrategy;

  /** JSON outline emitted by {@code OutlinePlannerAgent} (slice 2+). */
  @Column(name = "plan_outline_json", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String planOutlineJson;

  /**
   * One-line classifier rationale, persisted for offline calibration (heuristic-verdict vs
   * LLM-verdict vs final outcome).
   */
  @Column(name = "classifier_reasoning", columnDefinition = "TEXT")
  private String classifierReasoning;

  @Column(name = "classifier_input_tokens")
  private Long classifierInputTokens;

  @Column(name = "classifier_output_tokens")
  private Long classifierOutputTokens;

  @Column(name = "classifier_cost_usd")
  private Double classifierCostUsd;

  @Column(name = "outline_input_tokens")
  private Long outlineInputTokens;

  @Column(name = "outline_output_tokens")
  private Long outlineOutputTokens;

  @Column(name = "outline_cost_usd")
  private Double outlineCostUsd;

  @Column(name = "expansion_input_tokens")
  private Long expansionInputTokens;

  @Column(name = "expansion_output_tokens")
  private Long expansionOutputTokens;

  @Column(name = "expansion_cost_usd")
  private Double expansionCostUsd;

  @Column(name = "coherence_input_tokens")
  private Long coherenceInputTokens;

  @Column(name = "coherence_output_tokens")
  private Long coherenceOutputTokens;

  @Column(name = "coherence_cost_usd")
  private Double coherenceCostUsd;

  public com.navneet.executespec.router.LlmProvider getAiProviderEnum() {
    try {
      return com.navneet.executespec.router.LlmProvider.valueOf(aiProvider);
    } catch (Exception e) {
      return com.navneet.executespec.router.LlmProvider.OPENAI;
    }
  }

  public com.navneet.executespec.harness.ExecutionMode getExecutionModeEnum() {
    try {
      return com.navneet.executespec.harness.ExecutionMode.valueOf(executionMode);
    } catch (Exception e) {
      return com.navneet.executespec.harness.ExecutionMode.PRODUCTION;
    }
  }

  // ── Auto-restart tracking ─────────────────────────────────────────────────
  /**
   * How many times this run has been auto-restarted due to transient failures (rate-limiting,
   * provider unavailability, etc.). Capped at {@code agent.recovery.max-auto-restarts} to prevent
   * infinite loops.
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
   * User's subscription tier at run creation time — FREE | PRO | ENTERPRISE. Used for async
   * budget/model enforcement.
   */
  @Column(name = "user_tier", length = 20)
  private String userTier;

  /**
   * Comma-separated tech stack IDs detected from the plan JSON, e.g. "react,typescript,nodejs".
   * Computed after planning from task titles, objectives, and boundaries. Used by the frontend for
   * accurate tech badges that reflect what the planner chose, not just what the user requested.
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

  /**
   * Short 3-8 word title generated by the planner (e.g. "Todo REST API"). Null until planning
   * completes.
   */
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

  /**
   * URL of the pull request created for existing-repo runs (e.g.
   * "https://github.com/owner/repo/pull/1").
   */
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
   * Conversation history as a JSON array of {@code {role, content}} objects. Roles: "user" |
   * "assistant". Null until the first message is sent.
   */
  @Column(name = "chat_history_json", columnDefinition = "TEXT")
  private String chatHistoryJson;

  /**
   * Total number of messages exchanged (user + assistant combined). Capped at 20 in the
   * conversation controller (10 user + 10 assistant).
   */
  @Builder.Default
  @Column(name = "chat_message_count", columnDefinition = "integer default 0")
  private int chatMessageCount = 0;

  /**
   * How many times apply-suggestion (chat copilot or review) has been invoked on this run. Shared
   * counter for both COPILOT and REVIEW sources. Capped by tier_config.max_apply_suggestions.
   */
  @Builder.Default
  @Column(name = "apply_suggestion_count", columnDefinition = "integer default 0")
  private int applySuggestionCount = 0;

  /**
   * When this run was created as an iteration of a previous run, this field holds the source run's
   * ID so the harness can copy the workspace.
   */
  @Column(name = "source_run_id")
  private String sourceRunId;

  /**
   * How many times a preview has been started for this run. Capped by
   * tier_config.max_preview_starts.
   */
  @Builder.Default
  @Column(name = "preview_start_count", columnDefinition = "integer default 0")
  private int previewStartCount = 0;

  /** Current plan version (incremented on each plan edit / apply-suggestion). */
  @Builder.Default
  @Column(name = "plan_version")
  private int planVersion = 1;

  /**
   * How many times this run has been auto-retried at the run level (distinct from per-task retry).
   */
  @Builder.Default
  @Column(name = "run_retry_count", columnDefinition = "integer default 0")
  private int runRetryCount = 0;

  // ── GCS workspace archiving ────────────────────────────────────────────────

  /**
   * GCS object key for the archived workspace zip, e.g. {@code workspaces/{runId}.zip}. Null while
   * the workspace is still on local disk (active or recently completed). Set by {@code
   * WorkspaceArchiveJob} after the zip has been successfully uploaded.
   */
  @Column(name = "gcs_workspace_key")
  private String gcsWorkspaceKey;

  /**
   * Timestamp when this run's workspace was archived to GCS and the local directory deleted. Null
   * while local files still exist.
   */
  @Column(name = "gcs_archived_at")
  private Instant gcsArchivedAt;

  // ── User archive ─────────────────────────────────────────────────────────

  /**
   * Timestamp when the user archived this run. Null for active (non-archived) runs. Archived runs
   * are permanently deleted after 90 days. This is irreversible — once archived, a run cannot be
   * unarchived.
   */
  @Column(name = "archived_at")
  private Instant archivedAt;

  public void markArchived() {
    this.archivedAt = Instant.now();
  }

  public boolean isArchived() {
    return archivedAt != null;
  }

  // ── Instance tracking (V68) ───────────────────────────────────────────────

  /** Hostname/pod of the instance currently executing this run's pipeline thread. */
  @Column(name = "executing_instance")
  private String executingInstance;

  /** Last heartbeat from the executing instance (updated every 30s). */
  @Column(name = "execution_heartbeat")
  private Instant executionHeartbeat;

  // ── GDPR soft-delete ─────────────────────────────────────────────────────

  /**
   * Timestamp when the user (or retention job) soft-deleted this run. Soft-deleted runs are
   * excluded from queries and hard-deleted by the retention scheduler after the workspace GCS
   * deletion is confirmed.
   */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  public boolean isSoftDeleted() {
    return deletedAt != null;
  }

  // ── Pilot R4 / R7 ────────────────────────────────────────────────────────

  /** JSON output of {@code FeatureAssessmentService}. Null until ASSESSING phase runs. */
  @Column(name = "assessment_result", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String assessmentResult;

  /** Optional Figma URL passed at submission. Drives the design-compliance scorer. */
  @Column(name = "figma_url", length = 500)
  private String figmaUrl;

  /** Pilot v3.0 — JSON array of project_repo IDs cloned for this run, used by multi-repo push. */
  @Column(name = "selected_repo_ids", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String selectedRepoIds;

  // ── V168 — Pause / resume (2026-05-02) ───────────────────────────────────

  /**
   * Pause request flag. Set by {@code RunController.pauseRun} (POST /api/runs/{id}/pause). Read by
   * the executor at every task boundary AND by the worker at every iteration boundary; on TRUE the
   * agent persists a checkpoint and transitions the run to {@code PAUSED}. Reset to FALSE when the
   * run resumes, terminates, or cancels — so a re-pause request after resume gets a fresh trigger.
   */
  @Column(name = "pause_requested", nullable = false)
  private boolean pauseRequested = false;

  /** Wall-clock timestamp the executor wrote when it observed the pause request. */
  @Column(name = "paused_at")
  private Instant pausedAt;

  /** Most recent resume timestamp. Updated on every resume; older resumes are not retained. */
  @Column(name = "resumed_at")
  private Instant resumedAt;

  /**
   * The {@code agent_task.id} the worker was on when pause took effect. {@code null} when pause was
   * observed between tasks (no task was in flight). Resume uses this to restart the executor at the
   * right task index — combined with {@link #pausedAtIteration} for mid-task resume from the
   * correct worker iteration.
   */
  @Column(name = "paused_task_id", length = 64)
  private String pausedTaskId;

  /**
   * Worker iteration count within the paused task (0-based). {@code null} when pause was observed
   * between tasks. Combined with {@link #pausedTaskId} and {@code ContextStore}'s per-(runId,
   * taskId) snapshot to resume mid-task from the exact iteration the worker was on.
   */
  @Column(name = "paused_at_iteration")
  private Integer pausedAtIteration;

  /**
   * How many times this run has been paused. Hard cap at the controller (default 10, configurable
   * via {@code v2.pause.max_pauses_per_run}). Keeps an adversarial / buggy client from
   * pause-spamming a run forever — each pause re-locks resources, persists a checkpoint, and costs
   * Redis writes.
   */
  @Column(name = "pause_count", nullable = false)
  private int pauseCount = 0;
}
