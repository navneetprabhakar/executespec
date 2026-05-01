package com.navneet.executespec.billing.gateway;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Routes to the correct PaymentGateway implementation based on the billing entity's preferred
 * gateway and system feature flags.
 *
 * <p>Resolution order: 1. Entity's preferred gateway (if set and enabled) 2. System default gateway
 * (billing.default-gateway) 3. Razorpay fallback
 */
@Service
@Slf4j
public class PaymentGatewayRouter {

  private final PaymentGateway stripeGateway;
  private final PaymentGateway razorpayGateway;

  @Value("${billing.default-gateway:razorpay}")
  private String defaultGateway;

  @Value("${billing.stripe.enabled:false}")
  private boolean stripeEnabled;

  @Value("${billing.razorpay.enabled:true}")
  private boolean razorpayEnabled;

  public PaymentGatewayRouter(
      StripePaymentGateway stripeGateway,
      @Qualifier("razorpayPaymentGateway") PaymentGateway razorpayGateway) {
    this.stripeGateway = stripeGateway;
    this.razorpayGateway = razorpayGateway;
  }

  /** Resolve the gateway for the given billing entity. */
  public PaymentGateway resolve(BillingEntity entity) {
    String preferred = entity != null ? entity.getPaymentGateway() : null;
    return resolveByName(preferred);
  }

  /** Resolve gateway by name string (e.g., "stripe", "razorpay"). */
  public PaymentGateway resolveByName(String gatewayName) {
    if (gatewayName != null && !gatewayName.isBlank()) {
      String normalized = gatewayName.toLowerCase().trim();
      if ("stripe".equals(normalized) && stripeEnabled) {
        return stripeGateway;
      }
      if ("razorpay".equals(normalized) && razorpayEnabled) {
        return razorpayGateway;
      }
      log.warn(
          "Requested gateway '{}' not enabled, falling back to default '{}'",
          gatewayName,
          defaultGateway);
    }

    // Fall back to system default
    return resolveDefault();
  }

  /** Get the Stripe gateway directly (for webhook handling). */
  public PaymentGateway stripe() {
    return stripeGateway;
  }

  /** Get the Razorpay gateway directly (for webhook handling). */
  public PaymentGateway razorpay() {
    return razorpayGateway;
  }

  /** Check if Stripe is enabled. */
  public boolean isStripeEnabled() {
    return stripeEnabled;
  }

  /** Check if Razorpay is enabled. */
  public boolean isRazorpayEnabled() {
    return razorpayEnabled;
  }

  private PaymentGateway resolveDefault() {
    if ("stripe".equalsIgnoreCase(defaultGateway) && stripeEnabled) {
      return stripeGateway;
    }
    if (razorpayEnabled) {
      return razorpayGateway;
    }
    if (stripeEnabled) {
      return stripeGateway;
    }
    throw new IllegalStateException(
        "No payment gateway is enabled. "
            + "Set billing.stripe.enabled=true or billing.razorpay.enabled=true");
  }
}
