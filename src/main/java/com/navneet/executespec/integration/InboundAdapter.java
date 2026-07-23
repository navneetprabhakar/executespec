package com.navneet.executespec.integration;

import java.util.Map;
import java.util.Optional;

/**
 * Adapter for ingesting webhooks from an external integration source. Each integration implements
 * this to normalise its specific payload into an IntegrationEvent.
 */
public interface InboundAdapter {

  /** Source type string this adapter handles (e.g. "JIRA"). */
  String sourceType();

  /**
   * Verify webhook signature from HTTP headers. Throw WebhookSignatureException if invalid —
   * controller returns 401.
   */
  void verifySignature(Map<String, String> headers, String rawBody);

  /**
   * Parse raw payload JSON into an IntegrationEvent. Return empty Optional if this event type
   * should be ignored (e.g. wrong issue transition, Confluence minor edit).
   */
  Optional<IntegrationEvent> parseEvent(String rawBody);

  /**
   * Header-aware parse. Some providers (Bitbucket) carry the authoritative event type / delivery id
   * only in HTTP headers, not the body. The default delegates to the body-only form so adapters
   * that do not need headers are unaffected (RR-638).
   */
  default Optional<IntegrationEvent> parseEvent(Map<String, String> headers, String rawBody) {
    return parseEvent(rawBody);
  }

  /**
   * Extract a stable, unique event ID from the payload for idempotency. Used to deduplicate retried
   * webhook deliveries.
   */
  String extractEventId(String rawBody);

  /**
   * Header-aware idempotency id. Providers that expose a stable per-delivery id in a header (e.g.
   * Bitbucket's {@code X-Request-UUID}) can use it so distinct lifecycle events for one entity are
   * not collapsed into a single id. The default delegates to the body-only form (RR-638).
   */
  default String extractEventId(Map<String, String> headers, String rawBody) {
    return extractEventId(rawBody);
  }
}
