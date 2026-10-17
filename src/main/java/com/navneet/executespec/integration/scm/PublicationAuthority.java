package com.navneet.executespec.integration.scm;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;

/** Holds the exact workspace lease and prepared artifact receipt through external publication. */
class PublicationAuthority implements AutoCloseable {
  private final Runnable parentFence;
  private final String workspaceGeneration;
  private final String code;
  private final RunWorkspaceAuthorityService.Lease lease;
  private final PublicationPreparationService.RequiredPreparation prepared;

  PublicationAuthority(
      String workspaceGeneration,
      String code,
      RunWorkspaceAuthorityService.Lease lease,
      PublicationPreparationService.RequiredPreparation prepared,
      Runnable... parentFences) {
    this.parentFence =
        () -> {
          for (Runnable fence : parentFences) fence.run();
        };
    this.workspaceGeneration = workspaceGeneration;
    this.code = code;
    this.lease = lease;
    this.prepared = prepared;
  }

  /** Test-fixture constructor; production admission always carries a durable workspace lease. */
  public PublicationAuthority(String workspaceGeneration, String code) {
    this(workspaceGeneration, code, null, null);
  }

  public String workspaceGeneration() {
    return workspaceGeneration;
  }

  public String code() {
    return code;
  }

  public PublicationPreparationService.RequiredPreparation prepared() {
    return prepared;
  }

  RunWorkspaceAuthorityService.Lease leaseForSettlement() {
    if (lease == null) {
      throw new IllegalStateException("PUBLICATION_SETTLEMENT_AUTHORITY_MISSING");
    }
    return lease;
  }

  /** Re-fences the lease immediately before an irreversible publication side effect. */
  public void assertOwned() {
    if (lease != null) lease.assertOwned();
    parentFence.run();
  }

  static Runnable parentFence(
      com.navneet.executespec.state.entity.PipelineRun run,
      com.navneet.executespec.integration.RunPublicationFence fence) {
    return () -> {
      if ("EXECUTION_VIEW_APPROVED".equalsIgnoreCase(run.getSourceType())
          && (fence == null || !fence.mayPublish(run))) {
        throw new IllegalStateException("PUBLICATION_PARENT_AUTHORITY_LOST");
      }
    };
  }

  static PublicationAdmissionService.Eligibility blockedEligibility(
      String code,
      String blockingGate,
      String reason,
      boolean retryable,
      String remediation,
      String evidenceWorkspaceGeneration,
      String currentWorkspaceGeneration) {
    return new PublicationAdmissionService.Eligibility(
        false,
        "BLOCKED",
        code,
        blockingGate,
        reason,
        retryable,
        remediation,
        evidenceWorkspaceGeneration,
        currentWorkspaceGeneration);
  }

  @Override
  public void close() {
    if (lease != null) lease.close();
  }
}
