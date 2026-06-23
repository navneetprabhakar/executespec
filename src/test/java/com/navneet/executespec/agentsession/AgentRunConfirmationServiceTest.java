package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentRunConfirmationServiceTest {

  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentSessionRepository sessionRepo;
  @Mock ProjectAccessGuard projectAccessGuard;
  @Mock AgentRunSubmissionGate runSubmissionGate;

  private AgentRunConfirmationService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentTurnReadModel turnReadModel = new AgentTurnReadModel(eventRepo, objectMapper);
    AgentEventRecorder eventRecorder = new AgentEventRecorder(eventRepo, objectMapper);
    service =
        new AgentRunConfirmationService(
            turnRepo,
            turnReadModel,
            eventRecorder,
            new AgentTracePayloadFactory(),
            sessionRepo,
            projectAccessGuard,
            runSubmissionGate);
    lenient().when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(eventRepo.save(any(AgentActionEvent.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void rejectsNonRunDecisionBeforeSubmissionGate() throws Exception {
    AgentTurn turn = turn("client-turn-1", "READ_ONLY_CODE_HELP");
    when(turnRepo.findById("client-turn-1")).thenReturn(Optional.of(turn));
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("client-turn-1"))
        .thenReturn(List.of(orchestratorDecisionEvent("client-turn-1", nonRunDecision())));

    assertThatThrownBy(() -> service.confirm(session(), request("client-turn-1"), user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not a run route");

    verifyNoInteractions(runSubmissionGate);
  }

  @Test
  void callsSubmissionGateOnceLinksTurnAndPersistsTrace() throws Exception {
    AgentSession session = session();
    AgentTurn turn = turn("client-turn-2", "CODE_RUN_REQUIRED");
    when(turnRepo.findById("client-turn-2")).thenReturn(Optional.of(turn));
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("client-turn-2"))
        .thenReturn(List.of(orchestratorDecisionEvent("client-turn-2", runDecision("p-action"))));
    when(runSubmissionGate.submitConfirmed(any(), any(), any(), any(), any()))
        .thenReturn(new AgentRunSubmissionGate.Submission("run-123", true, "SUBMITTED"));

    var response = service.confirm(session, request("client-turn-2"), user());

    assertThat(response.runId()).isEqualTo("run-123");
    assertThat(response.calledRunApi()).isTrue();
    assertThat(response.status()).isEqualTo("SUBMITTED");
    assertThat(turn.getLinkedRunId()).isEqualTo("run-123");
    assertThat(turn.getStatus()).isEqualTo("RUN_SUBMITTED");
    verify(projectAccessGuard).assertCanWrite(eq("p-action"), any(AppUserDetails.class));
    verify(runSubmissionGate).submitConfirmed(eq(session), eq(turn), any(), any(), any());
    verify(turnRepo).save(turn);
    verify(sessionRepo).save(session);

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("ORCHESTRATOR_RUN_SUBMITTED");
    assertThat(event.getValue().getPayloadJson()).contains("confirmed_run_submission_gate");
    assertThat(event.getValue().getPayloadJson()).contains("run-123");
    assertThat(event.getValue().getPayloadJson()).contains("confirmationResponse");
  }

  @Test
  void returnsLinkedRunWithoutDuplicateSubmission() {
    AgentTurn turn = turn("client-turn-3", "CODE_RUN_REQUIRED");
    turn.setLinkedRunId("run-existing");
    when(turnRepo.findById("client-turn-3")).thenReturn(Optional.of(turn));

    var response = service.confirm(session(), request("client-turn-3"), user());

    assertThat(response.runId()).isEqualTo("run-existing");
    assertThat(response.calledRunApi()).isFalse();
    assertThat(response.status()).isEqualTo("ALREADY_SUBMITTED");
    verifyNoInteractions(runSubmissionGate);
  }

  @Test
  void rejectsTurnFromAnotherSession() {
    when(turnRepo.findById("client-turn-4"))
        .thenReturn(
            Optional.of(turnFor("client-turn-4", "other-session", "u1", "CODE_RUN_REQUIRED")));

    assertThatThrownBy(() -> service.confirm(session(), request("client-turn-4"), user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("forbidden");

    verifyNoInteractions(runSubmissionGate);
  }

  private static AgentSession session() {
    return AgentSession.builder()
        .id("s1")
        .orgId("org-1")
        .userId("u1")
        .projectId("p-session")
        .clientSurface("CLI")
        .title("Run agent")
        .build();
  }

  private static AgentTurn turn(String id, String intentType) {
    return turnFor(id, "s1", "u1", intentType);
  }

  private static AgentTurn turnFor(String id, String sessionId, String userId, String intentType) {
    return AgentTurn.builder()
        .id(id)
        .sessionId(sessionId)
        .orgId("org-1")
        .userId(userId)
        .sequence(1)
        .userMessage("Implement OAuth login")
        .assistantSummary("Create a cloud run?")
        .intentType(intentType)
        .intentConfidence(0.9)
        .status("NEEDS_CONFIRMATION")
        .build();
  }

  private static OrchestratorRunConfirmationRequest request(String turnId) {
    return new OrchestratorRunConfirmationRequest("s1", turnId, true, null, null, null);
  }

  private static OrchestratorTurnResponse nonRunDecision() {
    return new OrchestratorTurnResponse(
        "client-turn-1",
        "READ_ONLY_CODE_HELP",
        0.9,
        "read_only_code_help",
        false,
        "I will inspect or explain this without creating a run.",
        null,
        new OrchestratorPolicy(false, false, "No run."),
        "orch_trace_existing");
  }

  private static OrchestratorTurnResponse runDecision(String projectId) {
    return new OrchestratorTurnResponse(
        "client-turn-2",
        "CODE_RUN_REQUIRED",
        0.9,
        "explicit_code_mutation_request",
        true,
        "Create a cloud run?",
        new TypedAction(
            "create_cloud_run",
            Map.of("spec", "Implement OAuth login", "projectId", projectId),
            true,
            "run_credit_on_plan_approval",
            "Create a cloud run?"),
        new OrchestratorPolicy(true, true, "Submit only after confirmation."),
        "orch_trace_existing");
  }

  private static AgentActionEvent orchestratorDecisionEvent(
      String turnId, OrchestratorTurnResponse response) throws Exception {
    return AgentActionEvent.builder()
        .id("evt-" + turnId)
        .sessionId("s1")
        .turnId(turnId)
        .type("ORCHESTRATOR_DECISION")
        .payloadJson(new ObjectMapper().writeValueAsString(Map.of("response", response)))
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
