package com.navneet.executespec.billing.dto;

public record TopupOrderInitResult(
    String orderId,
    String customerId,
    String razorpayKeyId,
    long amountPaise,
    String gateway,
    String checkoutUrl,
    String stripeSessionId,
    long aiCredits) {

  public TopupOrderInitResult(
      String orderId, String customerId, String razorpayKeyId, long amountPaise) {
    this(orderId, customerId, razorpayKeyId, amountPaise, "RAZORPAY", null, null, 0);
  }
}
