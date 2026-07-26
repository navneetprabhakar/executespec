package com.navneet.executespec.chat;

import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.integration.PlannerFailureDiagnostic;
import com.navneet.executespec.state.entity.PipelineRun;

/** Zero-provider answers for terminal lifecycle facts already proven by persisted run evidence. */
final class RunChatDeterministicAnswer {

  private static final int MAX_FAILURE_CHARS = 2_000;

  private RunChatDeterministicAnswer() {}

  static Optional<String> answer(PipelineRun run, String question) {
    if (run == null || question == null) return Optional.empty();
    String lowerQuestion = question.toLowerCase(Locale.ROOT);
    if (!asksAboutPlanningFailure(lowerQuestion)) return Optional.empty();
    if (!"BUILD".equalsIgnoreCase(run.getRunMode())
        || !PlannerFailureDiagnostic.isPlanValidationStatusReason(run.getStatusReason())
        || hasText(run.getPlanJson())) {
      return Optional.empty();
    }

    String failure = run.getFailureReason();
    if (!isDeterministicPlanRejection(failure)) return Optional.empty();
    String boundedFailure =
        failure.length() <= MAX_FAILURE_CHARS
            ? failure
            : failure.substring(0, MAX_FAILURE_CHARS) + "… [truncated]";
    boolean providerResponseObserved =
        positive(run.getClassifierOutputTokens())
            || positive(run.getOutlineOutputTokens())
            || positive(run.getExpansionOutputTokens())
            || positive(run.getCoherenceOutputTokens())
            || positive(run.getPlannerOutputTokens())
            || hasText(run.getPlanOutlineJson());
    String route = firstNonBlank(run.getPlannerProvider(), run.getRequestedProvider());
    String model = run.getPlannerActualModel();
    String projectedStatusReason =
        PlannerFailureDiagnostic.projectStatusReason(run.getStatusReason(), run.getFailureReason());

    StringBuilder answer = new StringBuilder();
    answer
        .append("Worker code generation did not start. This BUILD run failed before a final ")
        .append("executable plan was persisted, so no worker task could run.\n\n")
        .append("Exact planning rejection: ")
        .append(boundedFailure)
        .append("\n\n")
        .append("Provider availability was not the recorded cause. ");
    if (providerResponseObserved) {
      answer.append("The run received planning output");
      if (hasText(route)) {
        answer.append(" from ").append(route.toLowerCase(Locale.ROOT));
        if (hasText(model)) answer.append('/').append(model);
      }
      answer.append(" before the deterministic contract gate rejected the assembled plan. ");
    }
    if ("PLAN_VALIDATION_REJECTED".equals(projectedStatusReason)) {
      answer.append(
          "PLAN_VALIDATION_REJECTED records that fail-closed contract-gate outcome; it does not"
              + " mean a provider outage.");
    } else {
      answer.append(
          "PLANNER_UNAVAILABLE is the fail-closed umbrella lifecycle category for this"
              + " planning-stage rejection; by itself it does not mean a provider outage.");
    }
    return Optional.of(answer.toString());
  }

  private static boolean asksAboutPlanningFailure(String question) {
    return question.contains("planner")
        || question.contains("planning")
        || question.contains("worker")
        || question.contains("code generation")
        || question.contains("provider")
        || question.contains("why did")
        || question.contains("failure")
        || question.contains("failed");
  }

  private static boolean isDeterministicPlanRejection(String failure) {
    return PlannerFailureDiagnostic.isDeterministicEvidence(failure);
  }

  private static boolean positive(Number value) {
    return value != null && value.longValue() > 0;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return hasText(preferred) ? preferred : fallback;
  }
}
