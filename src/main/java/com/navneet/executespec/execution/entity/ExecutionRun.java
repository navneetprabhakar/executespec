package com.navneet.executespec.execution.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "execution_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionRun {

  @Id
  @Column(length = 36)
  private String id;

  @Column(name = "project_id", nullable = false, length = 36)
  private String projectId;

  @Column(name = "org_id", nullable = false, length = 36)
  private String orgId;

  @Column(name = "created_by", nullable = false, length = 36)
  private String createdBy;

  @Column(name = "jira_enabled", nullable = false)
  @Builder.Default
  private boolean jiraEnabled = false;

  /**
   * Optional Confluence doc URL captured at Launch time; appended to the planner's requirement
   * context.
   */
  @Column(name = "confluence_url", length = 500)
  private String confluenceUrl;

  /**
   * Optional Figma URL captured at Launch time; appended to the planner's requirement context.
   * Drives design-compliance scoring where supported.
   */
  @Column(name = "figma_url", length = 500)
  private String figmaUrl;

  /** Override of the project's default AI provider. Null = use project default. */
  @Column(name = "ai_provider", length = 20)
  private String aiProvider;

  /**
   * Per-Launch branch override. Null = fall back to each repo's default_branch. When set, every
   * selected repo is cloned with {@code git clone -b <branch>}.
   */
  @Column(name = "branch", length = 255)
  private String branch;

  @Column(nullable = false, length = 30)
  private String status;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String requirement;

  @Column(name = "unified_arch_doc", columnDefinition = "TEXT")
  private String unifiedArchDoc;

  @Column(name = "arch_approved_at")
  private Instant archApprovedAt;

  @Column(name = "execution_plan", columnDefinition = "TEXT")
  private String executionPlan;

  @Column(name = "plan_approved_at")
  private Instant planApprovedAt;

  @Column(name = "total_estimated_tokens")
  private Long totalEstimatedTokens;

  @Column(name = "total_actual_tokens", nullable = false)
  @Builder.Default
  private Long totalActualTokens = 0L;

  @Column(name = "total_cost_usd", nullable = false, precision = 10, scale = 4)
  @Builder.Default
  private BigDecimal totalCostUsd = BigDecimal.ZERO;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  /**
   * v2.5.12 (Bug #3 design alt) — JPA optimistic lock. Concurrent writers (async planner save vs.
   * user /plan/approve) race each other via Hibernate; the loser throws
   * ObjectOptimisticLockingFailureException which the controller translates to 409 Conflict.
   */
  @Version
  @Column(name = "version", nullable = false)
  @Builder.Default
  private Long version = 0L;

  /** v2.5.7 — continue-on-PR: links back to the execution that created the original PR. */
  @Column(name = "source_execution_id")
  private String sourceExecutionId;

  /** v2.5.7 — the PR number being addressed (e.g. 2). Null for fresh executions. */
  @Column(name = "continue_pr_number")
  private Integer continuePrNumber;

  /** v2.5.7 — repo full name of the PR being addressed (e.g. "owner/repo"). */
  @Column(name = "continue_pr_repo")
  private String continuePrRepo;

  /**
   * R-339 (V159) — when this run was instantiated from a Migration recipe via {@code
   * MigrationOrchestrator}, the recipe id; NULL for non-migration runs.
   */
  @Column(name = "migration_recipe_id")
  private UUID migrationRecipeId;

  /**
   * R-339 (V159) — the specific variant (options cell) of the recipe used. May be NULL if the
   * recipe has only a default variant and we used that.
   */
  @Column(name = "migration_variant_id")
  private UUID migrationVariantId;

  /**
   * R-331 (V160) — when this run was instantiated from a Readymade Solution via {@code
   * ReadymadeOrchestrator}, the solution id; NULL for non-readymade runs.
   */
  @Column(name = "readymade_solution_id")
  private UUID readymadeSolutionId;

  /**
   * R-331 (V160) — the specific (language, framework, options) variant used. May be NULL when the
   * user explicitly bypassed variant resolution.
   */
  @Column(name = "readymade_variant_id")
  private UUID readymadeVariantId;

  /**
   * R-324 (V155) — JSON array of project_repo IDs the user selected at Launch time. NULL or {@code
   * "[]"} means "every linked project repo is in scope" (today's pre-R-324 default). Persisted as a
   * JSON string to mirror {@link com.navneet.executespec.state.entity.PipelineRun#selectedRepoIds}
   * so the two surfaces stay symmetric.
   *
   * <p>Populated by {@code ExecutionOrchestrator.createExecution()} from {@code req.repoIds()} plus
   * any new-repo IDs minted on the same call. Read by {@code
   * ExecutionPlannerService.buildCodemapsText()} to scope the planner's view to the user's
   * selection.
   */
  @Column(name = "selected_repo_ids", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String selectedRepoIds;

  /**
   * V188 (2026-05-13) — unified-dispatch link. When non-null, points at the single V2 {@code
   * pipeline_run} that drives execution for this V3 launch (one V2 run for the whole execution;
   * multi-repo PRs handled by {@code ScmPushService.pushMultiRepo}). NULL for legacy rows that
   * fanned out into per-{@link ExecutionSolution} V2 runs — those keep using the original
   * Solution-dispatch path during the deprecation window.
   */
  @Column(name = "pipeline_run_id", length = 64)
  private String pipelineRunId;

  @OneToMany(mappedBy = "executionRun", fetch = FetchType.LAZY)
  @OrderBy("sequenceNumber ASC")
  private List<ExecutionSolution> solutions;

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (status == null) status = "SETUP";
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
