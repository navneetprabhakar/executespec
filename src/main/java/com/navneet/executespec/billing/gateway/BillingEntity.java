package com.navneet.executespec.billing.gateway;

/**
 * Abstraction over AppUser and Org for billing purposes. Both can be the "billing entity" — the
 * entity that owns the subscription and is invoiced. If a user belongs to an org, the org is the
 * billing entity.
 */
public interface BillingEntity {

  /** The entity's primary key (user ID or org ID). */
  String getId();

  /** Current tier: FREE, PRO, ENTERPRISE. */
  String getTier();

  /** Preferred payment gateway: "razorpay" or "stripe". Null defaults to system default. */
  String getPaymentGateway();

  /** Stripe customer ID if previously created. Null if never used Stripe. */
  String getStripeCustomerId();

  /** Set the Stripe customer ID after first Stripe checkout. */
  void setStripeCustomerId(String stripeCustomerId);

  /** Entity type for polymorphic references: "USER" or "ORG". */
  String getEntityType();
}
