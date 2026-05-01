package com.navneet.executespec.standards;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E3.1 — recorded standards violation. One row per (run|launch, standard_id, file/location).
 * Captures both the original detection and any subsequent emergency override (per plan §5.8).
 */
@Entity
@Table(name = "standards_violation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StandardsViolation {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  /**
   * {@code pipeline_run.id} when the violation was detected during a run; null when the violation
   * came from a different surface (e.g. launch-level analysis).
   */
  @Column(name = "run_id")
  private UUID runId;

  @Column(name = "launch_id")
  private UUID launchId;

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "standard_id", nullable = false, length = 128)
  private String standardId;

  @Column(name = "enforcement_type", nullable = false, length = 32)
  private String enforcementType; // LINT | FILE_PATTERN | LLM_REVIEW | AST_CHECK

  @Column(nullable = false, length = 16)
  private String severity; // effective severity at detection time

  @Column(name = "violation_summary", nullable = false, columnDefinition = "TEXT")
  private String violationSummary;

  @Column(name = "violation_payload", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String violationPayload;

  @Column(name = "detected_at", nullable = false)
  @Builder.Default
  private Instant detectedAt = Instant.now();

  @Column(name = "override_applied", nullable = false)
  @Builder.Default
  private boolean overrideApplied = false;

  @Column(name = "override_by_user_id", length = 64)
  private String overrideByUserId;

  @Column(name = "override_rationale", columnDefinition = "TEXT")
  private String overrideRationale;

  @Column(name = "override_at")
  private Instant overrideAt;
}
