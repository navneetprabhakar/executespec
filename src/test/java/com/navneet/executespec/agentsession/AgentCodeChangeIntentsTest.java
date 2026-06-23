package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.InterpretIntentRequest;

import org.junit.jupiter.api.Test;

class AgentCodeChangeIntentsTest {

  @Test
  void localToolsConvertCodeChangeToLocalTaskWithoutRunApiAccess() {
    var decision =
        decide(
            "implement retries in the webhook handler",
            "project-1",
            new ClientCapabilities(true, true, true, true));

    assertThat(decision.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(decision.reasonCode()).isEqualTo("local_code_change_request");
    assertThat(decision.proposedAction().type()).isEqualTo("local_code_task");
    assertThat(decision.proposedAction().arguments())
        .containsEntry("spec", "implement retries in the webhook handler");
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("conversation_usage");
    assertThat(decision.policy().runApiAllowed()).isFalse();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isTrue();
  }

  @Test
  void projectContextAllowsCloudRunOnlyAfterConfirmation() {
    var decision =
        decide(
            "fix the failing auth refresh test",
            "project-1",
            new ClientCapabilities(false, true, true, true));

    assertThat(decision.route()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(decision.reasonCode()).isEqualTo("explicit_code_mutation_request");
    assertThat(decision.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(decision.proposedAction().arguments())
        .containsEntry("spec", "fix the failing auth refresh test")
        .containsEntry("projectId", "project-1");
    assertThat(decision.proposedAction().billingImpact()).isEqualTo("run_credit_on_plan_approval");
    assertThat(decision.policy().runApiAllowed()).isTrue();
    assertThat(decision.policy().confirmationRequiredBeforeSubmit()).isTrue();
  }

  @Test
  void missingProjectContextDoesNotAllowRunApi() {
    var decision =
        decide("implement OAuth login", null, new ClientCapabilities(false, true, true, true));

    assertThat(decision.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(decision.reasonCode()).isEqualTo("code_mutation_missing_project_context");
    assertThat(decision.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(decision.proposedAction().arguments()).doesNotContainKey("projectId");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void blankProjectContextDoesNotAllowRunApi() {
    var decision = decide("implement OAuth login", "  ", null);

    assertThat(decision.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void nonCodeChangeIsNotClaimed() {
    assertThat(
            AgentCodeChangeIntents.decide(
                "explain OAuth login",
                "explain oauth login",
                new InterpretIntentRequest("s1", "explain OAuth login", "project-1", null, null),
                null))
        .isEmpty();
  }

  private static com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision
      decide(String message, String projectId, ClientCapabilities caps) {
    return AgentCodeChangeIntents.decide(
            message,
            message.toLowerCase(Locale.ROOT),
            new InterpretIntentRequest("s1", message, projectId, null, caps),
            caps)
        .orElseThrow();
  }
}
