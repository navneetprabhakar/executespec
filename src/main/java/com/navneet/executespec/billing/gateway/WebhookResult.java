package com.navneet.executespec.billing.gateway;

/**
 * Result of processing a payment gateway webhook.
 *
 * @param success   Whether the webhook was processed successfully
 * @param eventType The gateway event type that was handled
 * @param message   Human-readable processing message (for logging)
 */
public record WebhookResult(boolean success, String eventType, String message) {
}
