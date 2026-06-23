package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

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
  void fullDecisionPreservesExplicitPolicy() {
    var decision =
        AgentIntentDecisions.decision(
            "CODE_RUN_REQUIRED",
            0.8,
            "explicit_code_mutation_request",
            true,
            "reply",
            new TypedAction("create_cloud_run", Map.of("spec", "fix"), true, "run", "?"),
            true,
            true,
            "submit only after confirmation");

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
