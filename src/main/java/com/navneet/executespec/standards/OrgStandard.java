package com.navneet.executespec.standards;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E3.1 — single org-level standard. {@link #standardYaml} stores the full YAML body (parsed at read
 * time by E3.2's parser). The YAML's own {@code id} field is mirrored in {@link #standardId} so the
 * {@code (org_id, standard_id)} lookup that drives merge + override stays index-friendly.
 *
 * <p>{@link #orgLevelLocked} = the project-level cannot override (any attempted override is logged
 * + ignored per plan §5.7).
 */
@Entity
@Table(
    name = "org_standard",
    uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "standard_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgStandard {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "org_id", nullable = false, length = 64)
  private String orgId;

  @Column(name = "standard_id", nullable = false, length = 128)
  private String standardId;

  @Column(name = "standard_yaml", nullable = false, columnDefinition = "TEXT")
  private String standardYaml;

  @Column(name = "org_level_locked", nullable = false)
  @Builder.Default
  private boolean orgLevelLocked = false;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();
}
