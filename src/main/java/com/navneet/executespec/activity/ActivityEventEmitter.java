package com.navneet.executespec.activity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E1.9 — single chokepoint for activity-feed writes. Every other service that emits a
 * customer-facing or admin-toggle event calls this service (do not write to {@link
 * ActivityFeedEntryRepository} directly).
 *
 * <h2>What it does</h2>
 *
 * <ol>
 *   <li>Always writes to the {@code activity_feed} table for the project.
 *   <li>If the event's {@link ActivityEventType#surfaces()} contains {@link
 *       ActivityEventType.Surface#AUDIT}, additionally writes to the legacy {@code activity_audit}
 *       table via {@link ActivityAuditService} so existing compliance consumers keep working
 *       unchanged.
 *   <li>Runs async so callers (controllers, async pipelines) never block on event emission.
 *       Failures are logged but never rethrown to the caller — losing one event is preferable to
 *       failing a user action.
 * </ol>
 *
 * <p>Notification preferences (per-event-type opt-in/out) ship in E4.5; this slice only persists
 * events.
 *
 * <p><b>Adding a new event type:</b> declare it in {@link ActivityEventType} with the right {@code
 * Tier} + {@code Surface}s, then call {@link #emit(UUID, ActivityEventType, UUID, String, String,
 * String, Map)} from the producer. The emitter handles dual-write + payload serialization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivityEventEmitter {

  private final ActivityFeedEntryRepository feedRepo;
  private final ActivityAuditService auditService;
  private final ObjectMapper objectMapper;

  /**
   * Emit an event. Returns immediately; the persist runs on the configured async executor (or
   * inline if @Async is disabled in tests).
   *
   * @param projectId target project (required)
   * @param type event type (drives surfaces + tier)
   * @param actorUserId user that caused the event; null = SYSTEM
   * @param targetType e.g. "RUN", "LAUNCH", "PR", "CONTRACT"
   * @param targetId opaque identifier for the target
   * @param summary human-readable summary (rendered in the feed)
   * @param structuredPayload optional structured fields; serialized to JSON
   */
  @Async
  public void emit(
      UUID projectId,
      ActivityEventType type,
      UUID actorUserId,
      String targetType,
      String targetId,
      String summary,
      Map<String, Object> structuredPayload) {
    if (projectId == null || type == null || summary == null) {
      log.warn(
          "[ActivityEventEmitter] skipping emit — missing required field "
              + "(projectId={}, type={}, summary-empty={})",
          projectId,
          type,
          summary == null);
      return;
    }
    try {
      if (type.writesTo(ActivityEventType.Surface.ACTIVITY)) {
        writeFeedEntry(
            projectId, type, actorUserId, targetType, targetId, summary, structuredPayload);
      }
      if (type.writesTo(ActivityEventType.Surface.AUDIT)) {
        writeAuditEntry(
            projectId, type, actorUserId, targetType, targetId, summary, structuredPayload);
      }
    } catch (Exception ex) {
      // Non-blocking by contract — log + swallow. Losing an event is
      // strictly better than failing the calling action.
      log.warn(
          "[ActivityEventEmitter] event {} for project {} failed to persist: {}",
          type,
          projectId,
          ex.getMessage(),
          ex);
    }
  }

  // ── Internal: feed write ────────────────────────────────────────────────

  private void writeFeedEntry(
      UUID projectId,
      ActivityEventType type,
      UUID actorUserId,
      String targetType,
      String targetId,
      String summary,
      Map<String, Object> structuredPayload) {
    Instant now = Instant.now();
    ActivityFeedEntry entry =
        ActivityFeedEntry.builder()
            .id(UUID.randomUUID())
            .projectId(projectId)
            .occurredAt(now)
            .actorUserId(actorUserId)
            .actorType(actorUserId == null ? "SYSTEM" : "USER")
            .eventType(type.name())
            .targetType(targetType)
            .targetId(targetId)
            .summary(summary)
            .payload(serializePayload(structuredPayload))
            .schemaVersion(type.schemaVersion())
            .createdAt(now)
            .build();
    feedRepo.save(entry);
  }

  // ── Internal: audit write (dual-surface events only) ────────────────────

  private void writeAuditEntry(
      UUID projectId,
      ActivityEventType type,
      UUID actorUserId,
      String targetType,
      String targetId,
      String summary,
      Map<String, Object> structuredPayload) {
    ActivityAudit audit =
        ActivityAudit.builder()
            .id(UUID.randomUUID().toString())
            .eventType(type.name())
            .entityType(targetType == null ? "PROJECT" : targetType)
            .entityId(targetId == null ? projectId.toString() : targetId)
            .orgId(null) // org_id not required for project-scoped events; left null
            .actorId(actorUserId == null ? null : actorUserId.toString())
            .newValue(summary)
            .metadata(serializePayload(structuredPayload))
            .createdAt(Instant.now())
            .build();
    auditService.record(audit);
  }

  // ── Internal: payload serialization ────────────────────────────────────

  String serializePayload(Map<String, Object> structuredPayload) {
    if (structuredPayload == null || structuredPayload.isEmpty()) return null;
    try {
      return objectMapper.writeValueAsString(structuredPayload);
    } catch (JsonProcessingException ex) {
      log.warn("[ActivityEventEmitter] could not serialize payload: {}", ex.getMessage());
      return null;
    }
  }
}
