package com.navneet.executespec.api.dto;

import java.time.Instant;
import java.util.List;

public record BillingOverview(
    String billingEntityType,
    String billingEntityName,
    String currentPlan,
    String subscriptionStatus,
    Instant nextBillingDate,
    int periodUsage,
    int remainingRuns,
    int runLimit,
    String gateway,
    int topupCredits,
    List<RecentInvoice> recentInvoices) {
  public record RecentInvoice(
      String invoiceNumber, double amount, String currency, String status, Instant issuedAt) {}
}
