package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentEventRecorderTest {

  @Mock AgentActionEventRepository eventRepo;

  private AgentEventRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new AgentEventRecorder(eventRepo, new ObjectMapper());
    org.mockito.Mockito.lenient()
        .when(eventRepo.save(org.mockito.Mockito.any(AgentActionEvent.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void appendsSessionAndTurnScopedEvent() {
    AgentActionEvent event =
        recorder.append("s1", "t1", "ORCHESTRATOR_DECISION", Map.of("route", "PLAN_ONLY"));

    assertThat(event.getId()).isNotBlank();
    assertThat(event.getSessionId()).isEqualTo("s1");
    assertThat(event.getTurnId()).isEqualTo("t1");
    assertThat(event.getActionId()).isNull();
    assertThat(event.getType()).isEqualTo("ORCHESTRATOR_DECISION");
    assertThat(event.getPayloadJson()).contains("\"route\":\"PLAN_ONLY\"");
    assertThat(event.getCreatedAt()).isNotNull();
  }

  @Test
  void appendsActionScopedEvent() {
    AgentActionEvent event =
        recorder.appendAction("s1", "a1", "PERMISSION_REQUESTED", Map.of("tool", "shell"));

    assertThat(event.getSessionId()).isEqualTo("s1");
    assertThat(event.getTurnId()).isNull();
    assertThat(event.getActionId()).isEqualTo("a1");
    assertThat(event.getType()).isEqualTo("PERMISSION_REQUESTED");
    assertThat(event.getPayloadJson()).contains("\"tool\":\"shell\"");
  }

  // RR-888 — an unserializable payload must NOT be laundered into a valid-looking event row (which
  // would consume the unique terminal ACTION_RESULT slot). The recorder fails before any insert.
  @Test
  void unserializablePayloadThrowsAndDoesNotInsert() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                recorder.appendAction(
                    "s1", "a1", "ACTION_RESULT", Map.of("verification", new Object())))
        .isInstanceOf(AgentEventRecorder.PayloadSerializationException.class);
    org.mockito.Mockito.verify(eventRepo, org.mockito.Mockito.never())
        .save(org.mockito.Mockito.any(AgentActionEvent.class));
  }

  @Test
  void storesNullPayloadAsNullJson() {
    AgentActionEvent event = recorder.append("s1", "ACTION_EVENT", null);

    assertThat(event.getSessionId()).isEqualTo("s1");
    assertThat(event.getTurnId()).isNull();
    assertThat(event.getActionId()).isNull();
    assertThat(event.getType()).isEqualTo("ACTION_EVENT");
    assertThat(event.getPayloadJson()).isNull();
  }
}
