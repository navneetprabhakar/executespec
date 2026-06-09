package com.navneet.executespec.benchmark;

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
 * One event in a {@link BenchmarkRun}'s stream, as published by the external verifier (Harbor) and
 * ingested by {@code BenchmarkController}. Persisted for replay + audit and re-broadcast over SSE so
 * the in-login {@code /benchmark} dashboard updates live. V236.
 */
@Entity
@Table(name = "benchmark_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BenchmarkEvent {

  @Id private String id;

  @Column(name = "benchmark_run_id", nullable = false, length = 64)
  private String benchmarkRunId;

  /** Per-run ordering. */
  @Column(nullable = false)
  private int seq;

  /**
   * RUN_STARTED / TASK_SCORED / RUN_PROGRESS / RUN_DONE / SUBMISSION_STARTED / SUBMITTED /
   * SUBMISSION_FAILED.
   */
  @Column(nullable = false, length = 40)
  private String type;

  @Column(columnDefinition = "text")
  private String message;

  /** Free-form JSON payload (task, reward, url, …). */
  @Column(columnDefinition = "text")
  private String payload;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
