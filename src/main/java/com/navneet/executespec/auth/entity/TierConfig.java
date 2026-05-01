package com.navneet.executespec.auth.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Global tier configuration — run quotas editable by super admin at runtime. One row per tier:
 * FREE, PRO, TEAM, MAX, ENTERPRISE.
 *
 * <p>v2.6.0 adds {@code lifecycleState} ({@link
 * com.navneet.executespec.billing.lifecycle.LifecycleState}) so any tier can be hidden from new
 * buyers without forcing churn on existing subscribers. See docs/plans/tier-lifecycle-plan.md.
 */
@Entity
@Table(name = "tier_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TierConfig {

  /** Tier identifier — FREE | PRO | ENTERPRISE */
  @Id private String tier;

  /** Display name shown in UI (e.g. "Free", "Pro", "Enterprise"). */
  @Column(name = "display_name", nullable = false)
  private String displayName;

  /** Monthly run limit. -1 means unlimited. */
  @Column(name = "monthly_run_limit", nullable = false)
  private int monthlyRunLimit;

  /**
   * When true, the UI shows this tier's limit as fixed (hint only). Super admin can still edit it.
   */
  @Column(name = "is_fixed", nullable = false)
  private boolean isFixed;

  @Column(name = "description")
  private String description;

  /** Max org members. -1 = unlimited. FREE=-1 (N/A), PRO=10, ENT=-1. */
  @Column(name = "max_org_members", nullable = false)
  private int maxOrgMembers = -1;

  /** Max chat messages per run. 0 = disabled. FREE=0, PRO/ENT=10. */
  @Column(name = "max_chat_messages", nullable = false)
  private int maxChatMessages = 10;

  /** Max concurrent runs. FREE=1, PRO=3, ENT=10. */
  @Column(name = "concurrent_run_limit", nullable = false)
  private int concurrentRunLimit = 1;

  /** Max apply-suggestion actions per run. FREE=2, PRO=10, ENT=-1 (unlimited). */
  @Column(name = "max_apply_suggestions", nullable = false)
  private int maxApplySuggestions = 2;

  /** Max preview starts per run. FREE=2, PRO=5, ENT=-1 (unlimited). */
  @Column(name = "max_preview_starts", nullable = false)
  private int maxPreviewStarts = 2;

  /** Max automatic run-level retries on failure. FREE=0, PRO=2, ENT=3. */
  @Column(name = "max_run_auto_retries", nullable = false)
  private int maxRunAutoRetries = 0;

  /** Whether this tier can use the deploy engine. FREE=false, PRO/ENT=true. */
  @Column(name = "can_deploy", nullable = false)
  private boolean canDeploy = false;

  /** Max active deployments. 0=disabled, -1=unlimited. FREE=0, PRO=5, ENT=-1. */
  @Column(name = "max_deployments", nullable = false)
  private int maxDeployments = 0;

  /** Max USD cost per run. null = unlimited. FREE=$3, PRO=$5 (no BYOK). */
  @Column(name = "max_run_cost_usd")
  private Double maxRunCostUsd;

  /** Data retention period in days. null = system default (30). FREE=30, PRO=90, ENTERPRISE=365. */
  @Column(name = "retention_days")
  private Integer retentionDays;

  /** Whether this tier unlocks the multi-repo Execution View. ENTERPRISE=true, others=false. */
  @Column(name = "execution_view_enabled", nullable = false)
  private boolean executionViewEnabled = false;

  /**
   * v2.6.3 (V140) — max number of projects an org on this tier may own. -1 = unlimited. Source of
   * truth: website/index.html (FREE=1, PRO=2, TEAM=5, MAX=10, ENTERPRISE=-1). Enforced by {@link
   * com.navneet.executespec.auth.service.TierEnforcementService#assertCanCreateProject}.
   */
  @Column(name = "max_projects_per_org", nullable = false)
  private int maxProjectsPerOrg = -1;

  /**
   * v2.6.3 (V140) — max number of repos that may be linked to a single project on this tier. -1 =
   * unlimited. Source of truth: website/index.html (FREE=1, PRO=2, TEAM=5, MAX=10, ENTERPRISE=-1).
   * Enforced by {@link
   * com.navneet.executespec.auth.service.TierEnforcementService#assertCanLinkRepoToProject}.
   */
  @Column(name = "max_repos_per_project", nullable = false)
  private int maxReposPerProject = -1;

  /**
   * v2.6.0 — lifecycle state. LISTED = shown on pricing page + accepts new signups. HIDDEN =
   * invisible to new buyers, existing subscribers untouched. SUNSETTING = HIDDEN + renewal
   * cancellation queued on gateway. Stored as a string to match the DB CHECK constraint values;
   * parsed via {@link com.navneet.executespec.billing.lifecycle.LifecycleState#valueOf(String)}.
   */
  @Column(name = "lifecycle_state", nullable = false, length = 16)
  private String lifecycleState = "LISTED";

  @Column(name = "lifecycle_changed_at")
  private Instant lifecycleChangedAt;

  @Column(name = "lifecycle_changed_by", length = 64)
  private String lifecycleChangedBy;

  @Column(name = "lifecycle_reason", columnDefinition = "TEXT")
  private String lifecycleReason;
}
