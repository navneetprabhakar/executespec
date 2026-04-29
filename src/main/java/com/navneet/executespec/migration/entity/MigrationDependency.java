package com.navneet.executespec.migration.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * R-336 — recipe ordering graph between {@link MigrationRecipe} rows.
 *
 * <ul>
 *   <li>{@link Kind#PREREQUISITE} — "{@code dependsOnId} must have
 *       been applied first." Hard precedence; the W4 apply path
 *       blocks until the prerequisite has shipped at least one
 *       successful run on this project.</li>
 *   <li>{@link Kind#RECOMMENDED_AFTER} — "consider running
 *       {@code dependsOnId} as a follow-up." Surfaced in the UI as
 *       a suggestion; never auto-runs.</li>
 * </ul>
 */
@Entity
@Table(name = "migration_dependency")
@IdClass(MigrationDependency.Id.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationDependency {

    public enum Kind { PREREQUISITE, RECOMMENDED_AFTER }

    @jakarta.persistence.Id
    @Column(name = "recipe_id", nullable = false)
    private UUID recipeId;

    @jakarta.persistence.Id
    @Column(name = "depends_on_id", nullable = false)
    private UUID dependsOnId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    /** JPA-required composite-key class. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        private UUID recipeId;
        private UUID dependsOnId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(recipeId, other.recipeId)
                && Objects.equals(dependsOnId, other.dependsOnId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(recipeId, dependsOnId);
        }
    }
}
