package com.navneet.executespec.integration;

import static com.navneet.executespec.integration.RunPipelineSupport.deserializeRepoIds;

import com.navneet.executespec.state.entity.PipelineRun;

/**
 * Owns the immutable checkout/publication intent admitted at run submission and reconstructed at
 * plan-gate, pause, or crash recovery boundaries.
 */
final class RunBranchPublicationIntent {

  private RunBranchPublicationIntent() {}

  record Resolved(String publicationBranch, String checkoutBranch) {}

  static Resolved resolve(String requestedPublicationBranch, RunOrchestrator.SubmitPolicy policy) {
    String publicationBranch = normalize(requestedPublicationBranch);
    String requestedCheckout = normalize(policy.checkoutBranch());
    String checkoutBranch = requestedCheckout != null ? requestedCheckout : publicationBranch;
    if (publicationBranch != null && !publicationBranch.equals(checkoutBranch)) {
      throw new IllegalArgumentException(
          "Existing-PR publication branch must match the checked-out branch");
    }

    boolean hasPublicationRepo = present(policy.publicationRepoFullName());
    boolean hasPublicationNumber =
        policy.publicationPrNumber() != null && policy.publicationPrNumber() > 0;
    boolean hasPublicationHead =
        policy.publicationHeadSha() != null
            && policy.publicationHeadSha().matches("[0-9a-fA-F]{40}");
    boolean hasPublicationBranch = publicationBranch != null;
    if (hasPublicationBranch != hasPublicationRepo
        || hasPublicationBranch != hasPublicationNumber
        || hasPublicationBranch != hasPublicationHead) {
      throw new IllegalArgumentException(
          "Existing-PR publication requires branch, repository, PR number, and head SHA together");
    }
    return new Resolved(publicationBranch, checkoutBranch);
  }

  static String normalize(String branch) {
    if (branch == null || branch.isBlank()) return null;
    String normalized = branch.trim();
    if (!com.navneet.executespec.run.sync.RunSyncService.isValidBranch(normalized)) {
      throw new IllegalArgumentException("Invalid branch name");
    }
    return normalized;
  }

  static String checkoutBranch(PipelineRun run, String legacyBranchOverride) {
    if (run != null) {
      if (present(run.getCheckoutBranch())) return run.getCheckoutBranch();
      if (present(run.getBranchOverride())) return run.getBranchOverride();
    }
    return legacyBranchOverride;
  }

  static RunOrchestrator.RunPipelineRequest pausedRequest(
      PipelineRun run, PlanGateContinuation continuation) {
    return recoveredRequest(run, continuation);
  }

  static RunOrchestrator.RunPipelineRequest approvedPlanRequest(
      PipelineRun run, PlanGateContinuation continuation) {
    return recoveredRequest(run, continuation);
  }

  private static RunOrchestrator.RunPipelineRequest recoveredRequest(
      PipelineRun run, PlanGateContinuation continuation) {
    return new RunOrchestrator.RunPipelineRequest(
        run.getRunId(),
        run.getUserRequest(),
        run.getAiProvider(),
        run.getWorkerModel(),
        run.getApprovedExecutionMode() == null ? "PRODUCTION" : run.getApprovedExecutionMode(),
        true,
        run.getProjectId(),
        deserializeRepoIds(run.getSelectedRepoIds()),
        null,
        false,
        checkoutBranch(run, null),
        continuation);
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
