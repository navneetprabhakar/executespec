package com.navneet.executespec.codeintel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

/**
 * Per-repo HLD + LLD architecture doc. Produced in Step 2 of the 3-step arch generation pipeline:
 * codemap → per-repo arch doc → combined architecture doc.
 *
 * <p>Authoritative in DB; disk mirror at {@code
 * workspace/projects/<id>/repos/<shortName>/architecture.md}.
 */
@Entity
@Table(name = "repo_architecture_doc")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoArchitectureDoc {

  @Id
  @GeneratedValue
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "project_id", nullable = false, length = 255)
  private String projectId;

  @Column(name = "repo_id", nullable = false, columnDefinition = "uuid")
  private UUID repoId;

  @Column(nullable = false)
  @Builder.Default
  private Integer version = 1;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  /** Pointer to the codemap this doc was derived from (audit trail). */
  @Column(name = "source_code_map_id", columnDefinition = "uuid")
  private UUID sourceCodeMapId;

  /** Default-branch HEAD at generation time (from the source codemap). */
  @Column(name = "generated_from_sha", length = 64)
  private String generatedFromSha;

  @Column(name = "generated_at", nullable = false)
  private Instant generatedAt;

  @Column(name = "generated_by", length = 255)
  private String generatedBy;

  @Column(name = "model_used", length = 64)
  private String modelUsed;

  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private Integer inputTokens = 0;

  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private Integer outputTokens = 0;

  /**
   * v2.6.4 (V174) — USD cost of the LLM call that generated this per-repo arch doc. Populated from
   * the pricing matrix at generation time so dashboard rollups don't have to back-calculate from
   * tokens × today's prices. Defaults to 0 for historical rows (V174 sets the column default).
   */
  @Column(name = "cost_usd", nullable = false)
  @Builder.Default
  private Double costUsd = 0.0;

  @PrePersist
  void prePersist() {
    if (generatedAt == null) generatedAt = Instant.now();
  }
}
