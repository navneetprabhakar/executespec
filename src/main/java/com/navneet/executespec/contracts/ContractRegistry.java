package com.navneet.executespec.contracts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * E2.1 — JPA entity over the {@code contract_registry} table (V135).
 *
 * <p>One row per detected/declared contract. The git-side authoritative state lives at {@code
 * .executespec/contracts/produced/&lt;name&gt;.&lt;ext&gt;}; this entity is the cache + index for
 * queries that don't want to hit the customer repo on every read.
 *
 * @see ContractType for the enum stored in {@code type} column
 * @see DetectionMethod for the enum stored in {@code detection_method} column
 */
@Entity
@Table(
    name = "contract_registry",
    uniqueConstraints = @UniqueConstraint(columnNames = {"repo_id", "name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContractRegistry {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  /** The repo that *produces* this contract (where its source path lives). */
  @Column(name = "repo_id", nullable = false)
  private UUID repoId;

  /**
   * The repo where the contract is *defined* (e.g. a separate `*-contracts` repo). NULL when
   * defined == produced (the common case).
   */
  @Column(name = "defined_in_repo_id")
  private UUID definedInRepoId;

  @Column(nullable = false)
  private String name;

  /** Stored as String for forward-compat; {@link #typeEnum()} normalises. */
  @Column(nullable = false, length = 32)
  private String type;

  @Column(name = "current_registry_version", nullable = false)
  @Builder.Default
  private Integer currentRegistryVersion = 1;

  @Column(name = "current_content_hash", nullable = false, length = 64)
  private String currentContentHash;

  /**
   * Advisory only — the deterministic version number for the planner is {@link
   * #currentRegistryVersion}.
   */
  @Column(name = "declared_semver", length = 32)
  private String declaredSemver;

  @Column(name = "detected_path", length = 512)
  private String detectedPath;

  @Column(name = "detection_method", nullable = false, length = 32)
  private String detectionMethod;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  public ContractType typeEnum() {
    if (type == null) return ContractType.OPENAPI;
    try {
      return ContractType.valueOf(type);
    } catch (IllegalArgumentException ex) {
      return ContractType.OPENAPI;
    }
  }

  public DetectionMethod detectionMethodEnum() {
    if (detectionMethod == null) return DetectionMethod.AUTO_FILE;
    try {
      return DetectionMethod.valueOf(detectionMethod);
    } catch (IllegalArgumentException ex) {
      return DetectionMethod.AUTO_FILE;
    }
  }

  /**
   * Contract format. New formats can be added by writers without needing readers to recognise them
   * — {@link #typeEnum()} falls back to OPENAPI on unknown values, but it doesn't crash the API.
   */
  public enum ContractType {
    OPENAPI,
    PROTO,
    GRAPHQL,
    JSON_SCHEMA,
    ASYNCAPI,
    EVENT_SCHEMA
  }

  /** Provenance for how this contract was discovered. */
  public enum DetectionMethod {
    AUTO_FILE,
    AUTO_BUILD_GRAPH,
    AUTO_RUNTIME_FETCH,
    IMPORTED_REGISTRY,
    DECLARED
  }
}
