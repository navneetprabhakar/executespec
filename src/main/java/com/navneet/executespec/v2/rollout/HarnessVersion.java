package com.navneet.executespec.v2.rollout;

/**
 * Which harness is selected for a given run.
 */
public enum HarnessVersion {
    /** Run goes through V1 (legacy {@code PipelineHarness}). */
    V1,
    /** Run goes through V2 (planner → executor → reviewer). */
    V2
}
