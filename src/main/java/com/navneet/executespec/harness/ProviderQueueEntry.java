package com.navneet.executespec.harness;

import java.time.Instant;

/**
 * A pending pipeline run waiting in a provider-specific queue.
 *
 * <p>Priority ordering: ENTERPRISE (0) &gt; PRO (1) &gt; FREE (2).
 * Within the same tier, FIFO ordering by submission time.
 */
public record ProviderQueueEntry(
        String runId,
        int tierPriority,
        Instant submittedAt,
        Runnable task
) implements Comparable<ProviderQueueEntry> {

    @Override
    public int compareTo(ProviderQueueEntry other) {
        int cmp = Integer.compare(this.tierPriority, other.tierPriority);
        return cmp != 0 ? cmp : this.submittedAt.compareTo(other.submittedAt);
    }

    /**
     * Maps a subscription tier name to a numeric priority (lower = higher priority).
     */
    public static int tierToPriority(String userTier) {
        return switch (userTier != null ? userTier.toUpperCase() : "FREE") {
            case "ENTERPRISE" -> 0;
            case "PRO" -> 1;
            default -> 2; // FREE and unknown tiers
        };
    }
}
