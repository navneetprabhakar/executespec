package com.navneet.executespec.codeintel.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Pilot v3.0 follow-up — codemap generated at submission time for paid-tier existing-repo runs.
 * Per-run, not per-project (single-shot legacy flow has no project context).
 */
@Entity
@Table(name = "run_code_map")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunCodeMap {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "run_id", nullable = false)
  private String runId;

  @Column(name = "repo_url", nullable = false, length = 500)
  private String repoUrl;

  @Column(name = "raw_content", columnDefinition = "TEXT", nullable = false)
  private String rawContent;

  /** LLM-compressed summary injected into planner + assessment prompts. */
  @Column(columnDefinition = "TEXT")
  private String summary;

  @Column(name = "file_count", nullable = false)
  @Builder.Default
  private Integer fileCount = 0;

  @Column(name = "route_count", nullable = false)
  @Builder.Default
  private Integer routeCount = 0;

  @Column(name = "generated_from_sha", length = 40)
  private String generatedFromSha;

  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private Integer inputTokens = 0;

  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private Integer outputTokens = 0;

  @Column(name = "actual_cost_usd", precision = 10, scale = 6)
  private BigDecimal actualCostUsd;

  @Column(name = "generated_at", nullable = false)
  @Builder.Default
  private Instant generatedAt = Instant.now();
}
