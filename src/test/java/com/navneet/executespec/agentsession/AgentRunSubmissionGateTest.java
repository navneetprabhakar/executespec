package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

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
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.run.RunIdempotencyClaim;
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
  @Mock ProjectAccessGuard projectAccessGuard;

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
            stateStore,
            projectAccessGuard);
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
    when(runIdempotencyService.claim(eq("u1"), eq("agent-orchestrator:turn-1"), anyString()))
        .thenReturn(RunIdempotencyClaim.claimed("u1", "agent-orchestrator:turn-1", "owner", 1L));
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
            nullable(String.class)))
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
            (String) null);
    verify(usageTrackingService).increment("u1", "RUNS", 1);
    verify(runIdempotencyService)
        .complete(
            argThat(c -> "agent-orchestrator:turn-1".equals(c.idempotencyKey()) && c.owned()),
            eq("run-1"));
  }

  @Test
  void aPreSubmitValidationFailureReleasesTheIdempotencyClaim() {
    // RR-1579: credit exhaustion, a disallowed model or a missing BYOK key used to leave the
    // durable claim held with no run id, so the retry after fixing the problem was rejected as a
    // duplicate submission until the lease elapsed.
    var claim = RunIdempotencyClaim.claimed("u1", "agent-orchestrator:turn-1", "owner", 1L);
    when(runIdempotencyService.claim(eq("u1"), eq("agent-orchestrator:turn-1"), anyString()))
        .thenReturn(claim);
    org.mockito.Mockito.doThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "AI credits exhausted"))
        .when(managedAiUsageService)
        .assertCanStartRun(any(), anyBoolean());

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request(true),
                    user()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

    verify(runIdempotencyService).failFinal(eq(claim), anyString());
    verify(runIdempotencyService, org.mockito.Mockito.never()).complete(any(), anyString());
  }

  @Test
  void confirmationInheritsSessionProviderAndModelWhenClientsDoNotEchoThem() {
    when(runIdempotencyService.claim(eq("u1"), eq("agent-orchestrator:turn-1"), anyString()))
        .thenReturn(RunIdempotencyClaim.claimed("u1", "agent-orchestrator:turn-1", "owner", 1L));
    when(runOrchestrator.submitRun(
            anyString(),
            anyString(),
            isNull(),
            anyString(),
            isNull(),
            isNull(),
            anyBoolean(),
            anyBoolean(),
            anyString(),
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
            nullable(String.class)))
        .thenReturn(PipelineRun.builder().runId("run-session-route").createdByUserId("u1").build());
    AgentSession session =
        AgentSession.builder()
            .id("s1")
            .orgId("org-1")
            .userId("u1")
            .projectId("p1")
            .defaultProvider("ANTHROPIC")
            .defaultModel("claude-opus-4-8")
            .build();

    gate.submitConfirmed(
        session,
        turn("Implement OAuth login"),
        decision("CODE_RUN_REQUIRED", true),
        request(true),
        user());

    verify(modelAccessService).assertProviderModelPair("ANTHROPIC", "claude-opus-4-8");
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
            "claude-opus-4-8",
            "ANTHROPIC",
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
            (String) null);
  }

  @Test
  void confirmedRunCanStartLowComplexityWorkersOnLightweightTier() {
    when(runIdempotencyService.claim(eq("u1"), eq("agent-orchestrator:turn-1"), anyString()))
        .thenReturn(RunIdempotencyClaim.claimed("u1", "agent-orchestrator:turn-1", "owner", 1L));
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
            anyString()))
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
            "LIGHTWEIGHT");
  }

  @Test
  void confirmedRunPropagatesPrototypeExecutionModeAndEditedSpec() {
    when(runIdempotencyService.claim(eq("u1"), eq("agent-orchestrator:turn-1"), anyString()))
        .thenReturn(RunIdempotencyClaim.claimed("u1", "agent-orchestrator:turn-1", "owner", 1L));
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
            nullable(String.class)))
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
            (String) null);
  }

  @Test
  void confirmedLocalRunRequiresPatAndFailsClosedWithoutConnectorBinding() {
    when(featureFlagService.isEnabled("worker.local-hands.enabled")).thenReturn(true);

    var request =
        new OrchestratorRunConfirmationRequest(
            "s1", "turn-1", true, null, null, null, null, null, true, null);
    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request,
                    user(),
                    true))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("local connector and repository selection");
    verifyNoInteractions(runOrchestrator);

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

  @Test
  void projectWriteDenialStopsConfirmedRunBeforeSubmission() {
    doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "denied"))
        .when(projectAccessGuard)
        .assertCanWrite(anyString(), any());

    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request(true),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("denied");

    verifyNoInteractions(runOrchestrator);
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
