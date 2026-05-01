package com.navneet.executespec.project.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Join row attaching a {@link ProjectRepo} to a project with per-link relationship metadata (e.g.,
 * "calls api-server", "depends on shared-models").
 */
@Entity
@Table(name = "project_repo_link")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRepoLink {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false)
  private String projectId;

  @Column(name = "repo_id", nullable = false)
  private UUID repoId;

  /** JSON array of relationship strings; null if unset. */
  @Column(name = "relationships", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String relationships;

  @Column(name = "linked_at", nullable = false)
  @Builder.Default
  private Instant linkedAt = Instant.now();

  @Column(name = "linked_by", nullable = false)
  private String linkedBy;
}
