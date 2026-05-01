package com.navneet.executespec.auth.dto;

import java.time.Instant;

public record AuditLogEntry(
    String id,
    String eventType,
    String entityType,
    String entityId,
    String orgId,
    String actorId,
    String actorEmail,
    String oldValue,
    String newValue,
    String metadata,
    Instant createdAt) {}
