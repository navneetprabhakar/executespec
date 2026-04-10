package com.navneet.executespec.agent;

import java.util.List;

/**
 * Cost estimation result for a complete pipeline run.
 * Computed at plan time and shown to the user at the approval gate.
 * Includes both real-time and batch pricing paths.
 */
public record RunCostEstimate(
        // ── Real-time path ───────────────────────────────────────────────
        double totalEstimatedCost,     // midpoint estimate in USD used for credit calculation
        double lowEstimatedCost,       // low-end estimate (min iterations, no escalation)
        double highEstimatedCost,      // high-end estimate (max iterations + 20% escalation premium)
        int runCredits,                // ceil(totalEstimatedCost / creditMultiplier)
        double hardCap,                // runCredits × creditMultiplier (budget ceiling)

        // ── Batch path ───────────────────────────────────────────────────
        double batchEstimatedCost,     // buffered estimate in USD with batch discount
        int batchCredits,              // ceil(batchEstimatedCost / creditMultiplier)
        double batchHardCap,           // batchCredits × creditMultiplier

        // ── Common ───────────────────────────────────────────────────────
        String provider,
        boolean batchAvailable,        // true if this run qualifies for batch execution
        int creditSavings,             // runCredits - batchCredits

        List<TaskCostEstimate> tasks,  // per-task breakdown
        double planningCost,
        double reviewCost,
        double compilerCost
) {
    public String formatted() {
        String base = String.format("Estimated cost: $%.2f – $%.2f (%d credit%s, cap $%.2f)",
                lowEstimatedCost, highEstimatedCost, runCredits, runCredits == 1 ? "" : "s", hardCap);
        if (batchAvailable) {
            base += String.format(" | Batch: $%.2f (%d credit%s, save %d)",
                    batchEstimatedCost, batchCredits, batchCredits == 1 ? "" : "s", creditSavings);
        }
        return base;
    }

    public record TaskCostEstimate(
            String taskId,
            String complexity,
            double estimatedCost,
            int estimatedIterations
    ) {}
}
