package com.navneet.executespec.migration.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * R-336 — sample-diff + cost-estimate cache for the W3 two-phase halt-and-inspect plan-gate.
 *
 * <p>Sample-diff generation costs an LLM call (~$0.05/recipe); we cache it per ({@code recipeId},
 * {@code stackSignature}) so the same recipe + same stack signature returns the same sample for
 * every customer who previews it. The cache survives across customers; it's a global asset.
 *
 * <p>{@link #stackSignature} captures major-version of the detected stack but not minor. e.g.
 * "typescript@5,express@4" — minor / patch deltas don't invalidate the cache, but a major bump of
 * either component does.
 */
@Entity
@Table(name = "migration_dry_run_cache")
@IdClass(MigrationDryRunCache.Id.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationDryRunCache {

  @jakarta.persistence.Id
  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @jakarta.persistence.Id
  @Column(name = "stack_signature", nullable = false, length = 255)
  private String stackSignature;

  /** Truncated (~10 KB cap) sample diff from one representative file. */
  @Column(name = "sample_diff", columnDefinition = "TEXT")
  private String sampleDiff;

  @Column(name = "est_files_low")
  private Integer estFilesLow;

  @Column(name = "est_files_high")
  private Integer estFilesHigh;

  @Column(name = "est_cost_low", precision = 10, scale = 2)
  private BigDecimal estCostLow;

  @Column(name = "est_cost_high", precision = 10, scale = 2)
  private BigDecimal estCostHigh;

  @Column(name = "generated_at", nullable = false)
  @Builder.Default
  private Instant generatedAt = Instant.now();

  /** JPA-required composite-key class. */
  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Id implements Serializable {
    private UUID recipeId;
    private String stackSignature;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Id other)) return false;
      return Objects.equals(recipeId, other.recipeId)
          && Objects.equals(stackSignature, other.stackSignature);
    }

    @Override
    public int hashCode() {
      return Objects.hash(recipeId, stackSignature);
    }
  }
}
