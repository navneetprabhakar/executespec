package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.sse.RunEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentRunEventBridgeTest {

  @Mock AgentSessionRepository sessionRepo;
  @Mock AgentActionEventRepository eventRepo;

  private AgentRunEventBridge bridge;

  @BeforeEach
  void setUp() {
    bridge =
        new AgentRunEventBridge(sessionRepo, new AgentEventRecorder(eventRepo, new ObjectMapper()));
  }

  @Test
  void bridgesGoalDecisionIntoRunScopedAgentSession() {
    AgentSession session = session("s1", "run-1");
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc("run-1", "ACTIVE"))
        .thenReturn(List.of(session));
    when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));

    bridge.onRunEvent(RunEvent.goalDecision("run-1", "T1", "Goal DONE", Map.of("state", "DONE")));

    ArgumentCaptor<AgentActionEvent> event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getSessionId()).isEqualTo("s1");
    assertThat(event.getValue().getType()).isEqualTo("RUN_GOAL_DECISION");
    assertThat(event.getValue().getPayloadJson()).contains("\"runId\":\"run-1\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"taskId\":\"T1\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"runEventType\":\"GOAL_DECISION\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"state\":\"DONE\"");
    verify(sessionRepo).save(session);
  }

  @Test
  void bridgesContinuationActionSkippedWithLimitData() {
    AgentSession session = session("s1", "run-1");
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc("run-1", "ACTIVE"))
        .thenReturn(List.of(session));
    when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));

    bridge.onRunEvent(
        RunEvent.continuationActionSkipped(
            "run-1",
            "T1",
            "skipped",
            "STEP_UP_MODEL",
            "openai",
            "gpt-5.4",
            "limit reached",
            "maxModelStepUps",
            1,
            1));

    ArgumentCaptor<AgentActionEvent> event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("RUN_CONTINUATION_ACTION_SKIPPED");
    assertThat(event.getValue().getPayloadJson()).contains("\"limitName\":\"maxModelStepUps\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"selectedModel\":\"gpt-5.4\"");
  }

  @Test
  void bridgesTaskWavePlanIntoRunScopedAgentSession() {
    AgentSession session = session("s1", "run-1");
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc("run-1", "ACTIVE"))
        .thenReturn(List.of(session));
    when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));

    bridge.onRunEvent(
        RunEvent.taskWavePlan(
            "run-1",
            "Task wave plan: 2 bounded waves",
            Map.of(
                "executionMode",
                "SEQUENTIAL_ADVISORY",
                "maxParallelTasks",
                2,
                "waves",
                List.of(List.of("T1", "T2"), List.of("T3")))));

    ArgumentCaptor<AgentActionEvent> event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getSessionId()).isEqualTo("s1");
    assertThat(event.getValue().getType()).isEqualTo("RUN_TASK_WAVE_PLAN");
    assertThat(event.getValue().getPayloadJson()).contains("\"runEventType\":\"TASK_WAVE_PLAN\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"summary\":\"Task wave plan");
    assertThat(event.getValue().getPayloadJson())
        .contains("\"executionMode\":\"SEQUENTIAL_ADVISORY\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"maxParallelTasks\":2");
    verify(sessionRepo).save(session);
  }

  @Test
  void ignoresUnmappedRunEventsAndEventsWithoutRunId() {
    bridge.onRunEvent(RunEvent.log("run-1", "T1", "plain log"));
    bridge.onRunEvent(RunEvent.goalDecision("", "T1", "Goal DONE", Map.of()));

    verify(sessionRepo, never()).findByRunIdAndStatusOrderByUpdatedAtDesc(any(), any());
    verify(eventRepo, never()).save(any());
  }

  private static AgentSession session(String id, String runId) {
    return AgentSession.builder()
        .id(id)
        .orgId("org-1")
        .userId("u1")
        .runId(runId)
        .clientSurface("WEB")
        .title("Run session")
        .status("ACTIVE")
        .build();
  }
}
