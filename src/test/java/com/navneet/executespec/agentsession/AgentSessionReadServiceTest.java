package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AgentSessionReadServiceTest {

  @Mock AgentSessionRepository sessionRepo;
  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock PipelineRunRepository pipelineRunRepo;

  private AgentSessionReadService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentSessionReadService(
            sessionRepo,
            turnRepo,
            eventRepo,
            new AgentTurnReadModel(eventRepo, new ObjectMapper()),
            pipelineRunRepo);
  }

  @Test
  void listsActiveSessionsWithCappedLimit() {
    when(sessionRepo.findByUserIdAndStatusOrderByUpdatedAtDesc(
            eq("u1"), eq("ACTIVE"), any(Pageable.class)))
        .thenReturn(List.of(session("s1", "u1")));
    when(turnRepo.findLatestLinkedRunTurnsBySessionIds(List.of("s1"))).thenReturn(List.of());

    var sessions = service.listSessions(user(), 500);

    assertThat(sessions).hasSize(1);
    assertThat(sessions.get(0).id()).isEqualTo("s1");
    var pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(sessionRepo)
        .findByUserIdAndStatusOrderByUpdatedAtDesc(eq("u1"), eq("ACTIVE"), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  void listsLatestLinkedRunSummaryForSessionRows() {
    when(sessionRepo.findByUserIdAndStatusOrderByUpdatedAtDesc(
            eq("u1"), eq("ACTIVE"), any(Pageable.class)))
        .thenReturn(List.of(session("s1", "u1")));
    when(turnRepo.findLatestLinkedRunTurnsBySessionIds(List.of("s1")))
        .thenReturn(List.of(linkedTurn("t2", 2, "submitted", "run-2")));
    when(pipelineRunRepo.findAllById(List.of("run-2")))
        .thenReturn(
            List.of(
                PipelineRun.builder()
                    .runId("run-2")
                    .runTitle("Add audit logs")
                    .status("AWAITING_APPROVAL")
                    .startedAt(java.time.Instant.parse("2026-06-20T00:00:00Z"))
                    .scmPrUrl("https://github.com/acme/repo/pull/2")
                    .build()));

    var sessions = service.listSessions(user(), 25);

    assertThat(sessions).hasSize(1);
    assertThat(sessions.get(0).latestLinkedRunId()).isEqualTo("run-2");
    assertThat(sessions.get(0).latestLinkedRunStatus()).isEqualTo("AWAITING_APPROVAL");
    assertThat(sessions.get(0).latestLinkedRunTitle()).isEqualTo("Add audit logs");
    assertThat(sessions.get(0).latestLinkedRunPrUrl())
        .isEqualTo("https://github.com/acme/repo/pull/2");
  }

  @Test
  void requireSessionRejectsMissingOrDifferentUser() {
    when(sessionRepo.findById("missing")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.requireSession("missing", user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("session not found");

    when(sessionRepo.findById("other")).thenReturn(Optional.of(session("other", "other-user")));
    assertThatThrownBy(() -> service.requireSession("other", user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("forbidden");
  }

  @Test
  void listsEventsAfterSessionOwnershipCheck() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdOrderByCreatedAtAsc("s1"))
        .thenReturn(List.of(AgentActionEvent.builder().id("evt-1").sessionId("s1").build()));

    var events = service.listEvents("s1", user());

    assertThat(events).extracting("id").containsExactly("evt-1");
  }

  @Test
  void listsBoundedTurnsBeforeCursorWithStoredTraceHydration() throws Exception {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(turnRepo.findBySessionIdOrderBySequenceAsc("s1"))
        .thenReturn(
            List.of(
                turn("t1", 1, "first"),
                turn("t2", 2, "second"),
                turn("t3", 3, "third"),
                turn("t4", 4, "fourth")));
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t2"))
        .thenReturn(List.of(orchestratorDecisionEvent("t2", "PLAN_ONLY")));
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t3"))
        .thenReturn(List.of(orchestratorDecisionEvent("t3", "READ_ONLY_CODE_HELP")));

    var turns = service.listTurns("s1", "t4", 2, user());

    assertThat(turns).extracting("turnId").containsExactly("t2", "t3");
    assertThat(turns.get(0).route()).isEqualTo("PLAN_ONLY");
    assertThat(turns.get(1).route()).isEqualTo("READ_ONLY_CODE_HELP");
  }

  private static AgentSession session(String id, String userId) {
    return AgentSession.builder()
        .id(id)
        .orgId("org-1")
        .userId(userId)
        .clientSurface("CLI")
        .title("Session")
        .status("ACTIVE")
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

  private static AgentTurn linkedTurn(String id, int sequence, String message, String runId) {
    AgentTurn turn = turn(id, sequence, message);
    turn.setLinkedRunId(runId);
    return turn;
  }

  private static AgentActionEvent orchestratorDecisionEvent(String turnId, String route)
      throws Exception {
    var response =
        new OrchestratorTurnResponse(
            turnId,
            route,
            0.9,
            "reason",
            false,
            "reply",
            null,
            new OrchestratorPolicy(false, false, "No run."),
            "orch_trace_" + turnId);
    return AgentActionEvent.builder()
        .id("evt-" + turnId)
        .sessionId("s1")
        .turnId(turnId)
        .type("ORCHESTRATOR_DECISION")
        .payloadJson(new ObjectMapper().writeValueAsString(java.util.Map.of("response", response)))
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
