package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;

import org.junit.jupiter.api.Test;

class AgentIntentLegacyAdapterTest {

  @Test
  void mapsCloudRunActionToLegacyCreateRunIntentAndConfirmationPrompt() {
    var decision =
        decision(
            "CODE_RUN_REQUIRED",
            true,
            "Create a cloud run?",
            new TypedAction(
                "create_cloud_run",
                Map.of("spec", "fix it"),
                true,
                "run_credit_on_plan_approval",
                "Confirm run"));

    var legacy = AgentIntentLegacyAdapter.toLegacy(decision);

    assertThat(legacy.intent()).isEqualTo("CREATE_RUN");
    assertThat(legacy.needsConfirmation()).isTrue();
    assertThat(legacy.confirmationPrompt()).isEqualTo("Create a cloud run?");
    assertThat(legacy.response()).isNull();
    assertThat(legacy.proposedAction()).isEqualTo(decision.proposedAction());
  }

  @Test
  void mapsReadOnlyRoutesToLegacyChatWithFallbackResponse() {
    var decision = decision("READ_ONLY_CODE_HELP", false, null, null);

    var legacy = AgentIntentLegacyAdapter.toLegacy(decision);

    assertThat(legacy.intent()).isEqualTo("CHAT");
    assertThat(legacy.needsConfirmation()).isFalse();
    assertThat(legacy.response()).isEqualTo("I will answer this without creating a run.");
  }

  @Test
  void mapsLifecycleActionsToLegacyGateIntents() {
    var approve =
        AgentIntentLegacyAdapter.toLegacy(
            decision(
                "RUN_LIFECYCLE_ACTION",
                true,
                null,
                new TypedAction("approve_run", Map.of("runId", "r1"), true, "run_credit", null)));
    var cancel =
        AgentIntentLegacyAdapter.toLegacy(
            decision(
                "RUN_LIFECYCLE_ACTION",
                true,
                null,
                new TypedAction("cancel_run", Map.of("runId", "r1"), true, "none", null)));

    assertThat(approve.intent()).isEqualTo("APPROVE_GATE");
    assertThat(cancel.intent()).isEqualTo("REJECT_GATE");
  }

  private static OrchestratorDecision decision(
      String route, boolean requiresConfirmation, String assistantReply, TypedAction action) {
    return new OrchestratorDecision(
        route,
        0.9,
        "test",
        requiresConfirmation,
        assistantReply,
        action,
        new OrchestratorPolicy(false, requiresConfirmation, "test"),
        null);
  }
}
