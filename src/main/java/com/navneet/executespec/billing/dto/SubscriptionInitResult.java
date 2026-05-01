package com.navneet.executespec.billing.dto;

public record SubscriptionInitResult(
    String gatewaySubscriptionId,
    String gatewayCustomerId,
    String shortUrl,
    String razorpayKeyId,
    // Stripe-specific fields
    String gateway,
    String checkoutUrl,
    String sessionId) {
  /** Razorpay-compatible constructor (backward compat). */
  public SubscriptionInitResult(
      String gatewaySubscriptionId,
      String gatewayCustomerId,
      String shortUrl,
      String razorpayKeyId) {
    this(gatewaySubscriptionId, gatewayCustomerId, shortUrl, razorpayKeyId, "RAZORPAY", null, null);
  }
}
