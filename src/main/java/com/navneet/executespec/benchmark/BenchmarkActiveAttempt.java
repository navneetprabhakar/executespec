package com.navneet.executespec.benchmark;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Durable in-flight attempt projection; supports concurrent benchmark workers and JVM restart. */
@Entity
@Table(
    name = "benchmark_active_attempt",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_benchmark_active_attempt",
            columnNames = {"benchmark_run_id", "task_name", "attempt"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkActiveAttempt {
  @Id private String id;

  @Column(name = "benchmark_run_id", nullable = false, length = 64)
  private String benchmarkRunId;

  @Column(name = "task_name", nullable = false, length = 200)
  private String taskName;

  @Column(nullable = false)
  private int attempt;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "last_progress_at", nullable = false)
  private Instant lastProgressAt;

  /** Heartbeat deadline; rows remain auditable after restart but are not presented as healthy. */
  @Column(name = "stale_after", nullable = false)
  private Instant staleAfter;

  @Column(name = "eta_seconds")
  private Long etaSeconds;

  @Column(name = "trajectory_location", length = 1000)
  private String trajectoryLocation;

  @Column(name = "trajectory_sha256", length = 64)
  private String trajectorySha256;

  @Transient
  public boolean isStale() {
    return staleAfter != null && !Instant.now().isBefore(staleAfter);
  }
}
