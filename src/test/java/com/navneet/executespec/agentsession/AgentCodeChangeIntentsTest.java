package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;

import com.navneet.executespec.agentsession.dto.AgentActionContext;
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
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.requiresUserConfirmation()).isFalse();
    assertThat(decision.policy().runApiAllowed()).isFalse();
  }

  @Test
  void missingProjectContextCarriesTypedBlockingContract() {
    var decision =
        decide("implement OAuth login", null, new ClientCapabilities(false, true, true, true));

    AgentActionContext context = decision.actionContext();
    assertThat(context).isNotNull();
    assertThat(context.confirmable()).isFalse();
    assertThat(context.projectSelectionRequired()).isTrue();
    assertThat(context.requiredContext()).containsExactly(AgentActionContext.CONTEXT_PROJECT);
    assertThat(context.blockingReasons())
        .containsExactly(AgentActionContext.REASON_MISSING_PROJECT);
    assertThat(context.recommendedAction()).isEqualTo(AgentActionContext.ACTION_SELECT_PROJECT);
  }

  @Test
  void confirmableCloudRunCarriesConfirmableContextOnTheActionItself() {
    var decision =
        decide(
            "fix the failing auth refresh test",
            "project-1",
            new ClientCapabilities(false, true, true, true));

    assertThat(decision.actionContext()).isNotNull();
    assertThat(decision.actionContext().confirmable()).isTrue();
    // The context must ride on the action so it survives persistence in
    // agent_turn.proposed_action_json and a client reload.
    assertThat(decision.proposedAction().actionContext()).isNotNull();
    assertThat(decision.proposedAction().actionContext().confirmable()).isTrue();
    assertThat(decision.proposedAction().actionContext().projectSelectionRequired()).isFalse();
  }

  @Test
  void blankProjectContextDoesNotAllowRunApi() {
    var decision = decide("implement OAuth login", "  ", null);

    assertThat(decision.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
    assertThat(decision.actionContext()).isNotNull();
    assertThat(decision.actionContext().confirmable()).isFalse();
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

  @Test
  void aReadOnlyCollaboratorGetsNoConfirmableRunCard() {
    var decision =
        AgentCodeChangeIntents.decide(
                "implement retry logic in the auth client",
                "implement retry logic in the auth client",
                new InterpretIntentRequest(
                    "s1",
                    "implement retry logic in the auth client",
                    "project-1",
                    null,
                    null,
                    Boolean.FALSE),
                null)
            .orElseThrow();

    assertThat(decision.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(decision.proposedAction()).isNull();
    assertThat(decision.policy().runApiAllowed()).isFalse();
    assertThat(decision.actionContext().confirmable()).isFalse();
    assertThat(decision.actionContext().blockingReasons())
        .containsExactly(
            com.navneet.executespec.agentsession.dto.AgentActionContext.REASON_MISSING_WRITE_SCOPE);
  }

  @Test
  void aProjectWriterStillGetsTheConfirmableCloudRunProposal() {
    var decision =
        AgentCodeChangeIntents.decide(
                "implement retry logic in the auth client",
                "implement retry logic in the auth client",
                new InterpretIntentRequest(
                    "s1",
                    "implement retry logic in the auth client",
                    "project-1",
                    null,
                    null,
                    Boolean.TRUE),
                null)
            .orElseThrow();

    assertThat(decision.route()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(decision.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(decision.actionContext().confirmable()).isTrue();
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
