package com.navneet.executespec.billing.dto;

import java.util.UUID;

public record CreateSubscriptionRequest(
    UUID planId,
    String gateway, // "RAZORPAY" or "STRIPE" — null defaults to entity/system preference
    String successUrl, // Stripe: redirect URL on success
    String cancelUrl, // Stripe: redirect URL on cancel
    /**
     * v2.6.1 — seat count for per-seat-priced TEAM tier (1-20 self-serve). Null or 1 for PRO/MAX
     * (per-plan priced). Values on non-TEAM plans are logged-and-ignored.
     */
    Integer seats) {
  /** Backward-compatible constructor for Razorpay callers. */
  public CreateSubscriptionRequest(UUID planId) {
    this(planId, null, null, null, null);
  }

  /** Back-compat constructor before seats landed. */
  public CreateSubscriptionRequest(
      UUID planId, String gateway, String successUrl, String cancelUrl) {
    this(planId, gateway, successUrl, cancelUrl, null);
  }
}
