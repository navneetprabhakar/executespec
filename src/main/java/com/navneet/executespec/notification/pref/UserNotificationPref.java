package com.navneet.executespec.notification.pref;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * E4.5 — per-user opt-in/out for a specific (event_type, channel) pair.
 * Absence of a row falls back to the per-event-type default in
 * {@link NotificationPrefDefaults}.
 */
@Entity
@Table(name = "user_notification_pref", uniqueConstraints =
        @UniqueConstraint(columnNames = {"user_id", "event_type", "channel"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserNotificationPref {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false, length = 32)
    private String channel;     // IN_APP | EMAIL

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
