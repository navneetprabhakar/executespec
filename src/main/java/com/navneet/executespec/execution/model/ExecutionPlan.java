package com.navneet.executespec.execution.model;

import java.math.BigDecimal;
import java.util.List;

public record ExecutionPlan(
        List<SolutionSpec> solutions,
        List<DependencyEdge> dependencyEdges,
        List<FileConflict> conflicts,
        long totalEstimatedTokens,
        BigDecimal totalEstimatedCostUsd) {
}
