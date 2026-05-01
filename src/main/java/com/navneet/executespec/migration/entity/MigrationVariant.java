package com.navneet.executespec.migration.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * R-336 — concrete (options) cell of a {@link MigrationRecipe}. Most recipes have a single variant
 * because source-stack is fixed; variants exist for recipes whose target-stack options branch —
 * e.g. Mongoose → Prisma where target DB could be postgres / mysql / sqlite.
 *
 * <p>A migration "variant" is structurally simpler than a Readymade variant because source-stack is
 * determined by the recipe's parent row (we're transforming what's already there) — there's no need
 * to vary by language/framework, only by output options.
 */
@Entity
@Table(
    name = "migration_variant",
    uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id", "options_signature"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationVariant {

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  /**
   * Concise human-readable signature for the (options) cell so callers can route by name without
   * parsing the JSONB. e.g. {@code "target_db=postgres,target_version=5.x"}.
   */
  @Column(name = "options_signature", nullable = false, length = 160)
  private String optionsSignature;

  /**
   * JSON object of variant-specific options (e.g. {@code
   * {"target_db":"postgres","target_version":"5.x"}}).
   */
  @Column(nullable = false, columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String options;

  /**
   * Migration spec text with {@code {{slot_name}}} placeholders. The shared {@code SpecComposer}
   * (W5 of Readymades plan) substitutes user-supplied slot values into this template.
   */
  @Column(name = "spec_template", nullable = false, columnDefinition = "TEXT")
  private String specTemplate;

  /** Per-variant override of {@link MigrationRecipe#getAiFallbackPromptPath()}. */
  @Column(name = "ai_fallback_prompt_path", length = 255)
  private String aiFallbackPromptPath;

  @Column(name = "est_credits_low", precision = 10, scale = 2)
  private BigDecimal estCreditsLow;

  @Column(name = "est_credits_high", precision = 10, scale = 2)
  private BigDecimal estCreditsHigh;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();
}
