package com.navneet.executespec.v2.reviewer;

import com.navneet.executespec.v2.agent.WorkerResult;
import lombok.Builder;

/**
 * Output of {@link ReviewerAgentV2#review}.
 *
 * @param overall               terminal verdict across both review passes
 * @param generalPassOutcome    raw worker outcome of the general-review pass
 * @param generalPassSummary    the worker's completeTask summary from the general pass
 *                              (useful for displaying "X issues found, Y fixed")
 * @param wiringPassOutcome     raw worker outcome of the wiring-review pass (null if skipped)
 * @param wiringPassSummary     completeTask summary from the wiring pass (null if skipped)
 * @param finalBuildPassed      final build result after all review fixes
 * @param finalBuildExitCode    exit code from the final build check
 * @param finalBuildOutput      truncated build output (null if build wasn't run)
 * @param totalLatencyMs        wall-clock across both passes + final build
 */
@Builder
public record ReviewerResult(
        Overall overall,
        WorkerResult generalPassOutcome,
        String generalPassSummary,
        WorkerResult wiringPassOutcome,
        String wiringPassSummary,
        Boolean finalBuildPassed,
        Integer finalBuildExitCode,
        String finalBuildOutput,
        long totalLatencyMs) {

    public enum Overall {
        /** Both passes completed, no issues found or all fixes verified by final build. */
        CLEAN,
        /** Passes completed, issues were fixed, final build passes. */
        FIXED_ISSUES,
        /** Passes completed but final build still fails after fixes. */
        UNRESOLVED_ISSUES,
        /** A review pass did not reach COMPLETED (worker timeout / error). */
        REVIEW_FAILED
    }
}
