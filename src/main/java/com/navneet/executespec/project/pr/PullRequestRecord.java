package com.navneet.executespec.project.pr;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row per PR that ExecuteSpec opens on behalf of a run. Updated in place when GitHub fires a
 * {@code pull_request.closed} / {@code merged} webhook.
 */
@Entity
@Table(name = "pull_request_record")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestRecord {

  public enum Status {
    OPEN,
    MERGED,
    CLOSED
  }

  /**
   * Origin of the PR. {@link #RUN} is the V82 default — every existing row has this. The remaining
   * values were added in V169 (2026-05-02) so PRs opened by system flows are visible on the project
   * dashboard alongside run/launch PRs.
   *
   * <ul>
   *   <li>{@link #RUN}: ExecutionRun + per-run worker pushes (ScmPushService /
   *       ExecutionBranchService).
   *   <li>{@link #BOOTSTRAP}: initial {@code .executespec/} folder seed at repo-link time
   *       (BootstrapService).
   *   <li>{@link #ARCHITECTURE}: codemap + architecture write-back PRs from
   *       ProjectArchitectureService.
   *   <li>{@link #CONTRACT}: contract drift / consumer mirror PRs from contracts.* controllers.
   *   <li>{@link #CHAT_TOOL}: PRs opened by project-chat tool calls (V169 companion feature).
   * </ul>
   */
  public enum Kind {
    RUN,
    BOOTSTRAP,
    ARCHITECTURE,
    CONTRACT,
    CHAT_TOOL
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  /**
   * Pipeline-run id when this PR is tied to a single V2 run; null for system PRs (BOOTSTRAP /
   * ARCHITECTURE / CONTRACT / CHAT_TOOL). V169 made the column nullable so non-run PR sources can
   * persist into the same table without a synthetic id.
   */
  @Column(name = "run_id")
  private String runId;

  /**
   * Project the PR belongs to. Run-scoped PRs may leave this null (the dashboard rollup falls back
   * to {@code pipeline_run.project_id} via the run_id join), but system PRs MUST set it because
   * they have no run to look up. Added in V169.
   */
  @Column(name = "project_id")
  private String projectId;

  /** Origin classifier — see {@link Kind}. Defaults to RUN to keep the V82 contract intact. */
  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  @Builder.Default
  private Kind kind = Kind.RUN;

  /** Project-linked repo, if the run was fed from the project catalogue. */
  @Column(name = "repo_id")
  private UUID repoId;

  /** Always populated — used by webhook matching and UI display. */
  @Column(name = "repo_full_name", nullable = false)
  private String repoFullName;

  @Column(name = "pr_number", nullable = false)
  private Integer prNumber;

  @Column(name = "pr_url", nullable = false, length = 500)
  private String prUrl;

  @Column(name = "head_branch")
  private String headBranch;

  @Column(name = "base_branch")
  private String baseBranch;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  @Builder.Default
  private Status status = Status.OPEN;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "merged_at")
  private Instant mergedAt;

  @Column(name = "closed_at")
  private Instant closedAt;

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  /**
   * 2026-05-02 (V170) — count of auto-fix re-runs triggered against this PR's head branch in
   * response to {@code check_run.failure} webhooks. Capped by {@code
   * CiFailureAutoFixService.MAX_AUTO_FIX_ATTEMPTS} (default 3) so a runaway CI ↔ planner loop
   * cannot burn credits indefinitely.
   */
  @Column(name = "ci_auto_fix_attempts", nullable = false)
  @Builder.Default
  private Integer ciAutoFixAttempts = 0;

  /**
   * 2026-05-11 (V186) — CI status rollup persisted from the polling path. The webhook-driven {@link
   * CiFailureAutoFixService} updates the originating run; this column is the user-visible signal so
   * the dashboard PR rollup can render a "CI ✗ N" chip even when no webhook is installed. See
   * {@link CiCheckStatus} for the value space + transition rules.
   */
  public enum CiCheckStatus {
    UNKNOWN,
    PENDING,
    SUCCESS,
    FAILURE,
    NEUTRAL
  }

  @Enumerated(EnumType.STRING)
  @Column(name = "ci_status", nullable = false, length = 16)
  @Builder.Default
  private CiCheckStatus ciStatus = CiCheckStatus.UNKNOWN;

  /** Number of failing checks at the last successful poll. 0 unless {@code ciStatus = FAILURE}. */
  @Column(name = "ci_failing_count", nullable = false)
  @Builder.Default
  private Integer ciFailingCount = 0;

  /** Timestamp of the last successful check-runs poll. Null pre-poll. */
  @Column(name = "ci_checked_at")
  private Instant ciCheckedAt;
}
