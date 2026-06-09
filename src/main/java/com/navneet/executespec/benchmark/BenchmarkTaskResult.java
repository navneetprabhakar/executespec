package com.navneet.executespec.benchmark;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One scored attempt of one task within a {@link BenchmarkRun} ({@code reward} 1=pass / 0=fail), as
 * written by Harbor's verifier and ingested by the orchestrator. {@link #esRunId} links to the
 * {@code pipeline_run} that executed it (so execution detail + score live together). V236.
 */
@Entity
@Table(
    name = "benchmark_task_result",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_benchmark_task",
            columnNames = {"benchmark_run_id", "task_name", "attempt"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkTaskResult {

  @Id private String id;

  @Column(name = "benchmark_run_id", nullable = false, length = 64)
  private String benchmarkRunId;

  @Column(name = "task_name", nullable = false, length = 200)
  private String taskName;

  @Column(nullable = false)
  @Builder.Default
  private int attempt = 1;

  /** 1 = passed Harbor's verifier, 0 = failed. */
  @Column(nullable = false)
  @Builder.Default
  private int reward = 0;

  @Column(name = "es_run_id", length = 64)
  private String esRunId;

  @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
