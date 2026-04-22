package com.navneet.executespec.analytics;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * Wave 2.1 — records the first firing of each {@link EventName} per user.
 *
 * <p>Composite PK (user_id, event_name) is the "fire once" guarantee — see
 * {@code EventTrackingService.isFirstTime}. Re-insertion is a no-op under
 * {@code INSERT ... ON CONFLICT DO NOTHING} semantics (JPA equivalent: catch
 * {@link org.springframework.dao.DataIntegrityViolationException}).
 */
@Entity
@Table(name = "user_activation_event")
@IdClass(UserActivationEvent.PK.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActivationEvent {

    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Id
    @Column(name = "event_name", nullable = false, length = 32)
    private String eventName;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    /** JSON-encoded properties forwarded to PostHog. Nullable. */
    @Column(name = "properties_json", columnDefinition = "jsonb")
    private String propertiesJson;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private String userId;
        private String eventName;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(userId, pk.userId) && Objects.equals(eventName, pk.eventName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, eventName);
        }
    }
}
