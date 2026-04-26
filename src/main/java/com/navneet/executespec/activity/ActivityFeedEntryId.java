package com.navneet.executespec.activity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Composite primary key for {@link ActivityFeedEntry}. The
 * {@code activity_feed} table is partitioned by {@code occurred_at}; the
 * partition key must be part of the primary key per PostgreSQL rules.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ActivityFeedEntryId implements Serializable {
    private UUID id;
    private Instant occurredAt;
}
