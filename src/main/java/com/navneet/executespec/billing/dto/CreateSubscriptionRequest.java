package com.navneet.executespec.billing.dto;

import java.util.UUID;

public record CreateSubscriptionRequest(
        UUID planId,
        String gateway,       // "RAZORPAY" or "STRIPE" — null defaults to entity/system preference
        String successUrl,    // Stripe: redirect URL on success
        String cancelUrl      // Stripe: redirect URL on cancel
) {
    /** Backward-compatible constructor for Razorpay callers. */
    public CreateSubscriptionRequest(UUID planId) {
        this(planId, null, null, null);
    }
}
