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
    if (retryViolations.isEmpty()) return repaired;

    // The model still left ownership off (Anthropic treats nested `required` as a hint, so the
    // forced-tool outline call omits meetsRequirements intermittently — planning was a coin flip).
    // Deterministically backfill ownership so a correct plan is not thrown away over missing
    // metadata. Any residual scope violation (e.g. an actually-unrequested OpenAPI deliverable) is
    // NOT ownership and still fails closed below.
    Plan ownershipRepaired = PlannerOwnershipRepair.assignMissingOwnership(repaired);
    List<String> residual = PlannerOutputUtils.validateScopeIntegrity(ownershipRepaired);
    if (residual.isEmpty()) {
      log.info(
          "OutlinePlanner requirement ownership was missing after model retry; deterministically"
              + " backfilled ownership for a complete plan.");
      return ownershipRepaired;
    }
    throw new IllegalStateException(
        "Planner repeatedly proposed unauthorized deliverables: " + String.join("; ", residual));
  }

  private static String repairFeedback(List<String> violations) {
    boolean missingOwnership =
        violations.stream().anyMatch(v -> v.startsWith("Tasks with no requirement ownership"));
    boolean unrequestedApiSpec =
        violations.stream().anyMatch(v -> v.startsWith("Unrequested OpenAPI"));
    StringBuilder feedback =
        new StringBuilder(
            "Your previous plan did not satisfy the requirement-ownership contract:\n"
                + "  - "
                + String.join("\n  - ", violations));
    if (missingOwnership) {
      // The tasks are almost always fine — the model simply left the meetsRequirements array off.
      // The old prompt told it "you added work the customer did not request", which pushed the
      // model to DELETE legitimate tasks instead of filling in ownership. Direct it precisely.
      feedback.append(
          "\n\nThe listed tasks are correct — do NOT remove, merge, or rescope them. Each task is"
              + " only missing its \"meetsRequirements\" field. Re-emit the SAME tasks, and on"
              + " EVERY task add a non-empty \"meetsRequirements\" array naming the requirement IDs"
              + " (from the top-level \"requirements\" array, e.g. [\"R1\",\"R3\"]) that the task"
              + " implements. Every requirement must be owned by at least one task.");
    }
    if (unrequestedApiSpec) {
      feedback.append(
          "\n\nAlso remove any standalone OpenAPI/Swagger deliverable: framework-generated runtime"
              + " API docs do not authorize a separate spec file.");
    }
    return feedback.toString();
  }
}
