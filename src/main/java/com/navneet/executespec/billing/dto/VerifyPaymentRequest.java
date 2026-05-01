package com.navneet.executespec.billing.dto;

public record VerifyPaymentRequest(
    String razorpaySubscriptionId, String razorpayPaymentId, String razorpaySignature) {}
