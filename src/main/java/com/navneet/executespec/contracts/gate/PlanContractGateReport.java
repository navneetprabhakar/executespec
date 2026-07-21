package com.navneet.executespec.contracts.gate;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RR-819 (V329) — the durable plan-approval contract gate report.
 *
 * <p>Before this row existed, "the user must type BREAKING to acknowledge a consumer-breaking
 * contract change" was a sentence in {@link PlanBreakingChangeReport}'s javadoc and a {@code
 * [REQUIRES ACK]} suffix on a MINOR issue produced AFTER the code was already written and pushed.
 * Nothing consumed {@code requiresTypedAck}; nothing could refuse anything.
 *
 * <p>This row is written while the run sits at {@code AWAITING_APPROVAL} and is the server's own
 * record of what the approver was shown. {@code reportDigest} binds the acknowledgement to that
 * exact content, so an acknowledgement collected against one set of contracts cannot be replayed
 * against a different one.
 */
@Entity
@Table(name = "plan_contract_gate_report")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanContractGateReport {

  @Id
  @Column(name = "run_id", nullable = false, length = 64)
  private String runId;

  @Column(name = "project_id", length = 64)
  private String projectId;

  /** Serialized {@link PlanContractGateService.GateReport} — what the approver must be shown. */
  @Column(name = "report_json", nullable = false, columnDefinition = "text")
  private String reportJson;

  /** SHA-256 (hex) of {@link #reportJson}. The acknowledgement is bound to this exact value. */
  @Column(name = "report_digest", nullable = false, length = 64)
  private String reportDigest;

  /** True when at least one intended contract change has a declared consumer in this project. */
  @Column(name = "requires_typed_ack", nullable = false)
  private boolean requiresTypedAck;

  @Column(name = "contract_count", nullable = false)
  private int contractCount;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "acknowledged_at")
  private Instant acknowledgedAt;

  @Column(name = "acknowledged_by_user_id", length = 64)
  private String acknowledgedByUserId;

  /** The exact text the approver typed. Persisted verbatim so the record is auditable. */
  @Column(name = "acknowledgement_text", length = 64)
  private String acknowledgementText;
}
