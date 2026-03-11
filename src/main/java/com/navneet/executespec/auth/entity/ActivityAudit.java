package com.navneet.executespec.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "activity_audit")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityAudit {

    @Id
    private String id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "entity_type", nullable = false, length = 32)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private String entityId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "actor_id")
    private String actorId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
