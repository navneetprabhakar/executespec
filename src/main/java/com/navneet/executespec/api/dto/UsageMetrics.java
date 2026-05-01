package com.navneet.executespec.api.dto;

import java.util.List;
import java.util.Map;

public record UsageMetrics(
    int totalRuns,
    int successfulRuns,
    int failedRuns,
    int partialRuns,
    List<DailyRunStat> runsByDay,
    Map<String, Integer> runsByProject,
    double avgTimeToCompleteSeconds,
    double totalCost,
    Map<String, Double> costByModel) {
  public record DailyRunStat(String date, int count, double successRate) {}
}
