package com.navneet.executespec.arch.invariants;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E3.14 — architecture invariant cached from {@code .executespec/arch/invariants.yaml} per project.
 * Customer git is source-of-truth; this row is the index for plan-time +
 * architecture-doc-regen-time checks per plan §5.12.
 */
@Entity
@Table(
    name = "arch_invariant",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "invariant_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchInvariant {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "invariant_id", nullable = false, length = 128)
  private String invariantId;

  @Column(name = "invariant_yaml", nullable = false, columnDefinition = "TEXT")
  private String invariantYaml;

  @Column(nullable = false, length = 32)
  @Builder.Default
  private String status = "ACTIVE";

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
