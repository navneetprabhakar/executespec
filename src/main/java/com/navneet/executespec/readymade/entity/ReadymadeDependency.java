package com.navneet.executespec.readymade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * R-327 — composition graph between {@link ReadymadeSolution} rows.
 *
 * <ul>
 *   <li>{@link #required} = {@code true} — when a user instantiates
 *       {@code solutionId}, also auto-install {@code dependsOnId} if
 *       it's not already in the project. Example: "Login with Google"
 *       depends-required-on "User entity."</li>
 *   <li>{@link #required} = {@code false} — recommended pairing only;
 *       surfaced in the UI as "consider also installing X" but never
 *       auto-installed.</li>
 * </ul>
 *
 * <p>Composite-key ({@code solutionId}, {@code dependsOnId}) per the
 * V157 PRIMARY KEY.
 */
@Entity
@Table(name = "readymade_dependency")
@IdClass(ReadymadeDependency.Id.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadymadeDependency {

    @jakarta.persistence.Id
    @Column(name = "solution_id", nullable = false)
    private UUID solutionId;

    @jakarta.persistence.Id
    @Column(name = "depends_on_id", nullable = false)
    private UUID dependsOnId;

    @Column(nullable = false)
    @Builder.Default
    private boolean required = true;

    /** JPA-required composite-key class. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        private UUID solutionId;
        private UUID dependsOnId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(solutionId, other.solutionId)
                && Objects.equals(dependsOnId, other.dependsOnId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(solutionId, dependsOnId);
        }
    }
}
