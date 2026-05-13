package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.*;

import com.navneet.executespec.router.LlmProvider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project {

  @Id private String id;

  @Column(name = "org_id", nullable = false)
  private String orgId;

  @Column(nullable = false)
  private String name;

  @Column(columnDefinition = "TEXT")
  private String description;

  /** ACTIVE | ARCHIVED */
  @Column(nullable = false)
  @Builder.Default
  private String status = "ACTIVE";

  /** User-selected LLM provider for this project. */
  @Column(name = "ai_provider", nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private LlmProvider aiProvider = LlmProvider.ANTHROPIC;

  @Column(name = "created_by", nullable = false)
  private String createdBy;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  /** Pilot R5 — branch name template used when pushing run output to GitHub. */
  @Column(name = "branch_naming_pattern")
  @Builder.Default
  private String branchNamingPattern = "executespec/{runId}/{description}";

  /** Pilot R5 — default target branch for PRs created from this project. */
  @Column(name = "target_branch")
  @Builder.Default
  private String targetBranch = "main";

  /** Pilot R5 — JSON array of GitHub usernames to request review from by default. */
  @Column(name = "default_reviewers", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String defaultReviewers;

  /**
   * v2.5.7 — default GitHub org/owner for repos created under this project. Pre-fills the
   * githubOwner field in Launch new-repo specs.
   */
  @Column(name = "github_owner")
  private String githubOwner;

  /**
   * M-033 (Wave 3) — per-project monthly spend cap in USD. NULL = no cap. Enforced at plan-approval
   * time: if (current MTD spend on this project + the new run's buffered estimate) > cap, the
   * approval returns 402 with a "your project cap is X" hint.
   */
  @Column(name = "spend_cap_usd_monthly", precision = 10, scale = 2)
  private java.math.BigDecimal spendCapUsdMonthly;

  /**
   * v2.6.2 (E1.7) — where this project's standards / scopes / detection-overrides config lives.
   * NULL or FOLDER (default) keeps config in {@code .executespec/config/<project-slug>/} on the
   * customer repo (visible to anyone with read access). DATABASE is the confidential-project
   * opt-out — config stays in the {@code project_config} table only, invisible to other readers of
   * the repo. See {@link #configStorageEnum()} for the NULL → FOLDER normaliser.
   */
  @Column(name = "config_storage", length = 16)
  private String configStorage;

  /**
   * v2.6.2 (E1.7) — strongly-typed read of {@link #configStorage} with NULL/blank/unknown all
   * normalised to {@link ConfigStorage#FOLDER}.
   */
  public ConfigStorage configStorageEnum() {
    if (configStorage == null || configStorage.isBlank()) return ConfigStorage.FOLDER;
    try {
      return ConfigStorage.valueOf(configStorage);
    } catch (IllegalArgumentException ex) {
      return ConfigStorage.FOLDER; // forward-compat unknown value → safe default
    }
  }

  /**
   * v2.6.2 (E1.7) — config storage strategy per the multi-project / confidential-project opt-out
   * from the enterprise-readiness plan.
   */
  public enum ConfigStorage {
    FOLDER,
    DATABASE
  }

  /**
   * Wave 2.2 — disposable onboarding project auto-provisioned on first login. Hidden from {@code
   * /org/projects} list, GC'd after 24h of inactivity by {@code SandboxCleanupJob}. Exists so a new
   * user can submit a sample spec in under 3 minutes without linking a real GitHub repo first.
   */
  @Column(name = "is_sandbox", nullable = false)
  @Builder.Default
  private boolean isSandbox = false;

  /**
   * V191 (PR-B-part-2, 2026-05-13) — when the user archived this project. NULL while {@link
   * #status} is ACTIVE. Stamped by {@code ProjectService.archiveProject} and cleared back to NULL
   * by {@code ProjectService.unarchiveProject}. Status field is kept in lockstep — both have to say
   * the same thing — but the timestamp is what the cleanup sweeper (PR-H) gates on.
   */
  @Column(name = "archived_at")
  private Instant archivedAt;

  /**
   * V191 (PR-B-part-2, 2026-05-13) — when the user soft-deleted this project. Soft-deleted projects
   * are hidden from every listing but retained for 30 days so the user can undo the action via
   * {@code POST /api/projects/{id}/restore}. After 30 days a sweeper job (PR-H) hard-deletes the
   * row + its dependent ledger entries. Distinct from {@link #archivedAt} — archive is "park out of
   * sight, stays visible under the archived toggle", delete is "I want this gone".
   */
  @Column(name = "deleted_at")
  private Instant deletedAt;

  public boolean isArchived() {
    return archivedAt != null;
  }

  public boolean isSoftDeleted() {
    return deletedAt != null;
  }
}
