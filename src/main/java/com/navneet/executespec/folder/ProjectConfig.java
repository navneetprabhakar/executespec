package com.navneet.executespec.folder;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E1.7 — JPA entity over the {@code project_config} table (V134). Holds the database-mode config
 * payloads for projects whose {@link
 * com.navneet.executespec.auth.entity.Project#configStorageEnum()} is {@link
 * com.navneet.executespec.auth.entity.Project.ConfigStorage#DATABASE}.
 *
 * <p>One row per {@code (project_id, config_key)}; content stored as JSONB so future config types
 * evolve without schema changes.
 */
@Entity
@Table(
    name = "project_config",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "config_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectConfig {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "config_key", nullable = false, length = 128)
  private String configKey;

  @Column(nullable = false, columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String content;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();
}
