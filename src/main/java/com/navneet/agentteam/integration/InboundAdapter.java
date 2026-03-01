package com.navneet.agentteam.integration;

import java.util.Map;
import java.util.Optional;

/**
 * Adapter for ingesting webhooks from an external integration source.
 * Each integration implements this to normalise its specific payload into an IntegrationEvent.
 */
public interface InboundAdapter {

    /** Source type string this adapter handles (e.g. "JIRA"). */
    String sourceType();

    /**
     * Verify webhook signature from HTTP headers. Throw WebhookSignatureException
     * if invalid — controller returns 401.
     */
    void verifySignature(Map<String, String> headers, String rawBody);

    /**
     * Parse raw payload JSON into an IntegrationEvent.
     * Return empty Optional if this event type should be ignored
     * (e.g. wrong issue transition, Confluence minor edit).
     */
    Optional<IntegrationEvent> parseEvent(String rawBody);

    /**
     * Extract a stable, unique event ID from the payload for idempotency.
     * Used to deduplicate retried webhook deliveries.
     */
    String extractEventId(String rawBody);
}
