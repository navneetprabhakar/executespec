package com.navneet.executespec.integration;

/** Builds immutable submit authority for follow-up runs without inheriting the original payer. */
final class IterationSubmitPolicyFactory {

  private IterationSubmitPolicyFactory() {}

  static RunOrchestrator.SubmitPolicy from(
      RunOrchestrator.IterationPolicy inherited, String actorOrgId) {
    if (inherited == null) throw new IllegalArgumentException("iteration policy is required");
    if (actorOrgId == null || actorOrgId.isBlank()) {
      throw new IllegalArgumentException("iteration actor org is required");
    }
    return new RunOrchestrator.SubmitPolicy(
        inherited.userCostCap(), inherited.skipAutoPush(), inherited.tenantOrgId(), actorOrgId);
  }
}
