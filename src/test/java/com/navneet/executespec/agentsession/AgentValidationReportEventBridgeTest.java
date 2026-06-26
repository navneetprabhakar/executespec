package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.report.ValidationReportEvidenceEvent;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.model.VerificationEvidenceDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentValidationReportEventBridgeTest {

  @Mock AgentSessionRepository sessionRepo;
  @Mock AgentActionEventRepository eventRepo;

  private AgentValidationReportEventBridge bridge;

  @BeforeEach
  void setUp() {
    bridge =
        new AgentValidationReportEventBridge(
            sessionRepo, new AgentEventRecorder(eventRepo, new ObjectMapper()));
  }

  @Test
  void bridgesValidationEvidenceIntoRunScopedAgentSession() {
    AgentSession session = session("s1", "run-1");
    when(eventRepo.save(any(AgentActionEvent.class))).thenAnswer(inv -> inv.getArgument(0));
    when(sessionRepo.findByRunIdAndStatusOrderByUpdatedAtDesc("run-1", "ACTIVE"))
        .thenReturn(List.of(session));
    when(sessionRepo.save(any(AgentSession.class))).thenAnswer(inv -> inv.getArgument(0));

    bridge.onValidationReportEvidence(
        new ValidationReportEvidenceEvent(
            "run-1",
            "vr-1",
            87,
            91,
            PipelineResult.PASSED,
            Instant.parse("2026-06-26T00:00:00Z"),
            List.of(
                new VerificationEvidenceDTO(
                    "build",
                    "Build",
                    "BUILD",
                    "PASS",
                    "DETERMINISTIC",
                    "HIGH",
                    "Build passed",
                    "Final build passed.",
                    "validation_report.final_build_passed",
                    1,
                    1))));

    ArgumentCaptor<AgentActionEvent> event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getSessionId()).isEqualTo("s1");
    assertThat(event.getValue().getType()).isEqualTo("RUN_VALIDATION_EVIDENCE");
    assertThat(event.getValue().getPayloadJson()).contains("\"runId\":\"run-1\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"reportId\":\"vr-1\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"verifiedScore\":87");
    assertThat(event.getValue().getPayloadJson()).contains("\"id\":\"build\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"source\":\"DETERMINISTIC\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"runGoalDecision\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"state\":\"UNCERTAIN\"");
    assertThat(event.getValue().getPayloadJson()).contains("\"recommendedNextStep\"");
    assertThat(event.getValue().getPayloadJson()).contains("Continue with the same model/provider");
    assertThat(event.getValue().getPayloadJson())
        .contains("\"spec adherence evidence unavailable\"");
    verify(sessionRepo).save(session);
  }

  @Test
  void ignoresEventsWithoutRunId() {
    bridge.onValidationReportEvidence(
        new ValidationReportEvidenceEvent(null, "vr-1", 87, null, null, null, List.of()));

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
