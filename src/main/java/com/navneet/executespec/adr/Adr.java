package com.navneet.executespec.adr;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E3.12 — Architecture Decision Record cached from {@code .executespec/arch/adrs/<NNNN>-<slug>.md}.
 * Customer git is source-of-truth; this row is the index for the planner-injection path (E3.13).
 *
 * <p>Stored as String (forward-compat) for {@link #status}; lifecycle: PROPOSED → ACCEPTED →
 * DEPRECATED / SUPERSEDED. Only ACCEPTED ADRs flow into planner context.
 */
@Entity
@Table(
    name = "adr",
    uniqueConstraints = @UniqueConstraint(columnNames = {"repo_id", "adr_id_in_repo"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Adr {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "repo_id", nullable = false)
  private UUID repoId;

  @Column(name = "adr_id_in_repo", nullable = false)
  private Integer adrIdInRepo;

  @Column(nullable = false)
  private String title;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "decision_date", nullable = false)
  private LocalDate decisionDate;

  @Column(columnDefinition = "TEXT")
  private String deciders;

  @Column(columnDefinition = "TEXT")
  private String supersedes;

  @Column(name = "superseded_by", columnDefinition = "TEXT")
  private String supersededBy;

  @Column(name = "related_adrs", columnDefinition = "TEXT")
  private String relatedAdrs;

  @Column(name = "related_standards", columnDefinition = "TEXT")
  private String relatedStandards;

  @Column(columnDefinition = "TEXT")
  private String tags;

  @Column(columnDefinition = "TEXT")
  private String body;

  @Column(name = "file_path", length = 512)
  private String filePath;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  public Status statusEnum() {
    if (status == null) return Status.PROPOSED;
    try {
      return Status.valueOf(status);
    } catch (IllegalArgumentException ex) {
      return Status.PROPOSED;
    }
  }

  public enum Status {
    PROPOSED,
    ACCEPTED,
    DEPRECATED,
    SUPERSEDED
  }
}
