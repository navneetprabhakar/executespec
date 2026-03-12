package com.navneet.executespec.billing.dto;

public record TopupVerifyRequest(
        String razorpayOrderId,
        String razorpayPaymentId,
        String razorpaySignature
) {}
