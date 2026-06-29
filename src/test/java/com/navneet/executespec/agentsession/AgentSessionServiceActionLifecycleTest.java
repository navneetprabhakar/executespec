package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorMode;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRepoContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRunConfirmationRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.StreamTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.repository.AgentUsageLedgerRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.AiProviderPreferenceService;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.run.RunIdempotencyService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentSessionServiceActionLifecycleTest {

  @Mock AgentSessionRepository sessionRepo;
  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentUsageLedgerRepository usageRepo;
  @Mock ProjectAccessGuard projectAccessGuard;
  @Mock RunAccessGuard runAccessGuard;
  @Mock ProjectRepository projectRepo;
  @Mock AgentContextResolver contextResolver;
  @Mock PipelineRunRepository pipelineRunRepo;
  @Mock AiProviderPreferenceService aiProviderPreferenceService;
  @Mock ModelAccessService modelAccessService;
  @Mock AgentRunSubmissionGate runSubmissionGate;
  @Mock RunIdempotencyService runIdempotencyService;

  private AgentSessionService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentIntentService intentService = new AgentIntentService();
    AgentTurnReadModel turnReadModel = new AgentTurnReadModel(eventRepo, objectMapper);
    AgentTurnRecorder turnRecorder = new AgentTurnRecorder(turnRepo, objectMapper);
    AgentEventRecorder eventRecorder = new AgentEventRecorder(eventRepo, objectMapper);
    AgentTracePayloadFactory tracePayloadFactory = new AgentTracePayloadFactory();
    AgentGateDecisionCardFactory gateCardFactory =
        new AgentGateDecisionCardFactory(projectRepo, objectMapper);
    service =
        new AgentSessionService(
            intentService,
            new AgentActionCatalog(),
            new AgentSessionReadService(sessionRepo, turnRepo, eventRepo, turnReadModel),
            new AgentSessionCreationService(
                sessionRepo,
                projectAccessGuard,
                runAccessGuard,
                aiProviderPreferenceService,
                modelAccessService),
            new AgentActionLifecycleService(intentService, eventRecorder, sessionRepo),
            new AgentGateReadService(pipelineRunRepo, runAccessGuard, gateCardFactory),
            new AgentOrchestratorTurnService(
                turnRepo,
                turnReadModel,
                intentService,
                turnRecorder,
                eventRecorder,
                tracePayloadFactory,
                sessionRepo,
                projectAccessGuard),
            new AgentRunConfirmationService(
                turnRepo,
                turnReadModel,
                eventRecorder,
                tracePayloadFactory,
                sessionRepo,
                projectAccessGuard,
                runSubmissionGate,
                runIdempotencyService),
            new AgentRecordedTurnService(intentService, turnRecorder, eventRecorder, sessionRepo),
            new AgentStreamTurnStarter(
                intentService,
                turnRecorder,
                eventRecorder,
                tracePayloadFactory,
                sessionRepo,
                aiProviderPreferenceService),
            new AgentStreamTurnLifecycle(turnRepo, eventRecorder, usageRepo),
            contextResolver);
    lenient()
        .when(eventRepo.save(any(AgentActionEvent.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient().when(sessionRepo.findById("s1")).thenReturn(Optional.of(session()));
    lenient().when(turnRepo.findById(any())).thenReturn(Optional.empty());
  }

  @Test
  void createSessionPersistsValidatedRunScope() {
    PipelineRun run = PipelineRun.builder().runId("run-1").projectId("p1").build();
    when(runAccessGuard.requireRunAccess(any(), any())).thenReturn(run);

    var response =
        service.createSession(
            new com.navneet.executespec.agentsession.dto.AgentSessionDtos.CreateSessionRequest(
                null,
                null,
                null,
                null,
                null,
                "WEB",
                "Run agent",
                null,
                null,
                "PRODUCTION",
                "CONFIRM_MUTATIONS",
                "run-1"),
            user());

    assertThat(response.runId()).isEqualTo("run-1");
    assertThat(response.projectId()).isEqualTo("p1");
    verify(runAccessGuard).requireRunAccess(any(), any());

    var saved = ArgumentCaptor.forClass(AgentSession.class);
    verify(sessionRepo).save(saved.capture());
    assertThat(saved.getValue().getRunId()).isEqualTo("run-1");
    assertThat(saved.getValue().getProjectId()).isEqualTo("p1");
    assertThat(saved.getValue().getClientSurface()).isEqualTo("WEB");
  }

  @Test
  void planActionRecordsPlannedEventAndReturnsSurfaceScopedCapabilities() throws Exception {
    var response =
        service.planAction(
            "s1",
            new ActionPlanRequest(
                "implement retries in the CLI",
                "p1",
                null,
                new ClientCapabilities(true, true, true, true),
                "VSCODE"),
            user());

    assertThat(response.sessionId()).isEqualTo("s1");
    assertThat(response.decision().intent()).isEqualTo("LOCAL_CODE_TASK");
    assertThat(response.capabilities()).extracting("name").contains("local_code_task");

    var saved = ArgumentCaptor.forClass(AgentActionEvent.class);
    org.mockito.Mockito.verify(eventRepo).save(saved.capture());
    assertThat(saved.getValue().getType()).isEqualTo("ACTION_PLANNED");
    assertThat(saved.getValue().getActionId()).isEqualTo(response.actionId());
    assertThat(saved.getValue().getPayloadJson()).contains(response.actionId());
    assertThat(saved.getValue().getPayloadJson()).contains("VSCODE");
  }

  @Test
  void recordActionResultPersistsTouchedFilesAndVerification() {
    var response =
        service.recordActionResult(
            "s1",
            "a1",
            new ActionResultRequest(
                "failed",
                "missing expected artifact",
                "run-1",
                List.of("README.md"),
                Map.of("missingArtifacts", List.of("CODEMAP.md"), "passed", false)),
            user());

    assertThat(response.status()).isEqualTo("FAILED");
    assertThat(response.summary()).isEqualTo("missing expected artifact");

    var saved = ArgumentCaptor.forClass(AgentActionEvent.class);
    org.mockito.Mockito.verify(eventRepo).save(saved.capture());
    assertThat(saved.getValue().getType()).isEqualTo("ACTION_RESULT");
    assertThat(saved.getValue().getActionId()).isEqualTo("a1");
    assertThat(saved.getValue().getPayloadJson()).contains("README.md");
    assertThat(saved.getValue().getPayloadJson()).contains("CODEMAP.md");
    verify(sessionRepo).save(any(AgentSession.class));
  }

  @Test
  void listTurnsReturnsLatestBoundedTranscriptBeforeCursor() throws Exception {
    when(turnRepo.findBySessionIdOrderBySequenceAsc("s1"))
        .thenReturn(
            List.of(
                turn("t1", 1, "first"),
                turn("t2", 2, "second"),
                turn("t3", 3, "third"),
                turn("t4", 4, "fourth")));
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t2"))
        .thenReturn(
            List.of(
                orchestratorDecisionEvent(
                    "t2",
                    new OrchestratorTurnResponse(
                        "t2",
                        "PLAN_ONLY",
                        0.91,
                        "plan_or_how_would_request",
                        false,
                        "I will write the plan without creating a run.",
                        null,
                        new OrchestratorPolicy(false, false, "Planning request."),
                        "orch_trace_t2"))));
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t3"))
        .thenReturn(
            List.of(
                orchestratorDecisionEvent(
                    "t3",
                    new OrchestratorTurnResponse(
                        "t3",
                        "READ_ONLY_CODE_HELP",
                        0.96,
                        "explicit_no_edit_instruction",
                        false,
                        "I will explain without edits.",
                        null,
                        new OrchestratorPolicy(false, false, "Read-only request."),
                        "orch_trace_t3"))));

    var response = service.listTurns("s1", "t4", 2, user());

    assertThat(response).extracting("turnId").containsExactly("t2", "t3");
    assertThat(response.get(0).userMessage()).isEqualTo("second");
    assertThat(response.get(0).route()).isEqualTo("PLAN_ONLY");
    assertThat(response.get(0).reasonCode()).isEqualTo("plan_or_how_would_request");
    assertThat(response.get(1).assistantSummary()).isEqualTo("reply-third");
    assertThat(response.get(1).route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(response.get(1).reasonCode()).isEqualTo("explicit_no_edit_instruction");
  }

  @Test
  void beginStreamTurnPersistsOrchestratorRouteAndPolicyTrace() {
    when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));

    var ctx =
        service.beginStreamTurn(
            "s1",
            new StreamTurnRequest(
                "Find the auth bug",
                null,
                null,
                new ClientCapabilities(false, true, true, true),
                null),
            user());

    assertThat(ctx.decision().route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(ctx.decision().policy().runApiAllowed()).isFalse();

    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getIntentType()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(turn.getValue().getActionType()).isNull();

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("STREAM_STARTED");
    assertThat(event.getValue().getPayloadJson()).contains("READ_ONLY_CODE_HELP");
    assertThat(event.getValue().getPayloadJson()).contains("runApiAllowed");
    assertThat(event.getValue().getPayloadJson()).contains("false");
  }

  @Test
  void orchestrateTurnPersistsStructuredDecisionWithoutSubmittingRun() {
    when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));

    var response =
        service.orchestrateTurn(
            orchestratorRequest("client-turn-1", "Explain this repo", "auto"), user());

    assertThat(response.turnId()).isEqualTo("client-turn-1");
    assertThat(response.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(response.policy().runApiAllowed()).isFalse();
    assertThat(response.traceId()).startsWith("orch_trace_");

    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getId()).isEqualTo("client-turn-1");
    assertThat(turn.getValue().getIntentType()).isEqualTo("READ_ONLY_CODE_HELP");

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("ORCHESTRATOR_DECISION");
    assertThat(event.getValue().getPayloadJson()).contains("calledRunApi");
    assertThat(event.getValue().getPayloadJson()).contains("false");
    assertThat(event.getValue().getPayloadJson()).contains("promptVersion");
    assertThat(event.getValue().getPayloadJson()).contains("policyDecisions");
  }

  @Test
  void orchestrateTurnReadOnlyModeBlocksRunAllowedDecision() {
    when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));

    var response =
        service.orchestrateTurn(
            orchestratorRequest("client-turn-2", "implement OAuth login", "read_only", false),
            user());

    assertThat(response.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(response.requiresUserConfirmation()).isTrue();
    assertThat(response.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(response.policy().runApiAllowed()).isFalse();
    assertThat(response.reasonCode()).isEqualTo("read_only_permission_blocks_run");
  }

  @Test
  void orchestrateTurnReplaysExistingDecisionForSameTurnId() throws Exception {
    AgentTurn existing = turn("client-turn-3", 1, "Explain this repo");
    existing.setIntentType("READ_ONLY_CODE_HELP");
    when(turnRepo.findById("client-turn-3")).thenReturn(Optional.of(existing));
    OrchestratorTurnResponse original =
        new OrchestratorTurnResponse(
            "client-turn-3",
            "READ_ONLY_CODE_HELP",
            0.9,
            "read_only_code_help",
            false,
            "I will inspect or explain this without creating a run.",
            null,
            new OrchestratorPolicy(false, false, "Read-only replay."),
            "orch_trace_existing");
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("client-turn-3"))
        .thenReturn(
            List.of(
                AgentActionEvent.builder()
                    .id("evt-1")
                    .sessionId("s1")
                    .turnId("client-turn-3")
                    .type("ORCHESTRATOR_DECISION")
                    .payloadJson(
                        new ObjectMapper().writeValueAsString(Map.of("response", original)))
                    .build()));

    var response =
        service.orchestrateTurn(
            orchestratorRequest("client-turn-3", "Explain this repo", "auto"), user());

    assertThat(response.traceId()).isEqualTo("orch_trace_existing");
    assertThat(response.route()).isEqualTo("READ_ONLY_CODE_HELP");
    org.mockito.Mockito.verify(turnRepo, org.mockito.Mockito.never()).save(any(AgentTurn.class));
  }

  @Test
  void confirmRunSubmissionRejectsNonRunDecisionBeforeSubmissionGate() throws Exception {
    AgentTurn existing = turn("client-turn-4", 1, "Explain this repo");
    existing.setIntentType("READ_ONLY_CODE_HELP");
    when(turnRepo.findById("client-turn-4")).thenReturn(Optional.of(existing));
    OrchestratorTurnResponse decision =
        new OrchestratorTurnResponse(
            "client-turn-4",
            "READ_ONLY_CODE_HELP",
            0.9,
            "read_only_code_help",
            false,
            "I will inspect or explain this without creating a run.",
            null,
            new OrchestratorPolicy(false, false, "No run."),
            "orch_trace_existing");
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("client-turn-4"))
        .thenReturn(List.of(orchestratorDecisionEvent("client-turn-4", decision)));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.confirmRunSubmission(
                    new OrchestratorRunConfirmationRequest(
                        "s1", "client-turn-4", true, null, null, null, null),
                    user()))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
        .hasMessageContaining("not a run route");

    org.mockito.Mockito.verifyNoInteractions(runSubmissionGate);
  }

  @Test
  void confirmRunSubmissionCallsSubmissionGateOnceAndLinksTurn() throws Exception {
    AgentTurn existing = turn("client-turn-5", 1, "Implement OAuth login");
    existing.setIntentType("CODE_RUN_REQUIRED");
    when(turnRepo.findById("client-turn-5")).thenReturn(Optional.of(existing));
    OrchestratorTurnResponse decision =
        new OrchestratorTurnResponse(
            "client-turn-5",
            "CODE_RUN_REQUIRED",
            0.9,
            "explicit_code_mutation_request",
            true,
            "Create a cloud run?",
            new TypedAction(
                "create_cloud_run",
                Map.of("spec", "Implement OAuth login", "projectId", "p1"),
                true,
                "run_credit_on_plan_approval",
                "Create a cloud run?"),
            new OrchestratorPolicy(true, true, "Submit only after confirmation."),
            "orch_trace_existing");
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("client-turn-5"))
        .thenReturn(List.of(orchestratorDecisionEvent("client-turn-5", decision)));
    when(runSubmissionGate.submitConfirmed(
            any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
        .thenReturn(
            new AgentRunSubmissionGate.Submission("run-123", true, "SUBMITTED", false, false));

    var response =
        service.confirmRunSubmission(
            new OrchestratorRunConfirmationRequest(
                "s1", "client-turn-5", true, null, null, null, null),
            user());

    assertThat(response.runId()).isEqualTo("run-123");
    assertThat(response.calledRunApi()).isTrue();
    assertThat(existing.getLinkedRunId()).isEqualTo("run-123");
    assertThat(existing.getStatus()).isEqualTo("RUN_SUBMITTED");
    verify(runSubmissionGate)
        .submitConfirmed(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(false));
    verify(turnRepo).save(existing);

    var events = ArgumentCaptor.forClass(AgentActionEvent.class);
    org.mockito.Mockito.verify(eventRepo, org.mockito.Mockito.atLeastOnce()).save(events.capture());
    assertThat(events.getAllValues())
        .anySatisfy(
            event -> {
              assertThat(event.getType()).isEqualTo("ORCHESTRATOR_RUN_SUBMITTED");
              assertThat(event.getPayloadJson()).contains("calledRunApi");
              assertThat(event.getPayloadJson()).contains("true");
              assertThat(event.getPayloadJson()).contains("run-123");
              assertThat(event.getPayloadJson()).contains("promptVersion");
              assertThat(event.getPayloadJson()).contains("policyDecisions");
            });
  }

  @Test
  void confirmRunSubmissionReturnsLinkedRunWithoutDuplicateSubmission() {
    AgentTurn existing = turn("client-turn-6", 1, "Implement OAuth login");
    existing.setIntentType("CODE_RUN_REQUIRED");
    existing.setLinkedRunId("run-existing");
    when(turnRepo.findById("client-turn-6")).thenReturn(Optional.of(existing));

    var response =
        service.confirmRunSubmission(
            new OrchestratorRunConfirmationRequest(
                "s1", "client-turn-6", true, null, null, null, null),
            user());

    assertThat(response.runId()).isEqualTo("run-existing");
    assertThat(response.calledRunApi()).isFalse();
    assertThat(response.status()).isEqualTo("ALREADY_SUBMITTED");
    org.mockito.Mockito.verifyNoInteractions(runSubmissionGate);
  }

  @Test
  void disconnectStreamTurnCancelsOnlyStreamingTurn() {
    AgentTurn turn = turn("t1", 1, "hello");
    turn.setStatus("STREAMING");
    when(turnRepo.findById("t1")).thenReturn(Optional.of(turn));

    service.disconnectStreamTurn(
        new AgentSessionService.StreamTurnContext(
            "s1",
            "t1",
            1,
            "p1",
            null,
            "org-1",
            "u1",
            "DEEPSEEK",
            "deepseek-v4-flash-native",
            null));

    assertThat(turn.getStatus()).isEqualTo("CANCELLED");
    assertThat(turn.getAssistantSummary()).isEqualTo("Stream disconnected.");
    verify(turnRepo).save(turn);

    var saved = ArgumentCaptor.forClass(AgentActionEvent.class);
    org.mockito.Mockito.verify(eventRepo).save(saved.capture());
    assertThat(saved.getValue().getType()).isEqualTo("STREAM_DISCONNECTED");
  }

  @Test
  void disconnectStreamTurnDoesNotOverwriteCompletedTurn() {
    AgentTurn turn = turn("t1", 1, "hello");
    turn.setStatus("OK");
    turn.setAssistantSummary("final answer");
    when(turnRepo.findById("t1")).thenReturn(Optional.of(turn));

    service.disconnectStreamTurn(
        new AgentSessionService.StreamTurnContext(
            "s1",
            "t1",
            1,
            "p1",
            null,
            "org-1",
            "u1",
            "DEEPSEEK",
            "deepseek-v4-flash-native",
            null));

    assertThat(turn.getStatus()).isEqualTo("OK");
    assertThat(turn.getAssistantSummary()).isEqualTo("final answer");
    org.mockito.Mockito.verify(turnRepo, org.mockito.Mockito.never()).save(any(AgentTurn.class));
  }

  private static AgentSession session() {
    return AgentSession.builder()
        .id("s1")
        .orgId("org-1")
        .userId("u1")
        .projectId("p1")
        .clientSurface("CLI")
        .title("test")
        .build();
  }

  private static AgentTurn turn(String id, int sequence, String message) {
    return AgentTurn.builder()
        .id(id)
        .sessionId("s1")
        .orgId("org-1")
        .userId("u1")
        .sequence(sequence)
        .userMessage(message)
        .assistantSummary("reply-" + message)
        .intentType("CHAT")
        .intentConfidence(0.9)
        .status("OK")
        .build();
  }

  private static AgentActionEvent orchestratorDecisionEvent(
      String turnId, OrchestratorTurnResponse decision) throws Exception {
    return AgentActionEvent.builder()
        .id("evt-" + turnId)
        .sessionId("s1")
        .turnId(turnId)
        .type("ORCHESTRATOR_DECISION")
        .payloadJson(new ObjectMapper().writeValueAsString(Map.of("response", decision)))
        .build();
  }

  private static OrchestratorTurnRequest orchestratorRequest(
      String turnId, String message, String permissionMode) {
    return orchestratorRequest(turnId, message, permissionMode, true);
  }

  private static OrchestratorTurnRequest orchestratorRequest(
      String turnId, String message, String permissionMode, boolean supportsLocalHands) {
    return new OrchestratorTurnRequest(
        "s1",
        turnId,
        message,
        "cli",
        "0.4.2",
        "p1",
        "/Users/navneet/repo",
        new OrchestratorRepoContext(
            "/Users/navneet/repo",
            "main",
            false,
            List.of("src/Auth.java"),
            List.of(),
            "git@github.com:navneet/repo.git",
            "repo",
            List.of("java")),
        new OrchestratorClientCapabilities(
            supportsLocalHands, true, true, true, true, true, true, true),
        new OrchestratorMode(permissionMode, "auto", true),
        List.of());
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
