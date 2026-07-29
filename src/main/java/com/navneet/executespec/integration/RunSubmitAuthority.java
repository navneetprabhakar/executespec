package com.navneet.executespec.integration;

import lombok.extern.slf4j.Slf4j;

/** Resolves the immutable host-tenant and payer identities stamped during run submission. */
@Slf4j
record RunSubmitAuthority(String tenantOrgId, String billingOrgId) {

  static RunSubmitAuthority resolve(
      RunOrchestrator.SubmitPolicy policy, String actorOrgId, String runId) {
    String tenant = presentOr(policy.tenantOrgId(), actorOrgId);
    String billing = presentOr(policy.billingOrgId(), actorOrgId);
    if (!tenant.equals(billing)) {
      log.info(
          "RunOrchestrator run={} tenant resolved to host project org={} (billing org={})",
          runId,
          tenant,
          billing);
    }
    return new RunSubmitAuthority(tenant, billing);
  }

  private static String presentOr(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
