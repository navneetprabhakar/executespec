package com.navneet.executespec.readymade.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * R-327 — per-user pin / saved-list of {@link ReadymadeSolution}s.
 * Surfaces on the gallery so users can keep frequently-used solutions
 * close at hand.  Composite-key ({@code userId}, {@code solutionId})
 * per the V157 PRIMARY KEY.
 */
@Entity
@Table(name = "readymade_favourite")
@IdClass(ReadymadeFavourite.Id.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReadymadeFavourite {

    @jakarta.persistence.Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @jakarta.persistence.Id
    @Column(name = "solution_id", nullable = false)
    private UUID solutionId;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** JPA-required composite-key class. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        private String userId;
        private UUID solutionId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id other)) return false;
            return Objects.equals(userId, other.userId)
                && Objects.equals(solutionId, other.solutionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, solutionId);
        }
    }
}
