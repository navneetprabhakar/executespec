package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorMode;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorRepoContext;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
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
class AgentOrchestratorTurnServiceTest {

  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentSessionRepository sessionRepo;
  @Mock ProjectAccessGuard projectAccessGuard;

  private AgentOrchestratorTurnService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    AgentTurnReadModel turnReadModel = new AgentTurnReadModel(eventRepo, objectMapper);
    AgentTurnRecorder turnRecorder = new AgentTurnRecorder(turnRepo, objectMapper);
    AgentEventRecorder eventRecorder = new AgentEventRecorder(eventRepo, objectMapper);
    service =
        new AgentOrchestratorTurnService(
            turnRepo,
            turnReadModel,
            new AgentIntentService(),
            turnRecorder,
            eventRecorder,
            new AgentTracePayloadFactory(),
            sessionRepo,
            projectAccessGuard);
    lenient().when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));
    lenient()
        .when(eventRepo.save(any(AgentActionEvent.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void orchestratesNewTurnAndPersistsTrace() {
    AgentSession session = session();
    when(turnRepo.findById("client-turn-1")).thenReturn(Optional.empty());
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);

    var response =
        service.orchestrate(
            session,
            orchestratorRequest("client-turn-1", "Explain this repo", "auto", true),
            user());

    assertThat(response.turnId()).isEqualTo("client-turn-1");
    assertThat(response.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(response.policy().runApiAllowed()).isFalse();
    assertThat(response.traceId()).startsWith("orch_trace_");
    assertThat(session.getTitle()).isEqualTo("Explain this repo");

    verify(projectAccessGuard).assertCanRead(eq("p1"), any(AppUserDetails.class));
    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getId()).isEqualTo("client-turn-1");
    assertThat(turn.getValue().getIntentType()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(turn.getValue().getStatus()).isEqualTo("OK");

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("ORCHESTRATOR_DECISION");
    assertThat(event.getValue().getPayloadJson()).contains("calledRunApi");
    assertThat(event.getValue().getPayloadJson()).contains("false");
    assertThat(event.getValue().getPayloadJson()).contains("promptVersion");
    assertThat(event.getValue().getPayloadJson()).contains("policyDecisions");
    verify(sessionRepo).save(session);
  }

  @Test
  void readOnlyModeDowngradesRunAllowedDecision() {
    when(turnRepo.findById("client-turn-2")).thenReturn(Optional.empty());
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);

    var response =
        service.orchestrate(
            session(),
            orchestratorRequest("client-turn-2", "implement OAuth login", "read_only", false),
            user());

    assertThat(response.route()).isEqualTo("IMPLEMENTATION_PROPOSAL");
    assertThat(response.requiresUserConfirmation()).isTrue();
    assertThat(response.proposedAction().type()).isEqualTo("create_cloud_run");
    assertThat(response.policy().runApiAllowed()).isFalse();
    assertThat(response.reasonCode()).isEqualTo("read_only_permission_blocks_run");
  }

  @Test
  void nonExecutionPromptsPersistNoRunDecisionsAtOrchestratorBoundary() {
    when(turnRepo.findById(any())).thenReturn(Optional.empty());
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);

    List<NoRunCase> cases =
        List.of(
            new NoRunCase("Explain this repo", "READ_ONLY_CODE_HELP", true),
            new NoRunCase("Review this code and list findings", "REVIEW_ONLY", true),
            new NoRunCase("Write a plan to add OAuth", "PLAN_ONLY", true),
            new NoRunCase("Find the auth bug", "READ_ONLY_CODE_HELP", true),
            new NoRunCase("commit all files and push to git", "LOCAL_TOOL_ACTION", true),
            new NoRunCase("delete everything", "SECURITY_REJECT", false));

    for (int i = 0; i < cases.size(); i++) {
      NoRunCase item = cases.get(i);
      var response =
          service.orchestrate(
              session(),
              orchestratorRequest("no-run-" + i, item.message(), "auto", item.supportsLocalHands()),
              user());

      assertThat(response.route()).as(item.message()).isEqualTo(item.route());
      assertThat(response.policy().runApiAllowed()).as(item.message()).isFalse();
    }
  }

  private record NoRunCase(String message, String route, boolean supportsLocalHands) {}

  @Test
  void replaysExistingTurnDecisionForSameSessionAndUser() throws Exception {
    AgentTurn existing = turn("client-turn-3", "s1", "u1");
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
        .thenReturn(List.of(orchestratorDecisionEvent("client-turn-3", original)));

    var response =
        service.orchestrate(
            session(),
            orchestratorRequest("client-turn-3", "Explain this repo", "auto", true),
            user());

    assertThat(response.traceId()).isEqualTo("orch_trace_existing");
    assertThat(response.route()).isEqualTo("READ_ONLY_CODE_HELP");
    verify(turnRepo, never()).save(any(AgentTurn.class));
    verify(eventRepo, never()).save(any(AgentActionEvent.class));
    verify(sessionRepo, never()).save(any(AgentSession.class));
  }

  @Test
  void rejectsTurnIdOwnedByAnotherSession() {
    when(turnRepo.findById("client-turn-4"))
        .thenReturn(Optional.of(turn("client-turn-4", "other-session", "u1")));

    assertThatThrownBy(
            () ->
                service.orchestrate(
                    session(),
                    orchestratorRequest("client-turn-4", "Explain this repo", "auto", true),
                    user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("turnId already belongs elsewhere");

    verify(turnRepo, never()).save(any(AgentTurn.class));
    verify(eventRepo, never()).save(any(AgentActionEvent.class));
  }

  private static AgentSession session() {
    return AgentSession.builder()
        .id("s1")
        .orgId("org-1")
        .userId("u1")
        .projectId("p1")
        .clientSurface("CLI")
        .title("New ExecuteSpec session")
        .build();
  }

  private static AgentTurn turn(String id, String sessionId, String userId) {
    return AgentTurn.builder()
        .id(id)
        .sessionId(sessionId)
        .orgId("org-1")
        .userId(userId)
        .sequence(1)
        .userMessage("Explain this repo")
        .assistantSummary("I will inspect or explain this without creating a run.")
        .intentType("READ_ONLY_CODE_HELP")
        .intentConfidence(0.9)
        .status("OK")
        .build();
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
