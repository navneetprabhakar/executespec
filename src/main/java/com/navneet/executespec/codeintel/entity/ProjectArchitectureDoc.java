package com.navneet.executespec.codeintel.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "project_architecture_doc")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectArchitectureDoc {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false)
  private String projectId;

  @Column(nullable = false)
  @Builder.Default
  private Integer version = 1;

  @Column(columnDefinition = "TEXT", nullable = false)
  private String content;

  @Column(name = "user_annotations", columnDefinition = "TEXT")
  private String userAnnotations;

  /** JSON map {repoFullName: sha} captured at generation time. */
  @Column(name = "generated_from_shas", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String generatedFromShas;

  /**
   * V192 (PR-M, 2026-05-13) — JSON map {repoFullName: branch} captured alongside the SHAs. Lets
   * {@link com.navneet.executespec.codeintel.StalenessService} probe the SAME branch the codemap
   * came from instead of always defaultBranch — fixes the permanent-STALE-badge bug on projects
   * regenerated with a {@code ?branch=&lt;non-default&gt;} override. Nullable: legacy pre-V192 rows
   * fall back to defaultBranch (existing behaviour).
   */
  @Column(name = "generated_from_branches", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String generatedFromBranches;

  @Column(name = "generated_at", nullable = false)
  @Builder.Default
  private Instant generatedAt = Instant.now();

  /**
   * v2.6.4 (V174) — USD cost of the combined-arch LLM synthesis. Populated by {@link
   * com.navneet.executespec.codeintel.ArchitectureDocGenerator} after each generation; dashboard
   * rollup sums this across versions for the project total.
   */
  @Column(name = "cost_usd", nullable = false)
  @Builder.Default
  private Double costUsd = 0.0;
}
