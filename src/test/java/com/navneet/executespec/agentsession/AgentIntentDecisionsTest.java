package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentActionContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

import org.junit.jupiter.api.Test;

class AgentIntentDecisionsTest {

  @Test
  void actionDecisionRequiresConfirmationOnlyWhenActionRequiresIt() {
    var confirmAction =
        new TypedAction("local_code_task", Map.of("spec", "fix"), true, "conversation_usage", "?");
    var confirmDecision =
        AgentIntentDecisions.decision(
            "IMPLEMENTATION_PROPOSAL", 0.8, confirmAction, "local_code_change_request", "reply");

    assertThat(confirmDecision.requiresUserConfirmation()).isTrue();
    assertThat(confirmDecision.policy().runApiAllowed()).isFalse();
    assertThat(confirmDecision.policy().confirmationRequiredBeforeSubmit()).isTrue();
    assertThat(confirmDecision.policy().reason()).contains("No new run submission");

    var readAction = new TypedAction("list_gates", Map.of(), false, "none", null);
    var readDecision =
        AgentIntentDecisions.decision(
            "RUN_LIFECYCLE_ACTION", 0.95, readAction, "list_or_show_runs", "reply");

    assertThat(readDecision.requiresUserConfirmation()).isFalse();
    assertThat(readDecision.policy().confirmationRequiredBeforeSubmit()).isFalse();
  }

  @Test
  void cloudRunActionIsRefusedWithoutProjectContext() {
    var action =
        new TypedAction(
            "create_cloud_run",
            Map.of("spec", "add tests"),
            true,
            "run_credit_on_plan_approval",
            "?");

    assertThatThrownBy(
            () ->
                AgentIntentDecisions.decision(
                    "CODE_RUN_REQUIRED",
                    0.8,
                    "explicit_code_mutation_request",
                    true,
                    "reply",
                    action,
                    true,
                    true,
                    "reason"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("without project context");
  }

  @Test
  void cloudRunActionIsRefusedWithABlockedContext() {
    var action =
        new TypedAction(
            "create_cloud_run",
            Map.of("spec", "add tests", "projectId", "p1"),
            true,
            "run_credit_on_plan_approval",
            "?",
            AgentActionContext.requiresProjectSelection());

    assertThatThrownBy(
            () ->
                AgentIntentDecisions.decision(
                    "CODE_RUN_REQUIRED",
                    0.8,
                    "explicit_code_mutation_request",
                    true,
                    "reply",
                    action,
                    true,
                    true,
                    "reason",
                    AgentActionContext.requiresProjectSelection()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("blocked context");
  }

  @Test
  void cloudRunActionIsRefusedWhenTheRunApiIsDisallowed() {
    var action =
        new TypedAction(
            "create_cloud_run",
            Map.of("spec", "add tests", "projectId", "p1"),
            true,
            "run_credit_on_plan_approval",
            "?",
            AgentActionContext.ready());

    assertThatThrownBy(
            () ->
                AgentIntentDecisions.decision(
                    "CODE_RUN_REQUIRED",
                    0.8,
                    "explicit_code_mutation_request",
                    true,
                    "reply",
                    action,
                    false,
                    true,
                    "reason",
                    AgentActionContext.ready()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Run API is disallowed");
  }

  @Test
  void blockedContextCannotClaimToBeConfirmable() {
    assertThatThrownBy(
            () ->
                new AgentActionContext(java.util.List.of(), java.util.List.of(), false, "X", false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AgentActionContext(
                    java.util.List.of(), java.util.List.of("BLOCKED"), true, "X", false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCloudRunProposalWithoutATypedContextIsRejected() {
    // The context is where both missing-project and missing-write-scope blocking live; an absent
    // one would skip every structural check that keeps a clickable card executable.
    assertThatThrownBy(
            () ->
                AgentIntentDecisions.decision(
                    "CODE_RUN_REQUIRED",
                    0.8,
                    "explicit_code_mutation_request",
                    true,
                    "reply",
                    new TypedAction(
                        "create_cloud_run",
                        Map.of("spec", "fix", "projectId", "p1"),
                        true,
                        "run",
                        "?"),
                    true,
                    true,
                    "submit only after confirmation"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void cancelRunHasAnExplicitBillingImpact() {
    assertThat(AgentIntentDecisions.billingImpact("cancel_run")).isEqualTo("none");
  }

  @Test
  void fullDecisionPreservesExplicitPolicy() {
    var decision =
        AgentIntentDecisions.decision(
            "CODE_RUN_REQUIRED",
            0.8,
            "explicit_code_mutation_request",
            true,
            "reply",
            new TypedAction(
                "create_cloud_run",
                Map.of("spec", "fix", "projectId", "p1"),
                true,
                "run",
                "?",
                AgentActionContext.ready()),
            true,
            true,
            "submit only after confirmation",
            AgentActionContext.ready());

    assertThat(decision.policy().runApiAllowed()).isTrue();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isTrue();
    assertThat(decision.policy().reason()).isEqualTo("submit only after confirmation");
    assertThat(decision.traceId()).isNull();
  }

  @Test
  void billingImpactMatchesAgentActionContract() {
    assertThat(AgentIntentDecisions.billingImpact("approve_run")).isEqualTo("run_credit");
    assertThat(AgentIntentDecisions.billingImpact("create_cloud_run"))
        .isEqualTo("run_credit_on_plan_approval");
    assertThat(AgentIntentDecisions.billingImpact("local_code_task"))
        .isEqualTo("conversation_usage");
    assertThat(AgentIntentDecisions.billingImpact("cancel_run")).isEqualTo("none");
  }
}
