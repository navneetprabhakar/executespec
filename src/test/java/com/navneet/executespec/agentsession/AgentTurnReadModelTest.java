package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorPolicy;
import com.navneet.executespec.agentsession.dto.AgentSessionDtos.OrchestratorTurnResponse;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentTurnReadModelTest {

  @Mock AgentActionEventRepository eventRepo;

  @Test
  void hydratesTurnResponseWithStoredOrchestratorTrace() throws Exception {
    var decision =
        new OrchestratorTurnResponse(
            "t1",
            "PLAN_ONLY",
            0.91,
            "plan_or_how_would_request",
            false,
            "I will write the plan without creating a run.",
            null,
            new OrchestratorPolicy(false, false, "Planning request."),
            "orch_trace_1");
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t1"))
        .thenReturn(List.of(event("t1", "ORCHESTRATOR_DECISION", decision)));

    var readModel = new AgentTurnReadModel(eventRepo, new ObjectMapper());
    var response = readModel.toResponse(turn("t1", "CHAT"));

    assertThat(response.route()).isEqualTo("PLAN_ONLY");
    assertThat(response.reasonCode()).isEqualTo("plan_or_how_would_request");
    assertThat(response.intentType()).isEqualTo("CHAT");
  }

  @Test
  void replayFallsBackToStoredTurnWhenTracePayloadIsMissing() {
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t2")).thenReturn(List.of());

    var readModel = new AgentTurnReadModel(eventRepo, new ObjectMapper());
    AgentTurn turn = turn("t2", "READ_ONLY_CODE_HELP");
    turn.setStatus("NEEDS_CONFIRMATION");

    var response = readModel.replayOrchestratorTurn(turn);

    assertThat(response.route()).isEqualTo("READ_ONLY_CODE_HELP");
    assertThat(response.reasonCode()).isEqualTo("idempotent_replay_without_trace_payload");
    assertThat(response.requiresUserConfirmation()).isTrue();
    assertThat(response.policy().runApiAllowed()).isFalse();
  }

  @Test
  void ignoresMalformedTracePayloads() {
    when(eventRepo.findByTurnIdOrderByCreatedAtAsc("t3"))
        .thenReturn(
            List.of(
                AgentActionEvent.builder()
                    .id("evt-t3")
                    .sessionId("s1")
                    .turnId("t3")
                    .type("ORCHESTRATOR_DECISION")
                    .payloadJson("{not-json")
                    .build()));

    var readModel = new AgentTurnReadModel(eventRepo, new ObjectMapper());

    assertThat(readModel.storedOrchestratorResponse("t3")).isEmpty();
  }

  private static AgentActionEvent event(
      String turnId, String type, OrchestratorTurnResponse response) throws Exception {
    return AgentActionEvent.builder()
        .id("evt-" + turnId)
        .sessionId("s1")
        .turnId(turnId)
        .type(type)
        .payloadJson(new ObjectMapper().writeValueAsString(Map.of("response", response)))
        .build();
  }

  private static AgentTurn turn(String id, String intentType) {
    return AgentTurn.builder()
        .id(id)
        .sessionId("s1")
        .orgId("org-1")
        .userId("u1")
        .sequence(1)
        .userMessage("hello")
        .assistantSummary("reply")
        .intentType(intentType)
        .intentConfidence(0.8)
        .status("OK")
        .build();
  }
}
