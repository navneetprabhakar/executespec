package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.TypedAction;
import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentTurnRecorderTest {

  @Mock AgentTurnRepository turnRepo;

  private AgentTurnRecorder recorder;

  @BeforeEach
  void setUp() {
    recorder = new AgentTurnRecorder(turnRepo, new ObjectMapper());
    when(turnRepo.save(org.mockito.Mockito.any(AgentTurn.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void recordsTurnWithNextSequenceAndProposedActionJson() {
    when(turnRepo.countBySessionId("s1")).thenReturn(4L);
    TypedAction action =
        new TypedAction(
            "create_cloud_run",
            Map.of("projectId", "p1", "spec", "Implement auth"),
            true,
            "run_credit_on_plan_approval",
            "Create a cloud run?");

    AgentTurn turn =
        recorder.record(
            session(),
            "client-turn-1",
            "Implement auth",
            "Create a cloud run?",
            "CODE_RUN_REQUIRED",
            0.92,
            action,
            "NEEDS_CONFIRMATION");

    assertThat(turn.getId()).isEqualTo("client-turn-1");
    assertThat(turn.getSequence()).isEqualTo(5);
    assertThat(turn.getSessionId()).isEqualTo("s1");
    assertThat(turn.getOrgId()).isEqualTo("org-1");
    assertThat(turn.getUserId()).isEqualTo("u1");
    assertThat(turn.getUserMessage()).isEqualTo("Implement auth");
    assertThat(turn.getAssistantSummary()).isEqualTo("Create a cloud run?");
    assertThat(turn.getIntentType()).isEqualTo("CODE_RUN_REQUIRED");
    assertThat(turn.getIntentConfidence()).isEqualTo(0.92);
    assertThat(turn.getActionType()).isEqualTo("create_cloud_run");
    assertThat(turn.getProposedActionJson()).contains("run_credit_on_plan_approval");
    assertThat(turn.getStatus()).isEqualTo("NEEDS_CONFIRMATION");
    assertThat(turn.getCreatedAt()).isNotNull();
  }

  @Test
  void generatesIdAndStoresNullMessageAsEmptyString() {
    when(turnRepo.countBySessionId("s1")).thenReturn(0L);

    AgentTurn turn =
        recorder.record(session(), null, null, null, "READ_ONLY_CODE_HELP", 0.8, null, "OK");

    assertThat(turn.getId()).isNotBlank();
    assertThat(turn.getSequence()).isEqualTo(1);
    assertThat(turn.getUserMessage()).isEqualTo("");
    assertThat(turn.getActionType()).isNull();
    assertThat(turn.getProposedActionJson()).isNull();

    var saved = ArgumentCaptor.forClass(AgentTurn.class);
    org.mockito.Mockito.verify(turnRepo).save(saved.capture());
    assertThat(saved.getValue()).isSameAs(turn);
  }

  private static AgentSession session() {
    return AgentSession.builder().id("s1").orgId("org-1").userId("u1").build();
  }
}
