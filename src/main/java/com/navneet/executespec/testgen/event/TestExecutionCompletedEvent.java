package com.navneet.executespec.testgen.event;

import com.navneet.executespec.testgen.model.TestExecutionSummary;

/**
 * Published when test execution against the preview completes.
 */
public record TestExecutionCompletedEvent(String runId, Long testPlanId, TestExecutionSummary summary) {}
