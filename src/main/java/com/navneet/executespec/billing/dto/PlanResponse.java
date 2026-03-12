package com.navneet.executespec.billing.dto;

import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingPlanLimit;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public record PlanResponse(
        UUID id,
        String name,
        String tier,
        String keyMode,
        String period,
        long baseAmountInr,
        long baseAmountUsd,
        long perSeatInr,
        long perSeatUsd,
        int includedSeats,
        int runLimit,
        int annualDiscountPct,
        boolean founderPricing,
        boolean active,
        Map<String, Long> limits
) {
    public static PlanResponse from(BillingPlan plan, List<BillingPlanLimit> limits) {
        Map<String, Long> limitMap = limits.stream()
                .collect(Collectors.toMap(BillingPlanLimit::getMetric, BillingPlanLimit::getMaxValue));
        return new PlanResponse(
                plan.getId(), plan.getName(), plan.getTier(), plan.getKeyMode(),
                plan.getPeriod(), plan.getBaseAmountInr(), plan.getBaseAmountUsd(),
                plan.getPerSeatInr(), plan.getPerSeatUsd(), plan.getIncludedSeats(),
                plan.getRunLimit(), plan.getAnnualDiscountPct(), plan.isFounderPricing(),
                plan.isActive(), limitMap
        );
    }
}
