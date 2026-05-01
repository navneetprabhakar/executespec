package com.navneet.executespec.billing.cost;

import java.time.Instant;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Audit log for per-run cost tracking.
 *
 * <p>Records estimated vs actual cost, credits charged, and margin for every completed run. Used
 * for pricing model iteration — after N runs we can answer: avg cost per run, budget-hit rate,
 * margin per user, cheapest provider in practice.
 */
@Entity
@Table(name = "run_cost_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunCostLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "run_id", nullable = false, length = 50)
  private String runId;

  @Column(name = "user_id", nullable = false, length = 50)
  private String userId;

  @Column(name = "org_id", length = 50)
  private String orgId;

  @Column(nullable = false, length = 20)
  private String provider;

  @Column(name = "estimated_cost", nullable = false)
  private Double estimatedCost;

  @Column(name = "actual_cost")
  private Double actualCost;

  @Column(name = "hard_cap", nullable = false)
  private Double hardCap;

  @Column(name = "credits_charged", nullable = false)
  private Integer creditsCharged;

  @Column(nullable = false, length = 30)
  private String status;

  @Column(name = "task_count", nullable = false)
  private Integer taskCount;

  @Column(name = "tasks_completed")
  private Integer tasksCompleted;

  @Column(name = "budget_hit", nullable = false)
  @Builder.Default
  private Boolean budgetHit = false;

  /** Estimated minus actual cost — our margin (positive = profit). */
  @Column(name = "margin_usd")
  private Double marginUsd;

  // ── Batch execution fields ───────────────────────────────────────────────
  /** "REALTIME" or "BATCH". */
  @Builder.Default
  @Column(name = "execution_mode", nullable = false, length = 10)
  private String executionMode = "REALTIME";

  /** Batch discount applied (0.50 = 50% off). 0 for real-time runs. */
  @Builder.Default
  @Column(name = "batch_discount")
  private Double batchDiscount = 0.0;

  /** Fraction of batch tasks that failed validation (null for real-time). */
  @Column(name = "batch_validation_fail_rate")
  private Double batchValidationFailRate;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
