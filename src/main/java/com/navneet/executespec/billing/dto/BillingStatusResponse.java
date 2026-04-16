package com.navneet.executespec.billing.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BillingStatusResponse(
        SubscriptionInfo subscription,
        PlanResponse plan,
        List<UsageInfo> usage,
        BonusRunsInfo bonusRuns,
        /** v2.5.8 — Total USD spent on LLM calls during the current billing
         *  period (pipeline_run + execution_run). Null if billing not initialised. */
        Double spentUsd
) {
    public record SubscriptionInfo(
            UUID id,
            String status,
            String paymentGateway,
            Instant currentPeriodEnd,
            int totalSeats,
            int extraSeats,
            boolean cancelAtPeriodEnd,
            boolean founderPricing,
            String termsVersion
    ) {}

    public record UsageInfo(
            String metric,
            long current,
            long max,
            double pctUsed
    ) {}

    public record BonusRunsInfo(
            long available,
            List<TopupInfo> topups
    ) {}

    public record TopupInfo(
            UUID id,
            int quantity,
            int remaining,
            String source,
            String status,
            Instant expiresAt,
            Instant createdAt
    ) {}
}
