package com.navneet.executespec.agent;

import java.util.function.Function;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Fail-closed admission policy when a planner ignores its explicitly advertised task ceiling. */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class PlannerTaskCapPolicy {

  static Plan enforce(Plan plan, int maxTasks, Function<String, Plan> retry) {
    if (maxTasks == Integer.MAX_VALUE || plan.tasks().size() <= maxTasks) return plan;

    String feedback =
        "Your previous plan contained "
            + plan.tasks().size()
            + " tasks, exceeding the hard maximum of "
            + maxTasks
            + ". Re-emit the complete plan with at most "
            + maxTasks
            + " cohesive tasks. Merge closely related work without dropping any requirement,"
            + " acceptance test, setup step, dependency, or exact API contract.";
    log.warn(
        "OutlinePlanner exceeded admitted task cap ({} > {}) — retrying with coarsening feedback",
        plan.tasks().size(),
        maxTasks);

    Plan retried = retry.apply(feedback);
    if (retried.tasks().size() > maxTasks) {
      throw new IllegalStateException(
          "OutlinePlanner exceeded the hard task cap after retry: "
              + retried.tasks().size()
              + " > "
              + maxTasks);
    }
    return retried;
  }
}
