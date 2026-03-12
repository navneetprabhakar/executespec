package com.navneet.executespec.billing.dto;

public record TopupOrderInitResult(
        String orderId,
        String customerId,
        String razorpayKeyId,
        long amountPaise
) {}
