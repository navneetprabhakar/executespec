package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentRunSubmissionGateTest {

  @Mock CustomerRunSubmissionService customerRunSubmissionService;
  @Mock FeatureFlagService featureFlagService;

  private AgentRunSubmissionGate gate;

  @BeforeEach
  void setUp() {
    gate = new AgentRunSubmissionGate(customerRunSubmissionService, featureFlagService);
  }

  @Test
  void rejectsNonRunRouteBeforeSharedAdmission() {
    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Explain this repo"),
                    decision("READ_ONLY_CODE_HELP", false),
                    request(true, null),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not a run route");

    verifyNoInteractions(customerRunSubmissionService);
  }

  @Test
  void rejectsMissingConfirmationBeforeSharedAdmission() {
    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request(false, null),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("confirmation required");

    verifyNoInteractions(customerRunSubmissionService);
  }

  @Test
  void confirmedRunDelegatesExactRepositorySelectionToSharedAdmission() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    PipelineRun run = PipelineRun.builder().runId("run-1").createdByUserId("u1").build();
    when(customerRunSubmissionService.submit(any(), any()))
        .thenReturn(new CustomerRunSubmissionService.Submission(run, true, false));

    var submission =
        gate.submitConfirmed(
            session(),
            turn("Implement OAuth login"),
            decision("CODE_RUN_REQUIRED", true),
            request(true, List.of(first, second)),
            user());

    assertThat(submission.runId()).isEqualTo("run-1");
    assertThat(submission.calledRunApi()).isTrue();
    assertThat(submission.status()).isEqualTo("SUBMITTED");

    var command = ArgumentCaptor.forClass(CustomerRunSubmissionService.Command.class);
    verify(customerRunSubmissionService).submit(command.capture(), any());
    assertThat(command.getValue().userRequest()).isEqualTo("Implement OAuth login");
    assertThat(command.getValue().source()).isEqualTo("AGENT_ORCHESTRATOR");
    assertThat(command.getValue().projectId()).isEqualTo("p1");
    assertThat(command.getValue().selectedRepoIds()).containsExactly(first, second);
    assertThat(command.getValue().idempotencyKey()).isEqualTo("agent-orchestrator:turn-1");
    assertThat(command.getValue().requestFingerprint()).isNotBlank();
  }

  @Test
  void duplicateFromSharedAdmissionRetainsAgentConfirmationSemantics() {
    PipelineRun run = PipelineRun.builder().runId("run-existing").createdByUserId("u1").build();
    when(customerRunSubmissionService.submit(any(), any()))
        .thenReturn(new CustomerRunSubmissionService.Submission(run, false, false));

    var submission =
        gate.submitConfirmed(
            session(),
            turn("Implement OAuth login"),
            decision("CODE_RUN_REQUIRED", true),
            request(true, null),
            user());

    assertThat(submission.calledRunApi()).isFalse();
    assertThat(submission.status()).isEqualTo("ALREADY_SUBMITTED");
  }

  @Test
  void sharedAdmissionFailurePropagatesWithoutASecondSubmissionPath() {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "denied"))
        .when(customerRunSubmissionService)
        .submit(any(), any());

    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    request(true, List.of(UUID.randomUUID())),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("denied");
  }

  @Test
  void confirmationInheritsSessionRouteAndNormalizesExecutionHints() {
    PipelineRun run = PipelineRun.builder().runId("run-2").createdByUserId("u1").build();
    when(customerRunSubmissionService.submit(any(), any()))
        .thenReturn(new CustomerRunSubmissionService.Submission(run, true, false));
    AgentSession session =
        AgentSession.builder()
            .id("s1")
            .orgId("org-1")
            .userId("u1")
            .projectId("p1")
            .defaultProvider("ANTHROPIC")
            .defaultModel("claude-sonnet-4-6")
            .build();

    gate.submitConfirmed(
        session,
        turn("original"),
        decision("CODE_RUN_REQUIRED", true),
        new OrchestratorRunConfirmationRequest(
            "s1",
            "turn-1",
            true,
            null,
            null,
            "lightweight",
            2.5,
            "PROTOTYPE",
            null,
            "edited spec",
            List.of()),
        user());

    var command = ArgumentCaptor.forClass(CustomerRunSubmissionService.Command.class);
    verify(customerRunSubmissionService).submit(command.capture(), any());
    assertThat(command.getValue().userRequest()).isEqualTo("edited spec");
    assertThat(command.getValue().modelName()).isEqualTo("claude-sonnet-4-6");
    assertThat(command.getValue().aiProvider()).isEqualTo("ANTHROPIC");
    assertThat(command.getValue().executionMode()).isEqualTo("PROTOTYPE");
    assertThat(command.getValue().lowComplexityWorkerStartTier()).isEqualTo("LIGHTWEIGHT");
    assertThat(command.getValue().userCostCap()).isEqualTo(2.5);
  }

  @Test
  void confirmedLocalRunRequiresPatAndFailsClosedWithoutConnectorBinding() {
    when(featureFlagService.isEnabled("worker.local-hands.enabled")).thenReturn(true);
    var localRequest =
        new OrchestratorRunConfirmationRequest(
            "s1", "turn-1", true, null, null, null, null, null, true, null, List.of());

    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    localRequest,
                    user(),
                    false))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Personal Access Token");

    assertThatThrownBy(
            () ->
                gate.submitConfirmed(
                    session(),
                    turn("Implement OAuth login"),
                    decision("CODE_RUN_REQUIRED", true),
                    localRequest,
                    user(),
                    true))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("local connector");
    verifyNoInteractions(customerRunSubmissionService);
  }

  @Test
  void structuralBoundaryForbidsDirectRunOrchestratorDependency() {
    assertThat(
            Arrays.stream(AgentRunSubmissionGate.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
        .contains(CustomerRunSubmissionService.class)
        .doesNotContain(RunOrchestrator.class);
  }

  private static OrchestratorRunConfirmationRequest request(
      boolean confirmed, List<UUID> selectedRepoIds) {
    return new OrchestratorRunConfirmationRequest(
        "s1", "turn-1", confirmed, null, null, null, null, null, null, null, selectedRepoIds);
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
