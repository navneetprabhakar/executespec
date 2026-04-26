package com.navneet.executespec.activity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * E1.10 — JPA entity over the {@code activity_feed} partitioned table.
 *
 * <p>Composite primary key {@code (id, occurred_at)} matches the V133
 * schema — PostgreSQL requires the partition key in the primary key.
 * Spring Data wraps the composite key in {@link ActivityFeedEntryId}.
 *
 * <p>Reads happen via {@link ActivityFeedEntryRepository} with explicit
 * {@code project_id} + {@code occurred_at} filters so PostgreSQL can
 * prune partitions; writes go through {@link ActivityEventEmitter},
 * never directly.
 */
@Entity
@Table(name = "activity_feed")
@IdClass(ActivityFeedEntryId.class)
@Data
// Adversarial review fix (E1.8/9/10 follow-up): @Data generates equals/hashCode
// over EVERY field. With a JPA composite key (@IdClass) that breaks Hibernate
// identity semantics — two entities with the same (id, occurredAt) but different
// summary/payload would compare unequal, corrupting caches + cascade ops.
// Constrain equality to the composite primary key only.
@EqualsAndHashCode(of = {"id", "occurredAt"})
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityFeedEntry {

    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Id
    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_type", nullable = false, length = 16)
    private String actorType;       // USER | SYSTEM

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;

    @Column(name = "schema_version", nullable = false)
    @Builder.Default
    private Integer schemaVersion = 1;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
