package com.navneet.executespec.harness;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.agent.CostEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Per-run USD cost budget enforcement — silent hard cap only.
 *
 * <p>The budget ceiling is derived from the run's charged credits:
 * {@code credits × creditMultiplierUsd × budgetMargin}. This acts as a
 * silent safety net — it only triggers if the cost estimate was wildly wrong.
 * Users approved the cost upfront at plan gate; no mid-run warnings.
 *
 * <p>BYOK / ENTERPRISE runs have unlimited budget (-1).
 *
 * <p>Thread-safe — multiple workers can query concurrently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunCostBudgetService {

    private final CostEstimator costEstimator;

    /** Per-run cost budget ceiling in USD. -1 = unlimited. TTL 6h, max 1000 entries. */
    private final Cache<String, Double> budgetCeilings = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .maximumSize(1000)
            .build();

    /** Per-run user tier cache for upgrade messages. TTL 6h, max 1000 entries. */
    private final Cache<String, String> runTierCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(6))
            .maximumSize(1000)
            .build();

    /**
     * Initialize cost budget for a run.
     *
     * @param runId       the run ID
     * @param budgetUsd   max USD cost for this run (-1 = unlimited)
     * @param userTier    the user's subscription tier (for upgrade messages)
     */
    public void initBudget(String runId, double budgetUsd, String userTier) {
        budgetCeilings.put(runId, budgetUsd);
        if (userTier != null) runTierCache.put(runId, userTier);
        else runTierCache.put(runId, "FREE");
        log.info("[CostBudget] Initialized run {} budget=${} tier={}", runId,
                budgetUsd < 0 ? "unlimited" : String.format("%.2f", budgetUsd), userTier);
    }

    /**
     * Check whether the run's accumulated cost exceeds its budget.
     * Returns false if no budget is set or budget is unlimited.
     * This is a silent hard cap — no progressive warnings.
     */
    public boolean isOverBudget(String runId) {
        Double ceiling = budgetCeilings.getIfPresent(runId);
        if (ceiling == null || ceiling < 0) return false;

        double currentCost = costEstimator.computeCostForRun(runId);
        boolean over = currentCost >= ceiling;
        if (over) {
            log.warn("[CostBudget] Run {} over budget (silent hard cap): ${} >= ${}", runId,
                    String.format("%.4f", currentCost), String.format("%.2f", ceiling));
        }
        return over;
    }

    /** Get the budget ceiling for a run (-1 = unlimited, null = not set). */
    public Double getBudget(String runId) {
        return budgetCeilings.getIfPresent(runId);
    }

    /** Get the current accumulated cost for a run. */
    public double getCurrentCost(String runId) {
        return costEstimator.computeCostForRun(runId);
    }

    /** Get the user tier for upgrade messaging. */
    public String getRunTier(String runId) {
        String tier = runTierCache.getIfPresent(runId);
        return tier != null ? tier : "FREE";
    }

    /** Clean up when a run completes. */
    public void remove(String runId) {
        budgetCeilings.invalidate(runId);
        runTierCache.invalidate(runId);
    }
}
