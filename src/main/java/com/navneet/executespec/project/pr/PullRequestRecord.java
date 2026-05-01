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

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "run_id", nullable = false)
  private String runId;

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
}
