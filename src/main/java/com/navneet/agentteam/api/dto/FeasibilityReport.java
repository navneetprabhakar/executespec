package com.navneet.agentteam.api.dto;

import java.util.List;

public record FeasibilityReport(
    /** "FEASIBLE" | "MARGINAL" | "INFEASIBLE" | "ESTIMATED" (no budget provided) */
    String verdict,
    Double budgetUsd,
    double estimatedLowUsd,
    double estimatedHighUsd,
    List<TaskFeasibility> tasks
) {}
