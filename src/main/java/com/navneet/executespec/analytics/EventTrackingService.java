package com.navneet.executespec.analytics;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wave 2.1 — activation funnel event tracker.
 *
 * <p>Each event fires at most once per user — re-invocations are silently no-op'd via the composite
 * PK on {@code user_activation_event}. Use {@link #track(String, EventName, Map)} for the
 * fire-and-forget path; {@link #isFirstTime(String, EventName)} lets callers probe idempotency
 * before doing expensive work.
 *
 * <p>Failures never propagate — analytics is a side-effect, not a pre-condition for signup or run
 * submission. All exceptions are caught + logged.
 *
 * <p>Full design: {@code docs/plans/activation-instrumentation-plan.md}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventTrackingService {

  private final UserActivationEventRepository eventRepo;
  private final ObjectProvider<PostHogClient> posthogProvider;
  private final ObjectMapper objectMapper;

  /**
   * Fire-and-forget event tracking. If the event has already been recorded for this user, the call
   * is a no-op. Safe to call from any pipeline stage — the async annotation keeps the caller's
   * latency flat.
   *
   * @param userId the authoritative user id (matches app_user.id)
   * @param event which funnel step
   * @param properties additional cohort dimensions — never contains PII
   */
  @Async
  public void track(String userId, EventName event, Map<String, Object> properties) {
    if (userId == null || userId.isBlank() || event == null) {
      return;
    }
    try {
      // Insert first (idempotent on the composite PK). Only forward
      // to PostHog on a true first-time insert — repeats shouldn't
      // inflate the headline funnel numbers.
      boolean inserted = insertFirstTime(userId, event, properties);
      if (!inserted) return;

      PostHogClient posthog = posthogProvider.getIfAvailable();
      if (posthog != null) {
        posthog.capture(userId, event.name(), properties);
      }
    } catch (Exception e) {
      log.warn("Event tracking failed for userId={} event={}: {}", userId, event, e.getMessage());
    }
  }

  /**
   * @return true if the event was inserted (first time); false if it already existed.
   */
  @Transactional
  boolean insertFirstTime(String userId, EventName event, Map<String, Object> properties) {
    if (eventRepo.existsByUserIdAndEventName(userId, event.name())) {
      return false;
    }
    try {
      String propsJson =
          properties == null || properties.isEmpty()
              ? null
              : objectMapper.writeValueAsString(properties);
      eventRepo.save(
          UserActivationEvent.builder()
              .userId(userId)
              .eventName(event.name())
              .occurredAt(Instant.now())
              .propertiesJson(propsJson)
              .build());
      return true;
    } catch (DataIntegrityViolationException e) {
      // Race: another concurrent track() inserted the same (userId, event).
      // Idempotency preserved; treat as "not first time for THIS call."
      log.debug(
          "Activation event race resolved via PK conflict: userId={} event={}", userId, event);
      return false;
    } catch (Exception e) {
      log.warn(
          "Activation event insert failed: userId={} event={}: {}", userId, event, e.getMessage());
      return false;
    }
  }

  /**
   * Probe whether firing {@code event} for {@code userId} would be the first-time firing. Useful
   * for callers that want to avoid building a properties map on repeat-path calls.
   */
  public boolean isFirstTime(String userId, EventName event) {
    if (userId == null || userId.isBlank() || event == null) return false;
    return !eventRepo.existsByUserIdAndEventName(userId, event.name());
  }
}
