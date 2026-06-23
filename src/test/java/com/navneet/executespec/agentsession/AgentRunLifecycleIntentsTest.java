package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class AgentRunLifecycleIntentsTest {

  private static final String RUN_ID = "9586cfee-f442-4927-90f4-ba4f5f645d0b";

  @Test
  void approveWithRunIdReturnsConfirmationActionWithoutRunApiAccess() {
    var decision = decide("approve run " + RUN_ID);

    assertThat(decision.route()).isEqualTo("RUN_LIFECYCLE_ACTION");
    assertThat(decision.reasonCode()).isEqualTo("approve_gate");
    assertThat(decision.requiresUserConfirmation()).isTrue();
    assertThat(decision.proposedAction().type()).isEqualTo("approve_run");
    assertThat(decision.proposedAction().arguments()).containsEntry("runId", RUN_ID);
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("run_credit");
    assertThat(decision.policy().runApiAllowed()).isFalse();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isTrue();
  }

  @Test
  void approveWithoutRunIdClarifiesAndDoesNotCreateAction() {
    var decision = decide("approve it");

    assertThat(decision.route()).isEqualTo("CLARIFY");
    assertThat(decision.reasonCode()).isEqualTo("run_lifecycle_missing_run_id");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void rejectAndCancelUseDistinctActionTypes() {
    assertThat(decide("reject run " + RUN_ID).proposedAction().type()).isEqualTo("reject_run");
    assertThat(decide("cancel run " + RUN_ID).proposedAction().type()).isEqualTo("cancel_run");
  }

  @Test
  void rejectWithoutRunIdClarifies() {
    var decision = decide("reject it");

    assertThat(decision.route()).isEqualTo("CLARIFY");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.assistantReply()).contains("Which run");
  }

  @Test
  void listRunsAndGatesStayReadOnlyLifecycleActions() {
    var decision = decide("show recent runs");

    assertThat(decision.route()).isEqualTo("RUN_LIFECYCLE_ACTION");
    assertThat(decision.proposedAction().type()).isEqualTo("list_gates");
    assertThat(decision.proposedAction().requiresConfirmation()).isFalse();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void nonLifecycleTextIsEmpty() {
    assertThat(AgentRunLifecycleIntents.decide("explain auth", "explain auth")).isEmpty();
  }

  private static com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision
      decide(String message) {
    return AgentRunLifecycleIntents.decide(message, message.toLowerCase(Locale.ROOT)).orElseThrow();
  }
}
