package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;

import com.navneet.executespec.agent.ApiKeyResolver;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.auth.service.TierEnforcementService;
import com.navneet.executespec.billing.service.ManagedAiUsageService;
import com.navneet.executespec.billing.service.UsageTrackingService;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.run.RunIdempotencyService;
import com.navneet.executespec.security.InputSanitizer;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentRunSubmissionGateTest {

  @Mock RunOrchestrator runOrchestrator;
  @Mock FeatureFlagService featureFlagService;
  @Mock InputSanitizer inputSanitizer;
  @Mock ModelAccessService modelAccessService;
  @Mock TierEnforcementService tierEnforcementService;
  @Mock UsageTrackingService usageTrackingService;
  @Mock ApiKeyResolver apiKeyResolver;
  @Mock ManagedAiUsageService managedAiUsageService;
  @Mock AiProviderPreferenceService aiProviderPreferenceService;
  @Mock RunIdempotencyService runIdempotencyService;
  @Mock StateStore stateStore;

  private AgentRunSubmissionGate gate;

  @BeforeEach
  void setUp() {
    gate =
        new AgentRunSubmissionGate(
            runOrchestrator,
            featureFlagService,
            inputSanitizer,
            modelAccessService,
            tierEnforcementService,
            usageTrackingService,
            apiKeyResolver,
            managedAiUsageService,
            aiProviderPreferenceService,
            runIdempotencyService,
            stateStore);
  }

  @Test
  void rejectsNonRunRouteBeforeCallingRunOrchestrator() {
    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Explain this repo"),
                    decision("READ_ONLY_CODE_HELP", false),
                    request(true),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not a run route");

    verifyNoInteractions(runOrchestrator);
  }

  @Test
  void rejectsMissingConfirmationBeforeCallingRunOrchestrator() {
    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request(false),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("confirmation required");

    verifyNoInteractions(runOrchestrator);
  }

  @Test
  void confirmedRunSubmitsExactlyOnceAndRecordsUsage() {
    when(runIdempotencyService.lookup("u1", "agent-orchestrator:turn-1"))
        .thenReturn(Optional.empty());
    when(aiProviderPreferenceService.preferredProvider(any())).thenReturn("DEEPSEEK");
    when(runOrchestrator.submitRun(
            anyString(),
            anyString(),
            isNull(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            anyBoolean(),
            isNull(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            isNull(),
            isNull(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            nullable(String.class),
            anyBoolean()))
        .thenReturn(PipelineRun.builder().runId("run-1").createdByUserId("u1").build());

    var submission =
        gate.submitConfirmed(
            session(),
            turn("Implement OAuth login"),
            decision("CODE_RUN_REQUIRED", true),
            request(true),
            user());

    assertThat(submission.runId()).isEqualTo("run-1");
    assertThat(submission.calledRunApi()).isTrue();
    verify(runOrchestrator)
        .submitRun(
            "Implement OAuth login",
            "AGENT_ORCHESTRATOR",
            null,
            "EXISTING",
            null,
            null,
            false,
            false,
            null,
            "DEEPSEEK",
            "u1",
            "org-1",
            "p1",
            "PRODUCTION",
            null,
            null,
            false,
            null,
            null,
            (Boolean) null,
            (Boolean) null,
            (String) null,
            false);
    verify(usageTrackingService).increment("u1", "RUNS", 1);
    verify(runIdempotencyService).record("u1", "agent-orchestrator:turn-1", "run-1");
  }

  @Test
  void confirmedRunCanStartLowComplexityWorkersOnLightweightTier() {
    when(runIdempotencyService.lookup("u1", "agent-orchestrator:turn-1"))
        .thenReturn(Optional.empty());
    when(aiProviderPreferenceService.preferredProvider(any())).thenReturn("DEEPSEEK");
    when(runOrchestrator.submitRun(
            anyString(),
            anyString(),
            isNull(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            anyBoolean(),
            isNull(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            isNull(),
            isNull(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            anyString(),
            anyBoolean()))
        .thenReturn(PipelineRun.builder().runId("run-2").createdByUserId("u1").build());

    var submission =
        gate.submitConfirmed(
            session(),
            turn("Implement OAuth login"),
            decision("CODE_RUN_REQUIRED", true),
            requestWithLowTier("lightweight"),
            user());

    assertThat(submission.runId()).isEqualTo("run-2");
    verify(runOrchestrator)
        .submitRun(
            "Implement OAuth login",
            "AGENT_ORCHESTRATOR",
            null,
            "EXISTING",
            null,
            null,
            false,
            false,
            null,
            "DEEPSEEK",
            "u1",
            "org-1",
            "p1",
            "PRODUCTION",
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            "LIGHTWEIGHT",
            false);
  }

  @Test
  void confirmedRunPropagatesPrototypeExecutionModeAndEditedSpec() {
    when(runIdempotencyService.lookup("u1", "agent-orchestrator:turn-1"))
        .thenReturn(Optional.empty());
    when(aiProviderPreferenceService.preferredProvider(any())).thenReturn("DEEPSEEK");
    when(runOrchestrator.submitRun(
            anyString(),
            anyString(),
            isNull(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            anyBoolean(),
            isNull(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            isNull(),
            isNull(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            nullable(String.class),
            anyBoolean()))
        .thenReturn(PipelineRun.builder().runId("run-3").createdByUserId("u1").build());

    gate.submitConfirmed(
        session(),
        turn("original"),
        decision("CODE_RUN_REQUIRED", true),
        new OrchestratorRunConfirmationRequest(
            "s1", "turn-1", true, null, null, null, null, "PROTOTYPE", null, "edited spec"),
        user());

    verify(runOrchestrator)
        .submitRun(
            "edited spec",
            "AGENT_ORCHESTRATOR",
            null,
            "EXISTING",
            null,
            null,
            false,
            false,
            null,
            "DEEPSEEK",
            "u1",
            "org-1",
            "p1",
            "PROTOTYPE",
            null,
            null,
            false,
            null,
            null,
            (Boolean) null,
            (Boolean) null,
            (String) null,
            false);
  }

  @Test
  void confirmedLocalRunRequiresPatAndPassesLocalIntentBeforeAsyncStart() {
    when(featureFlagService.isEnabled("worker.local-hands.enabled")).thenReturn(true);
    when(runIdempotencyService.lookup("u1", "agent-orchestrator:turn-1"))
        .thenReturn(Optional.empty());
    when(aiProviderPreferenceService.preferredProvider(any())).thenReturn("DEEPSEEK");
    when(runOrchestrator.submitRun(
            anyString(),
            anyString(),
            isNull(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            anyBoolean(),
            isNull(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            isNull(),
            isNull(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            nullable(String.class),
            anyBoolean()))
        .thenReturn(PipelineRun.builder().runId("run-local").createdByUserId("u1").build());

    var request =
        new OrchestratorRunConfirmationRequest(
            "s1", "turn-1", true, null, null, null, null, null, true, null);
    gate.submitConfirmed(
        session(),
        turn("Implement OAuth login"),
        decision("CODE_RUN_REQUIRED", true),
        request,
        user(),
        true);

    verify(runOrchestrator)
        .submitRun(
            "Implement OAuth login",
            "AGENT_ORCHESTRATOR",
            null,
            "EXISTING",
            null,
            null,
            false,
            false,
            null,
            "DEEPSEEK",
            "u1",
            "org-1",
            "p1",
            "PRODUCTION",
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            true);

    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request,
                    user(),
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Personal Access Token");
  }

  private static OrchestratorRunConfirmationRequest request(boolean confirmed) {
    return new OrchestratorRunConfirmationRequest(
        "s1", "turn-1", confirmed, null, null, null, null);
  }

  private static OrchestratorRunConfirmationRequest requestWithLowTier(String lowStartTier) {
    return new OrchestratorRunConfirmationRequest(
        "s1", "turn-1", true, null, null, lowStartTier, null);
  }

  private static OrchestratorTurnResponse decision(String route, boolean runAllowed) {
    return new OrchestratorTurnResponse(
        "turn-1",
        route,
        0.9,
        "test_route",
        true,
        "Create a cloud run?",
        new TypedAction(
            "create_cloud_run",
            Map.of("spec", "Implement OAuth login", "projectId", "p1"),
            true,
            "run_credit_on_plan_approval",
            "Create a cloud run?"),
        new OrchestratorPolicy(
            runAllowed, true, runAllowed ? "Submit after confirmation." : "No run."),
        "orch_trace_1");
  }

  private static AgentSession session() {
    return AgentSession.builder().id("s1").orgId("org-1").userId("u1").projectId("p1").build();
  }

  private static AgentTurn turn(String message) {
    return AgentTurn.builder()
        .id("turn-1")
        .sessionId("s1")
        .orgId("org-1")
        .userId("u1")
        .sequence(1)
        .userMessage(message)
        .intentType("CODE_RUN_REQUIRED")
        .intentConfidence(0.9)
        .build();
  }

  private static AppUserDetails user() {
    AppUser user = new AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setTier("PRO");
    user.setEmail("u1@example.com");
    return new AppUserDetails(user, null);
  }
}
