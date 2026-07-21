package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-241 — the plan gate is the cost/governance checkpoint before provider spend and publication.
 * These tests pin that bypass authority is a property of the submitting surface, and that it fails
 * closed for anything a customer can reach.
 */
class PlanGateBypassPolicyTest {

  private final PlanGateBypassPolicy policy = new PlanGateBypassPolicy();

  @Test
  void ordinaryCustomerSubmitSurfacesCanNeverBypassTheGate() {
    // The exact sources an ordinary user's run carries.
    assertThat(policy.bypassAuthorized("REST")).isFalse();
    assertThat(policy.bypassAuthorized("FORK")).isFalse();
    assertThat(policy.bypassAuthorized("AGENT_ORCHESTRATOR")).isFalse();
    assertThat(policy.bypassAuthorized("MANUAL")).isFalse();
    assertThat(policy.bypassAuthorized("CHAT")).isFalse();
  }

  @Test
  void unknownNullAndBlankSourcesFailClosed() {
    assertThat(policy.bypassAuthorized(null)).isFalse();
    assertThat(policy.bypassAuthorized("")).isFalse();
    assertThat(policy.bypassAuthorized("   ")).isFalse();
    assertThat(policy.bypassAuthorized("something-a-client-invented")).isFalse();
  }

  @Test
  void serverInternalAutomationSurfacesKeepTheirBypass() {
    // The four customer-facing autofix surfaces route through FindingPatchSubmitter.
    assertThat(policy.bypassAuthorized("finding:PR_REVIEW_COMMENT")).isTrue();
    assertThat(policy.bypassAuthorized("finding:TECH_DEBT")).isTrue();
    // Org-admin-configured inbound integrations.
    assertThat(policy.bypassAuthorized("JIRA")).isTrue();
    assertThat(policy.bypassAuthorized("SLACK")).isTrue();
    // Operator-only benchmark harnesses.
    assertThat(policy.bypassAuthorized("programbench")).isTrue();
    assertThat(policy.bypassAuthorized("swebench")).isTrue();
    // The legacy solution executor, dispatched only after the launch's own plan approval.
    assertThat(policy.bypassAuthorized("EXECUTION_VIEW_APPROVED")).isTrue();
  }

  @Test
  void unifiedExecutionViewDispatchIsNotAnAutomationSurface() {
    // The unified V3 launch dispatch submits with skipApproval=false and re-uses the V2 gate; it
    // must NOT be able to bypass, or a bug there would silently disable the gate.
    assertThat(policy.bypassAuthorized("EXECUTION_VIEW")).isFalse();
  }

  @Test
  void reservedSourcesAreRejectableAtThePublicApi() {
    // Both halves are needed: the allowlist alone would be spoofable via the request body.
    assertThat(policy.isReservedAutomationSource("finding:CODE_REVIEW")).isTrue();
    assertThat(policy.isReservedAutomationSource("  JIRA  ")).isTrue();
    assertThat(policy.isReservedAutomationSource("REST")).isFalse();
    assertThat(policy.isReservedAutomationSource(null)).isFalse();
  }
}
