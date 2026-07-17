package com.navneet.executespec.project.dto;

import java.time.Instant;
import java.util.UUID;

/** Lease state after a heartbeat renewal. */
public record LocalSourceHeartbeatResponse(
    UUID localFolderId,
    String localConnectionStatus,
    Instant sourceLastSeenAt,
    Instant sourceLeaseExpiresAt,
    String sourceRevision) {}
