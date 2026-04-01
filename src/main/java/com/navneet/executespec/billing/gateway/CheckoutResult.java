package com.navneet.executespec.billing.gateway;

/**
 * Result of creating a checkout session at a payment gateway.
 *
 * @param url       The checkout URL to redirect the user to
 * @param sessionId The gateway-specific session/subscription ID
 */
public record CheckoutResult(String url, String sessionId) {
}
