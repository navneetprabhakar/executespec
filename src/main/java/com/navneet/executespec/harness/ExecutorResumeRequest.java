package com.navneet.executespec.harness;

import com.navneet.executespec.execution.model.RunContinuationDecision;

/**
 * Resume identity plus the exact route authorized for a blocked continuation.
 *
 * <p>Crash recovery and ordinary retries carry only a target task. An approved continuation must
 * carry the complete provider/model/action/proposal identity and applies only to that target.
 */
public class ExecutorResumeRequest {

  private final String targetTaskId;
  private final String approvedProvider;
  private final String approvedModel;
  private final RunContinuationDecision approvedAction;
  private final String approvedProposalDigest;
  private final boolean mayReopenTerminalRun;

  ExecutorResumeRequest(
      String targetTaskId,
      String approvedProvider,
      String approvedModel,
      RunContinuationDecision approvedAction,
      String approvedProposalDigest,
      boolean mayReopenTerminalRun) {
    boolean hasProvider = approvedProvider != null && !approvedProvider.isBlank();
    boolean hasModel = approvedModel != null && !approvedModel.isBlank();
    if (hasProvider != hasModel) {
      throw new IllegalArgumentException(
          "Approved continuation route requires both provider and model");
    }
    if (hasProvider && approvedAction == null) {
      throw new IllegalArgumentException(
          "Approved continuation route requires the approved action");
    }
    if (hasProvider && (approvedProposalDigest == null || approvedProposalDigest.isBlank())) {
      throw new IllegalArgumentException(
          "Approved continuation route requires the proposal digest");
    }
    this.targetTaskId = targetTaskId;
    this.approvedProvider = hasProvider ? approvedProvider.trim() : null;
    this.approvedModel = hasModel ? approvedModel.trim() : null;
    this.approvedAction = approvedAction;
    this.approvedProposalDigest =
        approvedProposalDigest == null ? null : approvedProposalDigest.trim().toLowerCase();
    this.mayReopenTerminalRun = mayReopenTerminalRun;
  }

  public String targetTaskId() {
    return targetTaskId;
  }

  public String approvedProvider() {
    return approvedProvider;
  }

  public String approvedModel() {
    return approvedModel;
  }

  public RunContinuationDecision approvedAction() {
    return approvedAction;
  }

  public String approvedProposalDigest() {
    return approvedProposalDigest;
  }

  public boolean mayReopenTerminalRun() {
    return mayReopenTerminalRun;
  }

  boolean hasApprovedRoute() {
    return approvedProvider != null;
  }

  boolean approvedRouteTargets(String runId, String logicalTaskId) {
    if (!hasApprovedRoute() || targetTaskId == null || logicalTaskId == null) {
      return false;
    }
    String prefix = runId == null || runId.length() < 8 ? "" : runId.substring(0, 8) + "_";
    String normalizedTarget =
        !prefix.isEmpty() && targetTaskId.startsWith(prefix)
            ? targetTaskId.substring(prefix.length())
            : targetTaskId;
    return normalizedTarget.equals(logicalTaskId);
  }

  boolean targets(String runId, String logicalTaskId) {
    if (targetTaskId == null || logicalTaskId == null) return false;
    return shortTargetTaskId(runId).equals(logicalTaskId);
  }

  String shortTargetTaskId(String runId) {
    if (targetTaskId == null || targetTaskId.isBlank()) {
      return null;
    }
    String prefix = runId != null && runId.length() >= 8 ? runId.substring(0, 8) + "_" : "";
    return !prefix.isBlank() && targetTaskId.startsWith(prefix)
        ? targetTaskId.substring(prefix.length())
        : targetTaskId;
  }
}
