package com.navneet.executespec.benchmark;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A closed-loop, in-app Terminal-Bench run (RL-2026-06). ExecuteSpec triggers + tracks it; Harbor is
 * orchestrated under the hood by {@code BenchmarkOrchestratorService} (Harbor owns the verifier +
 * official submission). Each task is also a {@code pipeline_run}, linked via {@link
 * BenchmarkTaskResult#getEsRunId()}. Operator-only (SUPER_ADMIN + {@code @Profile}). See V236.
 */
@Entity
@Table(name = "benchmark_run")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkRun {

  @Id private String id;

  /** Benchmark suite. v1: {@code TERMINAL_BENCH}. */
  @Column(nullable = false, length = 40)
  private String suite;

  @Column(nullable = false, length = 120)
  private String dataset;

  @Column(nullable = false, length = 40)
  private String provider;

  @Column(nullable = false, length = 120)
  private String model;

  /** Attempts per task (k=5 for a leaderboard run). */
  @Column(nullable = false)
  @Builder.Default
  private int k = 1;

  @Column(name = "total_tasks", nullable = false)
  @Builder.Default
  private int totalTasks = 0;

  /** Tasks with at least one passing attempt (the headline "resolved%" metric). */
  @Column(name = "resolved_tasks", nullable = false)
  @Builder.Default
  private int resolvedTasks = 0;

  /** PENDING / RUNNING / DONE / FAILED / CANCELLED. */
  @Column(nullable = false, length = 20)
  @Builder.Default
  private String status = "PENDING";

  @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  /** The Harbor job name (for `harbor upload` + the job dir). */
  @Column(name = "harbor_job_name", length = 120)
  private String harborJobName;

  @Column(name = "created_by_user_id", length = 64)
  private String createdByUserId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(columnDefinition = "text")
  private String error;
}
