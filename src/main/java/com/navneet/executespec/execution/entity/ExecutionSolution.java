package com.navneet.executespec.execution.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "execution_solution")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
@ToString(exclude = {"executionRun", "dependencies", "repoMappings"})
public class ExecutionSolution {

  @Id
  @Column(length = 36)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "execution_run_id", nullable = false)
  private ExecutionRun executionRun;

  @Column(name = "sequence_number", nullable = false)
  private int sequenceNumber;

  @Column(nullable = false, length = 500)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(nullable = false, length = 30)
  private String status;

  @Column(name = "failure_phase", length = 20)
  private String failurePhase;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private int retryCount = 0;

  @Column(name = "max_retries", nullable = false)
  @Builder.Default
  private int maxRetries = 3;

  /**
   * v2.5.12 — V107 statusReason from the most recent child V2 PipelineRun (e.g. {@code
   * FINAL_BUILD_FAILED}, {@code TESTS_FAILED:5_OF_12}). {@link
   * com.navneet.executespec.execution.service.SolutionExecutionService} uses this to short-circuit
   * the PARTIAL retry loop when the same signature repeats across consecutive attempts.
   */
  @Column(name = "last_failure_signature", length = 80)
  private String lastFailureSignature;

  /**
   * v2.5.12 — number of consecutive retries that produced the same {@link #lastFailureSignature}.
   * Reset to 1 when the signature changes. Retry loop aborts when this reaches 2.
   */
  @Column(name = "consecutive_failure_count", nullable = false)
  @Builder.Default
  private int consecutiveFailureCount = 0;

  @Column(length = 10)
  private String complexity;

  @Column(name = "estimated_tokens")
  private Long estimatedTokens;

  @Column(name = "actual_tokens", nullable = false)
  @Builder.Default
  private Long actualTokens = 0L;

  @Column(name = "cost_usd", nullable = false, precision = 10, scale = 4)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  @Column(name = "git_tag_start", length = 255)
  private String gitTagStart;

  @Column(name = "git_tag_end", length = 255)
  private String gitTagEnd;

  @Column(name = "pipeline_run_id", length = 36)
  private String pipelineRunId;

  @Column(name = "pr_number")
  private Integer prNumber;

  @Column(name = "pr_url", length = 500)
  private String prUrl;

  @Column(name = "pr_status", length = 20)
  private String prStatus;

  @Column(name = "jira_ticket_id", length = 50)
  private String jiraTicketId;

  @Column(name = "jira_enabled", nullable = false)
  @Builder.Default
  private boolean jiraEnabled = false;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "execution_solution_dependency",
      joinColumns = @JoinColumn(name = "solution_id"),
      inverseJoinColumns = @JoinColumn(name = "depends_on_id"))
  @Builder.Default
  private Set<ExecutionSolution> dependencies = new HashSet<>();

  // EAGER + Set so Hibernate doesn't hit MultipleBagFetchException when
  // combined with other List-typed eager collections. Set is safe here
  // because a solution maps to at most a handful of repos and we never
  // rely on the original list order (reads use .get(0) as a first-match).
  @OneToMany(
      mappedBy = "solution",
      fetch = FetchType.EAGER,
      cascade = CascadeType.ALL,
      orphanRemoval = true)
  @Builder.Default
  private Set<ExecutionSolutionRepo> repoMappings = new HashSet<>();

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
    if (status == null) status = "QUEUED";
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
