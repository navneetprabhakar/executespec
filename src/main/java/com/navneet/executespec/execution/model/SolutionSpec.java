package com.navneet.executespec.execution.model;

import java.util.List;

/**
 * Planner output — one solution within an {@link ExecutionPlan}. Mirrors the
 * JSON emitted via the {@code submitExecutionPlan} tool call.
 */
public record SolutionSpec(
        int sequenceNumber,
        String title,
        String description,
        String repoId,
        String repoName,
        List<String> targetFiles,
        List<Integer> dependsOn,
        long estimatedTokens,
        String complexity) {
}
