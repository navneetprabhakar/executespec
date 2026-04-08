package com.navneet.executespec.harness;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.agent.CostEstimator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-run USD cost budget enforcement.
 *
 * <p>Tracks a per-run cost ceiling based on the user's subscription tier.
 * Before each worker task, {@link SequentialTaskExecutor} checks whether
 * the accumulated run cost exceeds the budget. If so, the run is cancelled
 * with an upgrade message.
 *
 * <p>Budget values:
 * <ul>
 *   <li>FREE: $3.00 (lifetime 3 runs, gpt-5.4-mini only)</li>
 *   <li>PRO (no BYOK): $5.00</li>
 *   <li>PRO (BYOK) / ENTERPRISE: unlimited (-1)</li>
 * </ul>
 *
 * <p>Thread-safe — multiple workers can query concurrently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunCostBudgetService {

    private static final int[] WARNING_THRESHOLDS = {50, 75, 90};

    private final CostEstimator costEstimator;
    private final SseBroadcaster sseBroadcaster;

    /** Tracks which warning thresholds have been emitted per run (e.g. "runId:50", "runId:75"). */
    private final Set<String> emittedWarnings = ConcurrentHashMap.newKeySet();

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
     */
    public boolean isOverBudget(String runId) {
        Double ceiling = budgetCeilings.getIfPresent(runId);
        if (ceiling == null || ceiling < 0) return false;

        double currentCost = costEstimator.computeCostForRun(runId);
        boolean over = currentCost >= ceiling;
        if (over) {
            log.warn("[CostBudget] Run {} over budget: ${} >= ${}", runId,
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

    /**
     * Check and emit progressive cost warnings at 50%, 75%, 90% of budget.
     * Each threshold fires only once per run. Non-blocking — safe to call frequently.
     */
    public void checkBudgetWarnings(String runId) {
        Double ceiling = budgetCeilings.getIfPresent(runId);
        if (ceiling == null || ceiling < 0) return;

        double currentCost = costEstimator.computeCostForRun(runId);
        int percentUsed = (int) (currentCost / ceiling * 100);

        for (int threshold : WARNING_THRESHOLDS) {
            if (percentUsed >= threshold) {
                String key = runId + ":" + threshold;
                if (emittedWarnings.add(key)) {
                    String tier = getRunTier(runId);
                    log.info("[CostBudget] Run {} reached {}% of ${} budget (${} spent)",
                            runId, threshold, String.format("%.2f", ceiling),
                            String.format("%.2f", currentCost));
                    sseBroadcaster.emit(runId, RunEvent.budgetWarning(
                            runId, tier, ceiling, threshold, currentCost));
                }
            }
        }
    }

    /** Clean up when a run completes. */
    public void remove(String runId) {
        budgetCeilings.invalidate(runId);
        runTierCache.invalidate(runId);
        emittedWarnings.removeIf(k -> k.startsWith(runId + ":"));
    }
}
