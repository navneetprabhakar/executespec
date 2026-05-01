package com.navneet.executespec.migration.entity;

import java.util.UUID;

import jakarta.persistence.*;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * R-336 — a parameter the user fills before instantiating a {@link MigrationRecipe}. Mirrors {@code
 * ReadymadeSlot} semantics — the {@code SlotValidator} + {@code SpecComposer} service classes
 * (Readymades W5) are intended to be shared across both products, which is why the slot-type enum
 * here matches Readymades exactly.
 */
@Entity
@Table(
    name = "migration_slot",
    uniqueConstraints = @UniqueConstraint(columnNames = {"recipe_id", "name"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationSlot {

  public enum Type {
    TEXT,
    SELECT,
    SECRET,
    REPO_PICKER,
    FILE_PATH,
    NUMBER,
    BOOLEAN
  }

  @Id @Builder.Default private UUID id = UUID.randomUUID();

  @Column(name = "recipe_id", nullable = false)
  private UUID recipeId;

  @Column(nullable = false, length = 64)
  private String name;

  @Column(name = "display_name", nullable = false, length = 160)
  private String displayName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private Type type;

  @Column(nullable = false)
  @Builder.Default
  private boolean required = false;

  @Column(name = "default_value", columnDefinition = "TEXT")
  private String defaultValue;

  /** For {@link Type#SELECT}: JSON array of allowed values. */
  @Column(columnDefinition = "jsonb")
  @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
  private String options;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "display_order", nullable = false)
  @Builder.Default
  private int displayOrder = 0;
}
