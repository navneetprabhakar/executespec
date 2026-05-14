package com.navneet.executespec.codeintel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PR-N2 (V196, 2026-05-14) — per-file LLM-generated purpose summary, SHA-keyed cache.
 *
 * <p>One row per {@code (repoFullName, filePath, fileSha)} triple. When the file changes the SHA
 * changes, so a new row is written; the old row becomes stale until the cleanup sweeper reaps it.
 * The composite key means we never confuse a renamed file with its original — both paths get their
 * own summary row.
 *
 * <p>Cost telemetry (model + tokens + USD) is stored so the dashboard rollup can attribute the
 * codemap-side LLM spend separately from run-side spend.
 */
@Entity
@Table(name = "file_purpose_summary")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilePurposeSummary {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "repo_full_name", nullable = false, length = 255)
  private String repoFullName;

  @Column(name = "file_path", nullable = false, length = 1024)
  private String filePath;

  /**
   * Identifier of the file content this summary was generated against. We hash the content (SHA-256
   * of UTF-8 bytes) rather than relying on GitHub blob SHA — this keeps the cache stable across
   * pulls / forks / mirrors and works for non-GitHub repos in future.
   */
  @Column(name = "file_sha", nullable = false, length = 64)
  private String fileSha;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String summary;

  @Column(name = "model_used", length = 64)
  private String modelUsed;

  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private Integer inputTokens = 0;

  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private Integer outputTokens = 0;

  @Column(name = "cost_usd", nullable = false)
  @Builder.Default
  private Double costUsd = 0.0;

  @Column(name = "generated_at", nullable = false)
  @Builder.Default
  private Instant generatedAt = Instant.now();
}
