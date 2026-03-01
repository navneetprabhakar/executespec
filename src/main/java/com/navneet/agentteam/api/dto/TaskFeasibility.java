package com.navneet.agentteam.api.dto;

public record TaskFeasibility(
    String taskId,
    String title,
    String complexity,
    int estimatedIterationsMin,
    int estimatedIterationsMax,
    double estimatedCostLow,
    double estimatedCostHigh
) {}
