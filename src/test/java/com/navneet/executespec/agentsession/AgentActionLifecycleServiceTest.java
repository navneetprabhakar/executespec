package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionEventRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionPlanRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ActionResultRequest;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.CapabilityTool;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.ClientCapabilities;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentActionLifecycleServiceTest {

  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentSessionRepository sessionRepo;

  private AgentActionLifecycleService service;

  @BeforeEach
  void setUp() {
    service =
        new AgentActionLifecycleService(
            new AgentIntentService(),
            new AgentEventRecorder(eventRepo, new ObjectMapper()),
            sessionRepo);
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void planRecordsActionPlannedEventAndReturnsCapabilities() {
    AgentSession session = session();
    List<CapabilityTool> capabilities =
        List.of(
            new CapabilityTool(
                "local_code_task",
                "Local code task",
                Map.of(),
                Map.of(),
                "PRO",
                List.of(),
                "LOCAL",
                true,
                "none",
                List.of("CLI")));

    var planned =
        service.plan(
            session,
            new ActionPlanRequest(
                "implement retries in the CLI",
                "p1",
                null,
                new ClientCapabilities(true, true, true, true),
                "VSCODE"),
            capabilities);

    assertThat(planned.sessionId()).isEqualTo("s1");
    assertThat(planned.actionId()).isNotBlank();
    assertThat(planned.decision().intent()).isEqualTo("LOCAL_CODE_TASK");
    assertThat(planned.capabilities()).isSameAs(capabilities);
    assertThat(planned.createdAt()).isNotNull();
    verify(sessionRepo).save(session);

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("ACTION_PLANNED");
    assertThat(event.getValue().getActionId()).isEqualTo(planned.actionId());
    assertThat(event.getValue().getPayloadJson()).contains(planned.actionId());
    assertThat(event.getValue().getPayloadJson()).contains("VSCODE");
    assertThat(event.getValue().getPayloadJson()).contains("orchestratorDecision");
  }

  @Test
  void recordEventDefaultsTypeAndPayload() {
    var response = service.recordEvent(session(), "a1", null);

    assertThat(response.actionId()).isEqualTo("a1");
    assertThat(response.sessionId()).isEqualTo("s1");
    assertThat(response.status()).isEqualTo("ACTION_EVENT");
    assertThat(response.updatedAt()).isNotNull();

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("ACTION_EVENT");
    assertThat(event.getValue().getActionId()).isEqualTo("a1");
    assertThat(event.getValue().getPayloadJson()).contains("a1");
    assertThat(event.getValue().getPayloadJson()).contains("\"payload\":{}");
  }

  @Test
  void recordEventPreservesExplicitTypeAndPayload() {
    service.recordEvent(
        session(), "a2", new ActionEventRequest("PERMISSION_PROMPT", Map.of("tool", "shell")));

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("PERMISSION_PROMPT");
    assertThat(event.getValue().getActionId()).isEqualTo("a2");
    assertThat(event.getValue().getPayloadJson()).contains("shell");
  }

  @Test
  void recordResultNormalizesStatusAndPersistsVerification() {
    var response =
        service.recordResult(
            session(),
            "a3",
            new ActionResultRequest(
                "failed",
                "missing expected artifact",
                "run-1",
                List.of("README.md"),
                Map.of("passed", false)));

    assertThat(response.status()).isEqualTo("FAILED");
    assertThat(response.summary()).isEqualTo("missing expected artifact");

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("ACTION_RESULT");
    assertThat(event.getValue().getActionId()).isEqualTo("a3");
    assertThat(event.getValue().getPayloadJson()).contains("FAILED");
    assertThat(event.getValue().getPayloadJson()).contains("README.md");
    assertThat(event.getValue().getPayloadJson()).contains("passed");
  }

  @Test
  void recordResultHandlesNullRequest() {
    var response = service.recordResult(session(), "a4", null);

    assertThat(response.status()).isEqualTo("UNKNOWN");
    assertThat(response.summary()).isNull();

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getActionId()).isEqualTo("a4");
    assertThat(event.getValue().getPayloadJson()).contains("\"status\":\"UNKNOWN\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"touchedFiles\":[]");
    assertThat(event.getValue().getPayloadJson()).contains("\"verification\":{}");
  }

  @Test
  void recordResultHandlesMissingRunIdAndBlankSummary() {
    var response =
        service.recordResult(
            session(),
            "a5",
            new ActionResultRequest("completed", "", null, null, Map.of("skipped", true)));

    assertThat(response.status()).isEqualTo("COMPLETED");
    assertThat(response.summary()).isEmpty();

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getActionId()).isEqualTo("a5");
    assertThat(event.getValue().getPayloadJson()).contains("\"runId\":\"\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"summary\":\"\"");
    assertThat(event.getValue().getPayloadJson()).contains("skipped");
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
}
