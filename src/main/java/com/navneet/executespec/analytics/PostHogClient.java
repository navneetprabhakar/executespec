package com.navneet.executespec.analytics;

import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import com.posthog.java.PostHog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Wave 2.1 — thin wrapper around the PostHog Java SDK.
 *
 * <p>No-op when {@code analytics.posthog.enabled=false} or {@code api-key} is blank — safe for
 * local dev without a PostHog project. Queue is flushed on shutdown via {@link #close()}.
 *
 * <p>Injected via {@link org.springframework.beans.factory.ObjectProvider} into {@link
 * EventTrackingService} so unit tests can skip it cleanly.
 */
@Component
@Slf4j
public class PostHogClient {

  @Value("${analytics.posthog.enabled:false}")
  private boolean enabled;

  @Value("${analytics.posthog.api-key:}")
  private String apiKey;

  @Value("${analytics.posthog.host:https://us.i.posthog.com}")
  private String host;

  private PostHog posthog;

  @PostConstruct
  void init() {
    if (!enabled || apiKey == null || apiKey.isBlank()) {
      log.info("PostHog disabled — enable with POSTHOG_ENABLED=true + POSTHOG_API_KEY=<key>");
      return;
    }
    this.posthog = new PostHog.Builder(apiKey).host(host).build();
    log.info("PostHog client initialized (host={})", host);
  }

  @PreDestroy
  void close() {
    if (posthog != null) {
      posthog.shutdown();
    }
  }

  /**
   * Forward an event to PostHog. Silent no-op when the client isn't configured. Exceptions are
   * caught + logged to keep the calling {@code EventTrackingService.track} async-safe.
   */
  public void capture(String distinctId, String event, Map<String, Object> properties) {
    if (posthog == null) return;
    try {
      posthog.capture(distinctId, event, properties);
    } catch (Exception e) {
      log.warn(
          "PostHog capture failed for event={} userId={}: {}", event, distinctId, e.getMessage());
    }
  }

  /**
   * Associate user-level traits (tier, signup method, etc.) with the distinct_id. Idempotent —
   * PostHog's {@code $set} merges with existing traits.
   */
  public void identify(String distinctId, Map<String, Object> traits) {
    if (posthog == null) return;
    try {
      posthog.identify(distinctId, traits, Map.of());
    } catch (Exception e) {
      log.warn("PostHog identify failed for userId={}: {}", distinctId, e.getMessage());
    }
  }

  /** For tests + diagnostics. */
  public boolean isEnabled() {
    return posthog != null;
  }
}
