package com.navneet.executespec.reviewer;

import com.navneet.executespec.engine.WorkerResult;

import lombok.Builder;

/**
 * Output of {@link ReviewerAgent#review}.
 *
 * @param overall terminal verdict across both review passes
 * @param generalPassOutcome raw worker outcome of the general-review pass
 * @param generalPassSummary the worker's completeTask summary from the general pass (useful for
 *     displaying "X issues found, Y fixed")
 * @param wiringPassOutcome raw worker outcome of the wiring-review pass (null if skipped)
 * @param wiringPassSummary completeTask summary from the wiring pass (null if skipped)
 * @param finalBuildPassed final build result after all review fixes
 * @param finalBuildExitCode exit code from the final build check
 * @param finalBuildOutput truncated build output (null if build wasn't run)
 * @param finalTestsPassed concrete workspace tests that passed in the FINAL workspace generation,
 *     or null when no concrete suite was executed (RR-273)
 * @param finalTestsTotal concrete workspace tests executed in the FINAL workspace generation, or
 *     null when no concrete suite was executed (RR-273)
 * @param concreteTestsRed true when a concrete suite was known red and was not subsequently proven
 *     green in the final workspace generation. Tighten-only: this forces {@code UNRESOLVED_ISSUES}
 *     so a clean verdict can never be returned over failing or unprovable tests (RR-273)
 * @param totalLatencyMs wall-clock across both passes + final build
 * @param workspaceQuiescent false when a timed-out review pass could NOT be proven terminated
 *     within the abandonment reserve, so the workspace may still be mutating after {@code review}
 *     returned. Every later evidence stage is skipped in that case, because build/test output and
 *     the published diff would describe different workspace generations (RR-272). Defaults to true.
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
    Integer finalTestsPassed,
    Integer finalTestsTotal,
    boolean concreteTestsRed,
    long totalLatencyMs,
    Boolean workspaceQuiescent) {

  /** True unless a timed-out pass was left running with unrevoked write authority (RR-272). */
  public boolean isWorkspaceQuiescent() {
    return workspaceQuiescent == null || workspaceQuiescent;
  }

  public enum Overall {
    /** Both passes completed, no issues found or all fixes verified by final build. */
    CLEAN,
    /** Passes completed, issues were fixed, final build passes. */
    FIXED_ISSUES,
    /** Passes completed but the final build or the final test suite still fails after fixes. */
    UNRESOLVED_ISSUES,
    /** A review pass did not reach COMPLETED (worker timeout / error). */
    REVIEW_FAILED
  }
}
