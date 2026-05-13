package com.navneet.executespec.codeintel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(name = "project_generated_doc")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectGeneratedDoc {

  public enum DocType {
    EXPLANATION,
    SETUP
  }

  @Id
  @GeneratedValue
  @Column(columnDefinition = "uuid")
  private UUID id;

  @Column(name = "project_id", nullable = false, length = 255)
  private String projectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "doc_type", nullable = false, length = 32)
  private DocType docType;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Column(nullable = false)
  @Builder.Default
  private Integer version = 1;

  @Column(name = "generated_by", length = 255)
  private String generatedBy;

  @Column(name = "generated_at", nullable = false)
  private Instant generatedAt;

  @Column(name = "model_used", length = 64)
  private String modelUsed;

  @Column(name = "input_tokens", nullable = false)
  @Builder.Default
  private Integer inputTokens = 0;

  @Column(name = "output_tokens", nullable = false)
  @Builder.Default
  private Integer outputTokens = 0;

  /**
   * v2.6.4 (V174) — USD cost of the LLM call that generated this doc. Populated at generation time
   * so dashboard rollups don't have to back-calculate from tokens × current prices.
   */
  @Column(name = "cost_usd", nullable = false)
  @Builder.Default
  private Double costUsd = 0.0;

  /**
   * V195 (PR-Q, 2026-05-13) — the {@link ProjectArchitectureDoc#getVersion()} this doc was
   * generated against. First half of the cache key for the short-circuit in {@code
   * ProjectDocGeneratorService.generate}: a new arch version invalidates every generated doc
   * downstream of it. Null on legacy rows (pre-V195) so the service falls through to regenerate,
   * preserving existing behaviour for unmigrated history.
   */
  @Column(name = "source_arch_version")
  private Integer sourceArchVersion;

  /**
   * V195 (PR-Q, 2026-05-13) — canonical serialised {repoFullName: sha} fingerprint of the per-repo
   * SHAs at generation time. Identical in form to {@code
   * ProjectArchitectureService.serialiseFingerprint} (sorted keys, JSON-shaped string). Second half
   * of the cache key — combined with {@link #sourceArchVersion} so a same-version arch but
   * different repo SHA still invalidates. Null on legacy rows.
   */
  @Column(name = "source_shas_fingerprint", length = 255)
  private String sourceShasFingerprint;

  @PrePersist
  void prePersist() {
    if (generatedAt == null) generatedAt = Instant.now();
  }
}
