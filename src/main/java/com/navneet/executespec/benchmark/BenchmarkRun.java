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
 * One tracked, in-app benchmark run (RL-2026-06) — suite-agnostic ({@link BenchmarkSuite}:
 * Terminal-Bench first, SWE-bench / ProgramBench next). ExecuteSpec is the event <b>sink +
 * display</b>, not an orchestrator: an external verifier/harness runs the benchmark and publishes
 * events to {@link BenchmarkTrackingService}, which aggregates resolved% + cost here. Each scored
 * task may link the {@code pipeline_run} that executed it via {@link
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

  /** Summed LLM input tokens across all scored attempts (V238). */
  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private long inputTokens = 0;

  /** Summed LLM output tokens across all scored attempts (V238). */
  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private long outputTokens = 0;

  /** The Harbor job name (for `harbor upload` + the job dir). */
  @Column(name = "harbor_job_name", length = 120)
  private String harborJobName;

  /** Leaderboard submission state: null / SUBMITTING / SUBMITTED / FAILED. */
  @Column(name = "submission_status", length = 20)
  private String submissionStatus;

  @Column(name = "leaderboard_url", length = 300)
  private String leaderboardUrl;

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
