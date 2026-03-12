package com.navneet.executespec.billing.dto;

public record SubscriptionInitResult(
        String gatewaySubscriptionId,
        String gatewayCustomerId,
        String shortUrl,
        String razorpayKeyId
) {}
