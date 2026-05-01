package com.navneet.executespec.contracts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E2.1 — drift event between {@code .executespec/contracts/produced/&lt;name&gt;.yaml} and the
 * source path the contract was originally detected from.
 *
 * <p>Created by E2.8 drift-detection on demand or implicitly during plan generation. Resolved by
 * either {@link ResolvedAction#REFRESHED} (writer opens a PR updating the .executespec/ file) or
 * {@link ResolvedAction#DISMISSED} (timestamped + audit-logged).
 */
@Entity
@Table(name = "contract_drift_event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractDriftEvent {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "contract_id", nullable = false)
  private UUID contractId;

  @Column(name = "detected_at", nullable = false)
  @Builder.Default
  private Instant detectedAt = Instant.now();

  @Column(name = "source_diff", columnDefinition = "TEXT")
  private String sourceDiff;

  /** Stored as String for forward-compat. NULL = unresolved. */
  @Column(name = "resolved_action", length = 32)
  private String resolvedAction;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  @Column(name = "resolved_by_user_id", length = 64)
  private String resolvedByUserId;

  @Column(name = "resolved_rationale", columnDefinition = "TEXT")
  private String resolvedRationale;

  public ResolvedAction resolvedActionEnum() {
    if (resolvedAction == null || resolvedAction.isBlank()) return null;
    try {
      return ResolvedAction.valueOf(resolvedAction);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public enum ResolvedAction {
    REFRESHED,
    DISMISSED
  }
}
