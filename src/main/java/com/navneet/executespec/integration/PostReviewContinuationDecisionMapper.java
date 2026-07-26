package com.navneet.executespec.integration;

import java.util.LinkedHashMap;
import java.util.Map;

import com.navneet.executespec.execution.model.GoalContinuationPolicyDecision;
import com.navneet.executespec.execution.model.RunContinuationDecision;
import com.navneet.executespec.router.ModelProviderScore;

/** Serializes post-review continuation policy without coupling it to pipeline orchestration. */
record PostReviewContinuationDecisionMapper(
    double requiredQualityThreshold,
    int maxProviderSwitches,
    int maxModelStepUps,
    int maxModelStepDowns) {

  Map<String, Object> data(
      GoalContinuationPolicyDecision decision,
      boolean confirmationRequired,
      boolean benchmarkPriorRequired,
      boolean routePinned,
      String source) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("decision", decision.decision().name());
    data.put("reasons", decision.reasons());
    Map<String, Object> policy = new LinkedHashMap<>();
    if (decision.policy() != null) policy.putAll(decision.policy());
    policy.put("taskCategory", "PROJECT_PATCH");
    policy.put("requiredQualityThreshold", requiredQualityThreshold);
    policy.put("providerSwitchesUsed", providerSwitchesUsed(decision));
    policy.put("maxProviderSwitches", Math.max(0, maxProviderSwitches));
    policy.put("maxModelStepUps", Math.max(0, maxModelStepUps));
    policy.put("maxModelStepDowns", Math.max(0, maxModelStepDowns));
    policy.put("requireConfirmationBeforeExpensiveContinuation", confirmationRequired);
    policy.put("requireBenchmarkPriorForAutomaticContinuation", benchmarkPriorRequired);
    policy.put("routePinned", routePinned);
    policy.put("source", source(source));
    data.put("policy", policy);
    decision
        .selectedCandidate()
        .ifPresent(candidate -> data.put("selectedCandidate", selectedCandidate(candidate)));
    String approval = approvalRequiredReason(decision, policy);
    if (approval != null) data.put("approvalRequiredReason", approval);
    data.put("source", source(source));
    return data;
  }

  static GoalContinuationPolicyDecision withSource(
      GoalContinuationPolicyDecision decision, String source) {
    if (decision == null) return null;
    Map<String, Object> policy = new LinkedHashMap<>();
    if (decision.policy() != null) policy.putAll(decision.policy());
    policy.put("source", source(source));
    return new GoalContinuationPolicyDecision(
        decision.decision(), decision.selectedCandidate(), decision.reasons(), policy);
  }

  private static String approvalRequiredReason(
      GoalContinuationPolicyDecision decision, Map<String, Object> policy) {
    if (decision == null
        || decision.decision() != RunContinuationDecision.STOP_BLOCKED
        || decision.selectedCandidate().isEmpty()) {
      return null;
    }
    ModelProviderScore candidate = decision.selectedCandidate().get();
    if (Boolean.TRUE.equals(policy.get("routePinned"))) return "ROUTE_PINNED";
    if (Boolean.TRUE.equals(policy.get("requireBenchmarkPriorForAutomaticContinuation"))
        && !candidate.benchmarkQualityPriorAvailable()) {
      return "BENCHMARK_PRIOR_REQUIRED";
    }
    if (Boolean.TRUE.equals(policy.get("requireConfirmationBeforeExpensiveContinuation"))) {
      return "EXPENSIVE_CONTINUATION_CONFIRMATION_REQUIRED";
    }
    return null;
  }

  private static int providerSwitchesUsed(GoalContinuationPolicyDecision decision) {
    Object value =
        decision == null || decision.policy() == null
            ? null
            : decision.policy().get("providerSwitchesUsed");
    return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
  }

  private static Map<String, Object> selectedCandidate(ModelProviderScore candidate) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("provider", candidate.provider());
    data.put("model", candidate.model());
    data.put("taskCategory", candidate.taskCategory());
    data.put("qualityScore", candidate.qualityScore());
    data.put("requiredQualityThreshold", candidate.requiredQualityThreshold());
    data.put("costPerMillionTokens", candidate.costPerMillionTokens());
    data.put("latencyP50Ms", candidate.latencyP50Ms());
    data.put("latencyP95Ms", candidate.latencyP95Ms());
    data.put("benchmarkQualityPriorAvailable", candidate.benchmarkQualityPriorAvailable());
    data.put("reasons", candidate.reasons());
    return data;
  }

  private static String source(String value) {
    return value == null || value.isBlank() ? "POST_REVIEW" : value;
  }
}
