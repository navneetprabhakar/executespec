package com.navneet.executespec.run.outcome;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.navneet.executespec.run.outcome.RunTerminalOutcome.Publication;

/**
 * Customer copy for the nine distinct reasons planning can stop at {@code
 * PLANNER_RECOVERY_POLICY_BLOCKED}.
 *
 * <h2>Why this exists</h2>
 *
 * The nine {@code PlannerRecoveryPolicyBlockedException.Code} values were collapsed into ONE
 * sentence — "bounded recovery could not produce an approvable plan" — which is accurate for only
 * three of them. Dev run {@code 322919af} stopped on {@code ROUTE_PINNED}: the first plan failed
 * deterministic validation, escalating to a different planning model is the DESIGNED recovery, and
 * the run's exact model pin forbade it. Recovery was never attempted, so telling the customer it
 * ran and failed was false, and the derived {@code retryable=false} was false too — unpinning the
 * model makes the run retryable.
 *
 * <h2>The split</h2>
 *
 * Four codes are POLICY REFUSALS with a lever the customer holds (pin, confirmation, benchmark
 * evidence, matrix contents): they must name the lever and stay retryable. Three are genuine
 * exhaustion, where the original copy was already correct. Two are route/config faults that a plain
 * retry can clear. The stop itself is unchanged — this only tells the truth about which one
 * happened.
 *
 * <p>The code is recovered from the leading token {@code RunPlanningCoordinator} persists as {@code
 * "<CODE>: <message>"} in {@code pipeline_run.failure_reason}. Only a token matching a known enum
 * constant is used; anything else falls back to the exhaustion wording, because an unrecognised
 * token is not evidence that a lever exists.
 */
final class PlannerRecoveryOutcomeCopy {

  private PlannerRecoveryOutcomeCopy() {}

  /** Leading {@code CODE:} token of a persisted planner-block failure reason. */
  private static final Pattern BLOCK_CODE_PREFIX = Pattern.compile("^([A-Z][A-Z0-9_]{1,63}):");

  /** Mirrors {@code PlannerRecoveryPolicyBlockedException.Code}; unknown tokens are ignored. */
  private static final Set<String> KNOWN_CODES =
      Set.of(
          "EXACT_REPAIR_ROUTE_UNAVAILABLE",
          "PHYSICAL_ROUTE_MISMATCH",
          "SAME_ROUTE_REPAIR_FAILED",
          "ROUTE_PINNED",
          "CONFIRMATION_REQUIRED",
          "NO_AVAILABLE_MATRIX_ROUTE",
          "BENCHMARK_EVIDENCE_REQUIRED",
          "NO_QUALITY_STEP_UP",
          "ESCALATION_LIMIT_EXHAUSTED");

  /** Returns the planner block code, or null when the reason does not carry a known one. */
  static String blockCode(String failureReason) {
    if (failureReason == null) return null;
    Matcher prefix = BLOCK_CODE_PREFIX.matcher(failureReason.trim());
    if (!prefix.find()) return null;
    String code = prefix.group(1);
    return KNOWN_CODES.contains(code) ? code : null;
  }

  static RunTerminalOutcomeDefinitions.Definition definition(
      String blockCode, Publication publication) {
    String scope =
        publication == Publication.PR_CREATED
            ? " Your existing pull request was not updated."
            : " No code or pull request was created.";

    return switch (blockCode == null ? "" : blockCode) {
      case "ROUTE_PINNED" ->
          RunTerminalOutcomeDefinitions.resubmitRequired(
              "Blocked by the exact model pin",
              "The first plan did not pass validation. Retrying the plan on a different model is"
                  + " how that is normally fixed, but this run pinned an exact model, which"
                  + " forbids switching — so no retry was attempted."
                  + scope,
              "Resubmit without the exact model pin, or pin a model strong enough to plan this"
                  + " request.");
      case "CONFIRMATION_REQUIRED" ->
          RunTerminalOutcomeDefinitions.resubmitRequired(
              "Blocked awaiting your model-upgrade confirmation",
              "The first plan did not pass validation, and fixing it needed a stronger planning"
                  + " model. This run requires you to confirm model upgrades, and no confirmation"
                  + " was given."
                  + scope,
              "Resubmit with \"Confirm model upgrades\" turned off, or approve the upgrade when"
                  + " prompted.");
      case "BENCHMARK_EVIDENCE_REQUIRED" ->
          RunTerminalOutcomeDefinitions.resubmitRequired(
              "Blocked by the benchmark-evidence requirement",
              "The first plan did not pass validation, and the stronger planning models available"
                  + " to this run have no benchmark evidence. This run requires benchmark-backed"
                  + " models only."
                  + scope,
              "Resubmit with \"Require benchmark evidence\" turned off, or choose a"
                  + " benchmark-backed model.");
      case "NO_AVAILABLE_MATRIX_ROUTE" ->
          RunTerminalOutcomeDefinitions.policyBlocked(
              "No alternative planning model available",
              "The first plan did not pass validation, and your organization's model matrix offers"
                  + " no other planning model to retry it on."
                  + scope,
              "Add a stronger planning model to the organization's model matrix, or choose a"
                  + " different provider.");
      case "EXACT_REPAIR_ROUTE_UNAVAILABLE", "PHYSICAL_ROUTE_MISMATCH" ->
          RunTerminalOutcomeDefinitions.actionRequired(
              "Planning model route could not be honoured",
              "The plan needed a repair pass, but the exact model route this run requires could"
                  + " not be used for it, so planning stopped rather than silently using a"
                  + " different one."
                  + scope,
              "Retry the run. If you pinned an exact model, try another model or provider.");
      default ->
          RunTerminalOutcomeDefinitions.policyBlocked(
              "Plan could not be made valid",
              "The plan failed deterministic validation, and the retries available to this run"
                  + " could not produce an approvable plan."
                  + scope,
              "Review the request for contradictory or under-specified requirements, then"
                  + " resubmit.");
    };
  }
}
