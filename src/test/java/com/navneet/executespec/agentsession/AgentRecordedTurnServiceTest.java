package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorDecision;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TurnRequest;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRecordedTurnServiceTest {

  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentSessionRepository sessionRepo;
  @Mock com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  @Mock com.navneet.executespec.specchat.ChatSecurityAuditService chatSecurityAuditService;

  private AgentRecordedTurnService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    service =
        new AgentRecordedTurnService(
            new AgentIntentService(),
            new AgentTurnRecorder(turnRepo, objectMapper),
            new AgentEventRecorder(eventRepo, objectMapper),
            sessionRepo,
            projectAccessGuard,
            chatSecurityAuditService);
    when(turnRepo.save(any(AgentTurn.class))).thenAnswer(inv -> inv.getArgument(0));
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void recordsTurnPersistsIntentEventAndUpdatesDefaultTitle() {
    AgentSession session = session("New ExecuteSpec session");
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);

    var response =
        service.record(
            session,
            "s1",
            new TurnRequest(
                "Find the auth bug", null, new ClientCapabilities(false, true, true, true)),
            principal());

    assertThat(response.sessionId()).isEqualTo("s1");
    assertThat(response.status()).isEqualTo("OK");
    assertThat(response.decision().intent()).isEqualTo("CHAT");
    assertThat(session.getTitle()).isEqualTo("Find the auth bug");

    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getSequence()).isEqualTo(1);
    assertThat(turn.getValue().getUserMessage()).isEqualTo("Find the auth bug");
    assertThat(turn.getValue().getIntentType()).isEqualTo("READ_ONLY_CODE_HELP");

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("INTENT");
    assertThat(event.getValue().getTurnId()).isEqualTo(turn.getValue().getId());
    assertThat(event.getValue().getActionId()).isEqualTo(turn.getValue().getId());
    assertThat(event.getValue().getPayloadJson()).contains("READ_ONLY_CODE_HELP");
    verify(sessionRepo).save(session);
  }

  @Test
  void preservesExistingTitleAndHandlesNullRequest() {
    AgentSession session = session("Existing title");
    when(turnRepo.countBySessionId("s1")).thenReturn(4L);

    var response = service.record(session, "s1", null, principal());

    assertThat(response.sessionId()).isEqualTo("s1");
    assertThat(response.status()).isIn("OK", "NEEDS_CONFIRMATION");
    assertThat(session.getTitle()).isEqualTo("Existing title");

    var turn = ArgumentCaptor.forClass(AgentTurn.class);
    verify(turnRepo).save(turn.capture());
    assertThat(turn.getValue().getSequence()).isEqualTo(5);
    assertThat(turn.getValue().getUserMessage()).isEmpty();

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("INTENT");
    assertThat(event.getValue().getTurnId()).isEqualTo(turn.getValue().getId());
    assertThat(event.getValue().getActionId()).isEqualTo(turn.getValue().getId());
  }

  @Test
  void usesOneCanonicalOrchestratorDecisionWithoutSecondClassifierCall() {
    AgentIntentService intentService = org.mockito.Mockito.mock(AgentIntentService.class);
    when(intentService.decide(any(), any(), any()))
        .thenReturn(
            new OrchestratorDecision(
                "READ_ONLY_CODE_HELP",
                0.91,
                "structured_read",
                false,
                "I will inspect this without creating a run.",
                null,
                new OrchestratorPolicy(false, false, "read-only"),
                "trace-1"));
    service =
        new AgentRecordedTurnService(
            intentService,
            new AgentTurnRecorder(turnRepo, new ObjectMapper()),
            new AgentEventRecorder(eventRepo, new ObjectMapper()),
            sessionRepo,
            projectAccessGuard,
            chatSecurityAuditService);
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);

    var response =
        service.record(
            session("Existing title"),
            "s1",
            new TurnRequest("Explain the auth flow", null, null),
            principal());

    assertThat(response.decision().intent()).isEqualTo("CHAT");
    verify(intentService).decide(any(), any(), any());
    verify(intentService, never()).interpret(any());
  }

  @Test
  void aRecordedTurnWithoutAnActorIsRejectedRatherThanSilentlyUnauthorized() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.record(session("Existing title"), "s1", null, null))
        .isInstanceOf(SecurityException.class);
    verify(turnRepo, org.mockito.Mockito.never()).save(any(AgentTurn.class));

    // The same call with a real actor still succeeds, so the rejection is about the missing
    // principal rather than an unrelated failure.
    assertThat(service.record(session("Existing title"), "s1", null, principal())).isNotNull();
  }

  private static AgentSession session(String title) {
    return AgentSession.builder()
        .id("s1")
        .orgId("org-1")
        .userId("u1")
        .projectId("p1")
        .clientSurface("CLI")
        .title(title)
        .build();
  }

  private static com.navneet.executespec.auth.security.AppUserDetails principal() {
    var user = new com.navneet.executespec.auth.entity.AppUser();
    user.setId("u1");
    user.setOrgId("org-1");
    user.setGlobalRole("USER");
    user.setTier("PRO");
    user.setEmail("u1@example.com");
    return new com.navneet.executespec.auth.security.AppUserDetails(user, null);
  }
}
