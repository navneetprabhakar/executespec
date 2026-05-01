package com.navneet.executespec.contracts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E2.1 — consumer declaration for a {@link ContractRegistry} row.
 *
 * <p>Project-scoped on purpose: the same {@code (contract_id, consumer_repo_id)} pair can be
 * declared independently in two different projects (preserves the confidentiality escape hatch from
 * plan §0).
 *
 * <p>Drives the breaking-change UX in E2.12 — a breaking change to a contract is only friction at
 * the plan-approval gate when there is at least one declared consumer in this project.
 */
@Entity
@Table(
    name = "contract_consumer",
    uniqueConstraints =
        @UniqueConstraint(
            columnNames = {"contract_id", "consumer_repo_id", "declared_in_project_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractConsumer {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "contract_id", nullable = false)
  private UUID contractId;

  @Column(name = "consumer_repo_id", nullable = false)
  private UUID consumerRepoId;

  @Column(name = "declared_version_constraint", length = 64)
  private String declaredVersionConstraint;

  @Column(name = "declared_in_project_id", nullable = false, length = 64)
  private String declaredInProjectId;

  @Column(name = "declared_at", nullable = false)
  @Builder.Default
  private Instant declaredAt = Instant.now();

  @Column(name = "declared_by_user_id", length = 64)
  private String declaredByUserId;
}
