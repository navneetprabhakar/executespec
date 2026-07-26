package com.navneet.executespec.chat;

import com.navneet.executespec.state.entity.PipelineRun;

/** Builds bounded, server-owned lifecycle evidence for terminal-run Ask turns. */
final class RunChatEvidence {

  private static final int MAX_FAILURE_REASON_CHARS = 2_000;

  private RunChatEvidence() {}

  static String render(PipelineRun run) {
    boolean outlinePersisted = hasText(run.getPlanOutlineJson());
    boolean providerResponseObserved =
        positive(run.getPlannerOutputTokens())
            || positive(run.getTotalOutputTokens())
            || positive(run.getClassifierOutputTokens())
            || positive(run.getOutlineOutputTokens())
            || positive(run.getExpansionOutputTokens())
            || positive(run.getCoherenceOutputTokens())
            || outlinePersisted;
    boolean plannerTokenAttributionIncomplete =
        zero(run.getPlannerInputTokens())
            && zero(run.getPlannerOutputTokens())
            && (positive(run.getTotalInputTokens())
                || positive(run.getTotalOutputTokens())
                || positive(run.getOutlineInputTokens())
                || positive(run.getOutlineOutputTokens())
                || outlinePersisted);
    StringBuilder evidence = new StringBuilder("## Run metadata\n");
    evidence.append("- runId: ").append(run.getRunId()).append('\n');
    evidence.append("- status: ").append(value(run.getStatus())).append('\n');
    evidence.append("- statusReason: ").append(value(run.getStatusReason())).append('\n');
    evidence
        .append("- failureReason: ")
        .append(bounded(run.getFailureReason(), MAX_FAILURE_REASON_CHARS))
        .append('\n');
    evidence
        .append("- requestedProvider: ")
        .append(value(firstNonBlank(run.getRequestedProvider(), run.getAiProvider())))
        .append('\n');
    evidence
        .append("- plannerEffectiveProvider: ")
        .append(value(run.getPlannerProvider()))
        .append('\n');
    evidence
        .append("- plannerEffectiveModel: ")
        .append(value(run.getPlannerActualModel()))
        .append('\n');
    evidence
        .append("- plannerTokens: input=")
        .append(number(run.getPlannerInputTokens()))
        .append(", output=")
        .append(number(run.getPlannerOutputTokens()))
        .append('\n');
    evidence.append("- providerResponseObserved: ").append(providerResponseObserved).append('\n');
    evidence
        .append("- plannerTokenAttributionIncomplete: ")
        .append(plannerTokenAttributionIncomplete)
        .append('\n');
    evidence
        .append("- totalRunTokens: input=")
        .append(number(run.getTotalInputTokens()))
        .append(", output=")
        .append(number(run.getTotalOutputTokens()))
        .append('\n');
    evidence
        .append("- planningPhaseTokens: classifier=")
        .append(pair(run.getClassifierInputTokens(), run.getClassifierOutputTokens()))
        .append(", outline=")
        .append(pair(run.getOutlineInputTokens(), run.getOutlineOutputTokens()))
        .append(", expansion=")
        .append(pair(run.getExpansionInputTokens(), run.getExpansionOutputTokens()))
        .append(", coherence=")
        .append(pair(run.getCoherenceInputTokens(), run.getCoherenceOutputTokens()))
        .append('\n');
    evidence
        .append("- planArtifacts: outlinePersisted=")
        .append(outlinePersisted)
        .append(", finalPlanPersisted=")
        .append(hasText(run.getPlanJson()))
        .append(", requirementsPersisted=")
        .append(hasText(run.getPlanRequirementsJson()))
        .append('\n');
    if (run.getActualCostUsd() != null) {
      evidence.append("- recordedRunCostUsd: ").append(run.getActualCostUsd()).append('\n');
    }
    return evidence.toString();
  }

  private static String pair(Number input, Number output) {
    return "input=" + number(input) + "/output=" + number(output);
  }

  private static String number(Number value) {
    return value == null ? "unknown" : value.toString();
  }

  private static boolean positive(Number value) {
    return value != null && value.longValue() > 0;
  }

  private static boolean zero(Number value) {
    return value != null && value.longValue() == 0;
  }

  private static String value(String value) {
    return value == null || value.isBlank() ? "(not recorded)" : value;
  }

  private static String firstNonBlank(String preferred, String fallback) {
    return preferred == null || preferred.isBlank() ? fallback : preferred;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String bounded(String value, int maxChars) {
    if (value == null || value.isBlank()) return "(not recorded)";
    return value.length() <= maxChars ? value : value.substring(0, maxChars) + "… [truncated]";
  }
}
