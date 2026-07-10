package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalHandsStateServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private AgentSessionRepository sessionRepo;
  private AgentActionEventRepository eventRepo;
  private AgentEventRecorder recorder;
  private LocalHandsStateService service;

  @BeforeEach
  void setUp() {
    sessionRepo = mock(AgentSessionRepository.class);
    eventRepo = mock(AgentActionEventRepository.class);
    recorder = mock(AgentEventRecorder.class);
    service = new LocalHandsStateService(sessionRepo, eventRepo, recorder, objectMapper);
    when(sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc("run-1", "ACTIVE"))
        .thenReturn(List.of(AgentSession.builder().id("session-1").build()));
  }

  @Test
  void record_appendsExplicitStateToAgentSessionActionStream() {
    service.record(
        "run-1",
        "call-1",
        LocalHandsState.AWAITING_CLIENT,
        "task-1",
        "readFile",
        "read",
        "{\"callId\":\"call-1\"}");

    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(recorder)
        .appendAction(
            eq("session-1"), eq("call-1"), eq("LOCAL_HANDS_AWAITING_CLIENT"), payload.capture());
    assertThat(objectMapper.valueToTree(payload.getValue()).path("state").asText())
        .isEqualTo("AWAITING_CLIENT");
    assertThat(objectMapper.valueToTree(payload.getValue()).path("frame").path("callId").asText())
        .isEqualTo("call-1");
  }

  @Test
  void pending_reconstructsOnlyCallsWhoseLatestStateNeedsClient() {
    when(eventRepo.findBySessionIdOrderByCreatedAtAsc("session-1"))
        .thenReturn(
            List.of(
                event("call-1", LocalHandsState.AWAITING_CLIENT, 1),
                event("call-2", LocalHandsState.AWAITING_CLIENT, 2),
                event("call-2", LocalHandsState.COMPLETED, 3),
                event("call-3", LocalHandsState.CLIENT_RECONNECTED, 4)));

    assertThat(service.pending("run-1"))
        .extracting(LocalHandsStateService.PendingCall::callId)
        .containsExactly("call-1", "call-3");
  }

  @Test
  void persistenceFailure_isFailSoftForWorkerPath() {
    when(sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc("run-1", "ACTIVE"))
        .thenThrow(new IllegalStateException("database unavailable"));

    service.record("run-1", "call-1", LocalHandsState.CLIENT_TIMEOUT, null, "shell", "shell", "{}");

    verify(recorder, org.mockito.Mockito.never()).appendAction(any(), any(), any(), any());
  }

  private AgentActionEvent event(String callId, LocalHandsState state, int second) {
    String payload =
        "{\"state\":\""
            + state.name()
            + "\",\"runId\":\"run-1\",\"callId\":\""
            + callId
            + "\",\"toolName\":\"readFile\",\"permissionKind\":\"read\","
            + "\"frame\":{\"callId\":\""
            + callId
            + "\"}}";
    return AgentActionEvent.builder()
        .id("event-" + callId + "-" + second)
        .sessionId("session-1")
        .actionId(callId)
        .type("LOCAL_HANDS_" + state.name())
        .payloadJson(payload)
        .createdAt(Instant.parse("2026-07-10T00:00:0" + second + "Z"))
        .build();
  }
}
