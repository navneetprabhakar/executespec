package com.navneet.executespec.agent;

import java.util.List;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class PlannerScopeIntegrityPolicy {

  private PlannerScopeIntegrityPolicy() {}

  static Plan enforce(Plan initial, Function<String, Plan> retry) {
    List<String> violations = PlannerOutputUtils.validateScopeIntegrity(initial);
    if (violations.isEmpty()) return initial;

    log.warn(
        "OutlinePlanner scope integrity failed ({} violations) — retrying once. First violation:"
            + " {}",
        violations.size(),
        violations.getFirst());
    Plan repaired = retry.apply(repairFeedback(violations));
    List<String> retryViolations = PlannerOutputUtils.validateScopeIntegrity(repaired);
    if (!retryViolations.isEmpty()) {
      throw new IllegalStateException(
          "Planner repeatedly proposed unauthorized deliverables: "
              + String.join("; ", retryViolations));
    }
    return repaired;
  }

  private static String repairFeedback(List<String> violations) {
    return "Your previous plan added work that the customer did not request:\n  - "
        + String.join("\n  - ", violations)
        + "\n\nRe-emit the plan without invented deliverables. Every task must own at least one"
        + " explicit requirement, and framework-generated runtime API docs do not authorize a"
        + " standalone OpenAPI/Swagger file.";
  }
}
