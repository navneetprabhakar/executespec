package com.navneet.executespec.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.billing.client.RazorpayRestClient;
import com.navneet.executespec.billing.entity.BillingPlan;
import com.navneet.executespec.billing.entity.BillingPlanLimit;
import com.navneet.executespec.billing.repository.BillingPlanLimitRepository;
import com.navneet.executespec.billing.repository.BillingPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PlanSeederService {

    private final RazorpayRestClient razorpayClient;
    private final BillingPlanRepository planRepo;
    private final BillingPlanLimitRepository limitRepo;

    private static final String[] ALL_PRODUCTS = {"CODE_GEN", "PIPELINE_GEN", "PILOTSRE", "IAC_GEN"};

    /**
     * Seeds all 8 paid plans in Razorpay + local DB.
     * Call via a one-time REST endpoint or @PostConstruct in dev profile.
     * Idempotent: skips if plans already exist.
     */
    @Transactional
    public void seedAllPlans() {
        if (planRepo.findByActiveTrue().size() > 1) {
            log.info("Plans already seeded ({} plans exist), skipping", planRepo.count());
            return;
        }

        // ── STARTER BYOK ──
        seedPlan("Starter BYOK Monthly", "STARTER", "BYOK", "MONTHLY",
                "monthly", 1, 149900, 1800, 1, 50, 10, 500, 0);
        seedPlan("Starter BYOK Annual", "STARTER", "BYOK", "ANNUAL",
                "yearly", 1, 1438800, 16800, 1, 50, 10, 500, 20);

        // ── STARTER PLATFORM ──
        seedPlan("Starter Platform Monthly", "STARTER", "PLATFORM", "MONTHLY",
                "monthly", 1, 299900, 3600, 1, 20, 10, 500, 0);
        seedPlan("Starter Platform Annual", "STARTER", "PLATFORM", "ANNUAL",
                "yearly", 1, 2878800, 34800, 1, 20, 10, 500, 20);

        // ── PRO BYOK ──
        seedPlan("Pro BYOK Monthly", "PRO", "BYOK", "MONTHLY",
                "monthly", 1, 999900, 11900, 5, 500, 50, 10240, 0);
        seedPlan("Pro BYOK Annual", "PRO", "BYOK", "ANNUAL",
                "yearly", 1, 9598800, 114000, 5, 500, 50, 10240, 20);

        // ── PRO PLATFORM ──
        seedPlan("Pro Platform Monthly", "PRO", "PLATFORM", "MONTHLY",
                "monthly", 1, 2499900, 29900, 5, 150, 50, 10240, 0);
        seedPlan("Pro Platform Annual", "PRO", "PLATFORM", "ANNUAL",
                "yearly", 1, 23998800, 286800, 5, 150, 50, 10240, 20);

        log.info("All 8 billing plans seeded successfully");
    }

    private void seedPlan(String name, String tier, String keyMode, String period,
                          String rzpPeriod, int rzpInterval,
                          long amountInr, long amountUsd,
                          int seats, int runLimit, int projectLimit, int storageMb,
                          int annualDiscountPct) {
        // Create plan in Razorpay
        JsonNode rzpPlan = razorpayClient.createPlan(name, (int) amountInr, rzpPeriod, rzpInterval);
        String rzpPlanId = rzpPlan.get("id").asText();

        // Save plan to DB
        BillingPlan plan = BillingPlan.builder()
                .name(name)
                .tier(tier)
                .keyMode(keyMode)
                .productAccess(ALL_PRODUCTS)
                .period(period)
                .baseAmountInr(amountInr)
                .baseAmountUsd(amountUsd)
                .includedSeats(seats)
                .runLimit(runLimit)
                .annualDiscountPct(annualDiscountPct)
                .razorpayPlanId(rzpPlanId)
                .active(true)
                .build();
        plan = planRepo.save(plan);

        // Save plan limits
        limitRepo.save(BillingPlanLimit.builder()
                .billingPlanId(plan.getId()).metric("RUNS").maxValue(runLimit).build());
        limitRepo.save(BillingPlanLimit.builder()
                .billingPlanId(plan.getId()).metric("PROJECTS").maxValue(projectLimit).build());
        limitRepo.save(BillingPlanLimit.builder()
                .billingPlanId(plan.getId()).metric("STORAGE_MB").maxValue(storageMb).build());

        log.info("Seeded plan: {} → razorpay_plan_id={}", name, rzpPlanId);
    }
}
