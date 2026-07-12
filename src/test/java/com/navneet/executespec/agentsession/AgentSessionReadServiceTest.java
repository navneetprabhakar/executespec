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
  void requireSessionRejectsSameUserFromDifferentOrg() {
    AgentSession otherOrg = session("other-org-session", "u1");
    otherOrg.setOrgId("org-other");
    when(sessionRepo.findById("other-org-session")).thenReturn(Optional.of(otherOrg));

    assertThatThrownBy(() -> service.requireSession("other-org-session", user()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("forbidden");
  }

  @Test
  void listsEventsAfterSessionOwnershipCheck() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdOrderByCreatedAtAsc(eq("s1"), any(Pageable.class)))
        .thenReturn(List.of(AgentActionEvent.builder().id("evt-1").sessionId("s1").build()));

    var events = service.listEvents("s1", user());

    assertThat(events).extracting("id").containsExactly("evt-1");
    var page = ArgumentCaptor.forClass(Pageable.class);
    verify(eventRepo).findBySessionIdOrderByCreatedAtAsc(eq("s1"), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(500);
  }

  @Test
  void replaysOnlyEventsAfterCursorAndReturnsLastSequence() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc("s1", 4))
        .thenReturn(
            List.of(
                AgentActionEvent.builder()
                    .id("evt-5")
                    .sessionId("s1")
                    .streamSequence(5L)
                    .type("STREAM_COMPLETED")
                    .build()));

    var replay = service.replayEvents("s1", 4L, user());

    assertThat(replay.snapshot()).isFalse();
    assertThat(replay.lastSequence()).isEqualTo(5L);
    assertThat(replay.events()).extracting("id").containsExactly("evt-5");
  }

  @Test
  void replayPreservesSemanticPostSseFramesForBrowserReconnect() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc("s1", 4))
        .thenReturn(
            List.of(
                semanticEvent("route", 5L, "ROUTE_DECISION"),
                semanticEvent("tool-call", 6L, "TOOL_CALL"),
                semanticEvent("tool-result", 7L, "TOOL_RESULT"),
                semanticEvent("text", 8L, "TURN_TEXT"),
                semanticEvent("done", 9L, "TURN_DONE")));

    var replay = service.replayEvents("s1", 4L, user());

    assertThat(replay.events())
        .extracting(AgentActionEvent::getType)
        .containsExactly("ROUTE_DECISION", "TOOL_CALL", "TOOL_RESULT", "TURN_TEXT", "TURN_DONE");
    assertThat(replay.lastSequence()).isEqualTo(9L);
    assertThat(replay.snapshot()).isFalse();
  }

  private static AgentActionEvent semanticEvent(String id, long sequence, String type) {
    return AgentActionEvent.builder()
        .id(id)
        .sessionId("s1")
        .turnId("t1")
        .streamSequence(sequence)
        .type(type)
        .build();
  }

  @Test
  void replayToleratesLegacyEventWithoutSequenceWithoutAdvancingCursor() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdOrderByStreamSequenceAsc(eq("s1"), any(Pageable.class)))
        .thenReturn(
            List.of(
                AgentActionEvent.builder()
                    .id("legacy-event")
                    .sessionId("s1")
                    .type("TEXT")
                    .streamSequence(null)
                    .build()));

    var replay = service.replayEvents("s1", null, user());

    assertThat(replay.events()).extracting("id").containsExactly("legacy-event");
    assertThat(replay.lastSequence()).isZero();
    assertThat(replay.snapshot()).isTrue();
    assertThat(replay.session().id()).isEqualTo("s1");
    assertThat(replay.session().status()).isEqualTo("ACTIVE");
  }

  @Test
  void initialSnapshotReportsTruncationWhenHistoryExceedsPage() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdOrderByStreamSequenceAsc(eq("s1"), any(Pageable.class)))
        .thenReturn(List.of(AgentActionEvent.builder().id("first").build()));
    when(eventRepo.countBySessionId("s1")).thenReturn(501L);

    var replay = service.replayEvents("s1", null, user());

    assertThat(replay.snapshot()).isTrue();
    assertThat(replay.truncated()).isTrue();
    assertThat(replay.hasMore()).isTrue();
  }

  @Test
  void replayRejectsStaleRowsAndNeverRegressesCursor() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc("s1", 10))
        .thenReturn(
            List.of(
                AgentActionEvent.builder().id("stale").streamSequence(9L).build(),
                AgentActionEvent.builder().id("fresh").streamSequence(12L).build(),
                AgentActionEvent.builder().id("legacy").streamSequence(null).build()));
    when(eventRepo.countBySessionIdAndStreamSequenceIsNull("s1")).thenReturn(1L);
    when(eventRepo.findBySessionIdOrderByStreamSequenceAsc(eq("s1"), any(Pageable.class)))
        .thenReturn(
            List.of(
                AgentActionEvent.builder().id("legacy").streamSequence(null).build(),
                AgentActionEvent.builder().id("fresh").streamSequence(12L).build()));
    when(eventRepo.countBySessionId("s1")).thenReturn(2L);

    var replay = service.replayEvents("s1", 10L, user());

    assertThat(replay.events()).extracting("id").containsExactly("legacy", "fresh");
    assertThat(replay.lastSequence()).isEqualTo(12L);
    assertThat(replay.snapshot()).isTrue();
    assertThat(replay.legacyEventsOmitted()).isFalse();
  }

  @Test
  void legacyReplayMarksTruncatedSnapshotWhenHistoryExceedsSafePage() {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(eventRepo.findBySessionIdAndStreamSequenceGreaterThanOrderByStreamSequenceAsc("s1", 10))
        .thenReturn(List.of());
    when(eventRepo.countBySessionIdAndStreamSequenceIsNull("s1")).thenReturn(1L);
    when(eventRepo.countBySessionId("s1")).thenReturn(501L);
    when(eventRepo.findBySessionIdOrderByStreamSequenceAsc(eq("s1"), any(Pageable.class)))
        .thenReturn(List.of(AgentActionEvent.builder().id("legacy").build()));

    var replay = service.replayEvents("s1", 10L, user());

    assertThat(replay.snapshot()).isTrue();
    assertThat(replay.truncated()).isTrue();
    assertThat(replay.hasMore()).isTrue();
    var page = ArgumentCaptor.forClass(Pageable.class);
    verify(eventRepo).findBySessionIdOrderByStreamSequenceAsc(eq("s1"), page.capture());
    assertThat(page.getValue().getPageSize()).isEqualTo(500);
  }

  @Test
  void listsBoundedTurnsBeforeCursorWithStoredTraceHydration() throws Exception {
    when(sessionRepo.findById("s1")).thenReturn(Optional.of(session("s1", "u1")));
    when(turnRepo.findById("t4")).thenReturn(Optional.of(turn("t4", 4, "fourth")));
    when(turnRepo.findBySessionIdAndSequenceLessThanOrderBySequenceDesc(
            eq("s1"), eq(4), any(Pageable.class)))
        .thenReturn(List.of(turn("t3", 3, "third"), turn("t2", 2, "second")));
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
