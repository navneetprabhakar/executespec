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
 * R-336 — abstract migration recipe (Express → Fastify, JS → TS, Mongoose → Prisma, etc.). Concrete
 * code generation lives in {@link MigrationVariant} rows referencing this row's id. Slots
 * (user-fillable parameters) live in {@link MigrationSlot}; recipe-on-recipe ordering in {@link
 * MigrationDependency}.
 *
 * <p>See {@code docs/plans/pending-migrations-plan.md} for the full product shape; this entity is
 * the W1 schema only.
 */
@Entity
@Table(name = "migration_recipe")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationRecipe {

  /**
   * Tier-gating shape: any tier can SEE the card, but FREE/PRO see PRO+ entries with a lock icon.
   */
  public enum Tier {
    FREE,
    PRO,
    TEAM,
    ENTERPRISE
  }

  public enum Source {
    FIRST_PARTY,
    COMMUNITY
  }

  public enum Status {
    LISTED,
    HIDDEN,
    ARCHIVED
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(nullable = false, unique = true, length = 64)
  private String slug;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(length = 255)
  private String tagline;

  @Column(columnDefinition = "TEXT")
  private String description;

  /** JSON object: {@code {"language":"typescript","framework":"express","min_version":"4.x"}} */
  @Column(name = "source_stack", nullable = false, columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String sourceStack;

  /** JSON object: {@code {"language":"typescript","framework":"fastify","target_version":"5.x"}} */
  @Column(name = "target_stack", nullable = false, columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String targetStack;

  /**
   * Glob predicate restricting which files this recipe touches. e.g. <code>
   * "src&#47;&#42;&#42;&#47;&#42;.{js,ts}"</code> (i.e. recursive match across {@code src/} for
   * JS/TS files). Glob-only in v1; content predicates added later if early recipes need it.
   */
  @Column(name = "affected_files_glob", nullable = false, columnDefinition = "TEXT")
  private String affectedFilesGlob;

  @Column(nullable = false, length = 64)
  private String category;

  @Column(columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String tags;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  @Builder.Default
  private Tier tier = Tier.PRO;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  @Builder.Default
  private Source source = Source.FIRST_PARTY;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  @Builder.Default
  private Status status = Status.LISTED;

  /**
   * W8 hybrid-AST hook. NULL = AI-only path; populated when a deterministic AST recipe is wired
   * alongside the AI fallback. e.g. {@code "ast-grep:./recipes/express-to-fastify.yml"}.
   */
  @Column(name = "deterministic_recipe_ref", length = 255)
  private String deterministicRecipeRef;

  /**
   * Path to the recipe-specific AI fallback prompt (e.g. {@code
   * "prompts/migrations/express-to-fastify-fallback.md"}). Loaded by the V2 worker when a file is
   * routed through the AI fallback rather than the deterministic pass.
   */
  @Column(name = "ai_fallback_prompt_path", length = 255)
  private String aiFallbackPromptPath;

  /**
   * JSON object declared by the recipe author; the reviewer's wiring pass enforces it
   * deterministically. e.g. for Express→Fastify, {@code {"preserve_routes": true,
   * "preserve_middlewares": true}}.
   */
  @Column(name = "preservation_contract", columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String preservationContract;

  @Column(name = "est_files_low")
  private Integer estFilesLow;

  @Column(name = "est_files_high")
  private Integer estFilesHigh;

  @Column(name = "est_credits_low", precision = 10, scale = 2)
  private BigDecimal estCreditsLow;

  @Column(name = "est_credits_high", precision = 10, scale = 2)
  private BigDecimal estCreditsHigh;

  @Column(name = "icon_url", columnDefinition = "TEXT")
  private String iconUrl;

  @Column(name = "thumbnail_url", columnDefinition = "TEXT")
  private String thumbnailUrl;

  @Column(name = "sample_pr_url", columnDefinition = "TEXT")
  private String samplePrUrl;

  @Column(name = "author_user_id", length = 36)
  private String authorUserId;

  @Column(name = "author_org_id", length = 36)
  private String authorOrgId;

  @Column(name = "created_at", nullable = false)
  @Builder.Default
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  @Builder.Default
  private Instant updatedAt = Instant.now();

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }
}
