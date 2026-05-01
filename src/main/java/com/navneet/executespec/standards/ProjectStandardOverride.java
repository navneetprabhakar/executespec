package com.navneet.executespec.standards;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E3.1 — per-project override of an org-level standard.
 *
 * <p>Two override types per plan §5.8:
 *
 * <ul>
 *   <li>{@link OverrideType#SEVERITY_DOWNGRADE} — keep the standard active but drop severity
 *       (BLOCKING → WARNING → ADVISORY). Severity upgrades are rejected at write time per plan
 *       §5.7.
 *   <li>{@link OverrideType#NOT_APPLICABLE} — exclude the standard from this project's effective
 *       set entirely. Persistent PROJECT_ADMIN-gated.
 * </ul>
 *
 * <p>{@link #rationale} is mandatory at write time so the audit trail is meaningful (UI enforces
 * ≥30 chars per plan §5.8).
 */
@Entity
@Table(
    name = "project_standard_override",
    uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "standard_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectStandardOverride {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "standard_id", nullable = false, length = 128)
  private String standardId;

  @Column(name = "override_type", nullable = false, length = 32)
  private String overrideType;

  @Column(name = "new_severity", length = 16)
  private String newSeverity;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String rationale;

  @Column(name = "created_by_user_id", length = 64)
  private String createdByUserId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  public OverrideType overrideTypeEnum() {
    if (overrideType == null) return OverrideType.NOT_APPLICABLE;
    try {
      return OverrideType.valueOf(overrideType);
    } catch (IllegalArgumentException ex) {
      return OverrideType.NOT_APPLICABLE;
    }
  }

  public enum OverrideType {
    SEVERITY_DOWNGRADE,
    NOT_APPLICABLE
  }
}
