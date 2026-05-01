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

  @Column(name = "generated_at", nullable = false)
  @Builder.Default
  private Instant generatedAt = Instant.now();
}
