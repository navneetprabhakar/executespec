package com.navneet.executespec.testgen.event;

/** Published when all test classes are compiled and ready for execution against a preview. */
public record TestsReadyEvent(String runId, Long testPlanId) {}
