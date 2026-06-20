package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
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

  private AgentRecordedTurnService service;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    service =
        new AgentRecordedTurnService(
            new AgentIntentService(),
            new AgentTurnRecorder(turnRepo, objectMapper),
            new AgentEventRecorder(eventRepo, objectMapper),
            sessionRepo);
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
                "Find the auth bug", null, new ClientCapabilities(false, true, true, true)));

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
    assertThat(event.getValue().getPayloadJson()).contains("READ_ONLY_CODE_HELP");
    verify(sessionRepo).save(session);
  }

  @Test
  void preservesExistingTitleAndHandlesNullRequest() {
    AgentSession session = session("Existing title");
    when(turnRepo.countBySessionId("s1")).thenReturn(4L);

    var response = service.record(session, "s1", null);

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
}
