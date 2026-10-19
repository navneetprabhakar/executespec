package com.navneet.executespec.agent;

import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit plan decomposition is customer scope, not a classifier complexity estimate. */
public final class ExplicitTaskCountContract {
  private static final Pattern COUNT =
      Pattern.compile(
          "(?i)\\b(\\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen)\\s+(?:sequential\\s+)?plan\\s+tasks?\\b");

  private ExplicitTaskCountContract() {}

  public static int requestedCount(String spec) {
    if (spec == null) return 0;
    var matcher = COUNT.matcher(spec);
    if (!matcher.find()) return 0;
    String value = matcher.group(1).toLowerCase(Locale.ROOT);
    var words =
        java.util.List.of(
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen");
    return words.contains(value) ? words.indexOf(value) + 1 : Integer.parseInt(value);
  }

  static ClassificationResult normalizeClassification(ClassificationResult result, String spec) {
    int count = requestedCount(spec);
    if (count <= 1
        || (result.strategy() != PlanStrategy.SINGLE_SHOT && result.strategy().maxTasks() >= count))
      return result;
    PlanStrategy strategy =
        count <= PlanStrategy.SMALL.maxTasks()
            ? PlanStrategy.SMALL
            : count <= PlanStrategy.MEDIUM.maxTasks() ? PlanStrategy.MEDIUM : PlanStrategy.LARGE;
    return new ClassificationResult(
        strategy,
        Math.min(count, PlannerRequest.LARGE_PLANNER_TASK_CAP),
        result.estimatedRepoTouchCount(),
        result.requiresEpics(),
        result.reasoning() + "; explicit customer plan task count=" + count,
        result.source(),
        result.inputTokens(),
        result.outputTokens(),
        result.costUsd());
  }
}
