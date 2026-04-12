package com.navneet.executespec.v2.agent;

import lombok.Builder;

import java.util.List;

/**
 * The structured plan produced by {@link PlannerAgentV2}.
 *
 * <p>New in V2 vs V1:
 * <ul>
 *   <li>{@link #buildCommand} — explicit build command declaration. If null, V2's
 *       {@link com.navneet.executespec.v2.smoketest.BuildCommandResolver} falls back
 *       to framework inference.</li>
 *   <li>{@link #smokeTestStrategy} — how the executor verifies each task after merge
 *       (Phase 5 owns the actual enforcement).</li>
 *   <li>{@link PlannedTaskV2#hardConstraints} — per-task constraints injected into
 *       the worker system prompt.</li>
 * </ul>
 *
 * <p>Removed from V1:
 * <ul>
 *   <li>Ownership walls — sequential execution makes them unnecessary.</li>
 *   <li>Subtask decomposition — folded into the worker's iteration budget.</li>
 * </ul>
 */
@Builder
public record PlanV2(
        String planTitle,
        String planSummary,
        String projectType,
        String buildCommand,
        SmokeTestStrategy smokeTestStrategy,
        List<PlannedTaskV2> tasks,
        Classification classification) {

    public PlanV2 {
        if (planTitle == null || planTitle.isBlank()) planTitle = "(untitled plan)";
        if (planSummary == null) planSummary = "";
        if (smokeTestStrategy == null) smokeTestStrategy = SmokeTestStrategy.BUILD;
        if (tasks == null) tasks = List.of();
        if (classification == null) classification = Classification.MEDIUM;
    }

    public enum SmokeTestStrategy {
        /** Run the resolved build command; pass iff exit 0. Default. */
        BUILD,
        /** Start a preview and hit a health endpoint. Heavier but catches runtime issues. */
        PREVIEW_HEALTH,
        /** Run the project's test suite. Most thorough, slowest. */
        TEST_SUITE
    }
}
