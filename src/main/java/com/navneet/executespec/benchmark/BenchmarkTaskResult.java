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
 * written by the external verifier and ingested by {@link BenchmarkTrackingService}. {@link
 * #esRunId} links to the {@code pipeline_run} that executed it (so execution detail + score live
 * together). V236.
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

  /** Harness outcome label, independent of reward math (e.g. PASSED, FAILED, TIMEOUT). */
  @Column(name = "outcome", length = 40)
  private String outcome;

  /** Human-readable harness/verifier/infra reason when available. */
  @Column(name = "outcome_reason", length = 500)
  private String reason;

  /** NONE / TIMEOUT / INFRASTRUCTURE / VERIFIER / AGENT_OR_TEST_FAILURE / UNKNOWN. */
  @Column(name = "failure_classification", nullable = false, length = 40)
  @Builder.Default
  private String failureClassification = "UNKNOWN";

  /** Stable operator-visible location of the attempt trajectory artifact. */
  @Column(name = "trajectory_location", length = 1000)
  private String trajectoryLocation;

  /** SHA-256 of the exact trajectory bytes at {@link #trajectoryLocation}. */
  @Column(name = "trajectory_sha256", length = 64)
  private String trajectorySha256;

  @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
  @Builder.Default
  private BigDecimal costUsd = BigDecimal.ZERO;

  /** LLM input tokens for this attempt's ExecuteSpec run (V238). */
  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private long inputTokens = 0;

  /** LLM output tokens for this attempt's ExecuteSpec run (V238). */
  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private long outputTokens = 0;

  /**
   * Physical provider that actually served this attempt, derived server-side from {@code
   * run_step_usage} via {@link #esRunId} (V304). Null for historical rows and for MIXED attempts.
   * Never populated from a client-supplied event field.
   */
  @Column(name = "provider", length = 40)
  private String provider;

  /**
   * Physical model that actually served this attempt. Same derivation rules as {@link #provider}.
   */
  @Column(name = "model", length = 120)
  private String model;

  /**
   * LEDGER / MIXED / UNATTRIBUTED (V304). MIXED means several distinct routes served one attempt
   * and must never be collapsed to a single provider — that collapse is exactly what this field
   * exists to prevent.
   */
  @Column(name = "route_attribution", length = 24)
  private String routeAttribution;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
