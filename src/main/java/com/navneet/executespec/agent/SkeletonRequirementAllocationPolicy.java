package com.navneet.executespec.agent;

import java.util.List;
import java.util.function.Function;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Fail-closed requirement ownership gate for hierarchical skeletons before parallel expansion. */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class SkeletonRequirementAllocationPolicy {

  static Plan enforce(Plan plan, Function<String, Plan> retry) {
    List<String> violations = PlannerOutputUtils.validateRequirementCoverage(plan);
    if (violations.isEmpty()) return plan;

    String feedback =
        "Your skeleton did not allocate the requirement graph correctly:\n  - "
            + String.join("\n  - ", violations)
            + "\n\nRe-emit the complete skeleton. Keep task bodies omitted, but every task must"
            + " list the requirement IDs it owns in meetsRequirements, every cited ID must exist,"
            + " and every requirement must have at least one owner.";
    log.warn(
        "OutlinePlanner skeleton requirement allocation invalid ({} violations) — retrying before"
            + " expansion. First violation: {}",
        violations.size(),
        violations.get(0));

    Plan retried = retry.apply(feedback);
    List<String> remaining = PlannerOutputUtils.validateRequirementCoverage(retried);
    if (!remaining.isEmpty()) {
      throw new IllegalStateException(
          "OutlinePlanner skeleton requirement allocation remained invalid after retry: "
              + String.join("; ", remaining));
    }
    return retried;
  }
}
