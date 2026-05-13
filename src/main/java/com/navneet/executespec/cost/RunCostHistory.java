package com.navneet.executespec.cost;

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
 * One row per terminal run capturing the features needed to score similarity + the actual cost.
 * Used by {@link CostHistoryService} for k-NN cost estimation (Stage 2 of audit 2026-05-13).
 *
 * <p>Schema in V187. Bump {@code schemaVersion} when features change so the k-NN query can filter
 * to compatible rows.
 */
@Entity
@Table(name = "run_cost_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RunCostHistory {

  @Id
  @Column(name = "run_id")
  private String runId;

  @Column(name = "org_id")
  private String orgId;

  @Column(name = "project_id")
  private String projectId;

  @Column(name = "plan_strategy")
  private String planStrategy;

  @Column(name = "classification")
  private String classification;

  @Column(name = "task_count")
  private Integer taskCount;

  @Column(name = "repo_count")
  private Integer repoCount;

  @Column(name = "project_type")
  private String projectType;

  @Column(name = "provider")
  private String provider;

  @Column(name = "spec_length")
  private Integer specLength;

  @Column(name = "actual_cost_usd")
  private BigDecimal actualCostUsd;

  @Column(name = "actual_tokens_in")
  private Long actualTokensIn;

  @Column(name = "actual_tokens_out")
  private Long actualTokensOut;

  @Column(name = "verified_score")
  private Integer verifiedScore;

  @Column(name = "status")
  private String status;

  @Column(name = "status_reason")
  private String statusReason;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "completed_at", nullable = false)
  private Instant completedAt;

  @Column(name = "schema_version", nullable = false)
  @Builder.Default
  private Short schemaVersion = 1;
}
