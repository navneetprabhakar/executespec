package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentActionEvent;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.entity.AgentUsageLedger;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.repository.AgentUsageLedgerRepository;
import com.navneet.executespec.cost.RunCostTracker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentStreamTurnLifecycleTest {

  @Mock AgentTurnRepository turnRepo;
  @Mock AgentActionEventRepository eventRepo;
  @Mock AgentUsageLedgerRepository usageRepo;

  @Test
  void completeUpdatesTurnPersistsUsageAndCompletionEvent() {
    AgentTurn turn = turn("t1", "STREAMING");
    when(turnRepo.findById("t1")).thenReturn(Optional.of(turn));
    var cost = new RunCostTracker.Snapshot(10, 4, 3, 1, 0.012, 1, 2);

    lifecycle().complete(ctx("t1"), "final answer", cost, "OK");

    assertThat(turn.getAssistantSummary()).isEqualTo("final answer");
    assertThat(turn.getInputTokens()).isEqualTo(10);
    assertThat(turn.getOutputTokens()).isEqualTo(4);
    assertThat(turn.getReasoningTokens()).isEqualTo(2);
    assertThat(turn.getCostUsd()).isEqualTo(0.012);
    assertThat(turn.getStatus()).isEqualTo("OK");
    verify(turnRepo).save(turn);

    var usage = ArgumentCaptor.forClass(AgentUsageLedger.class);
    verify(usageRepo).save(usage.capture());
    assertThat(usage.getValue().getSourceId()).isEqualTo("t1");
    assertThat(usage.getValue().getProvider()).isEqualTo("DEEPSEEK");
    assertThat(usage.getValue().getBillingPolicy()).isEqualTo("INCLUDED");

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("STREAM_COMPLETED");
    assertThat(event.getValue().getPayloadJson()).contains("\"costUsd\":0.012");
  }

  @Test
  void failMarksTurnErrorAndRecordsFailureEvent() {
    AgentTurn turn = turn("t2", "STREAMING");
    when(turnRepo.findById("t2")).thenReturn(Optional.of(turn));

    lifecycle().fail(ctx("t2"), "provider timeout");

    assertThat(turn.getStatus()).isEqualTo("ERROR");
    assertThat(turn.getAssistantSummary()).isEqualTo("provider timeout");
    verify(turnRepo).save(turn);

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("STREAM_FAILED");
    assertThat(event.getValue().getPayloadJson()).contains("provider timeout");
  }

  @Test
  void disconnectCancelsOnlyStreamingTurns() {
    AgentTurn completed = turn("t3", "OK");
    completed.setAssistantSummary("done");
    when(turnRepo.findById("t3")).thenReturn(Optional.of(completed));

    lifecycle().disconnect(ctx("t3"));

    assertThat(completed.getStatus()).isEqualTo("OK");
    assertThat(completed.getAssistantSummary()).isEqualTo("done");
    verify(turnRepo, never()).save(any(AgentTurn.class));

    var event = ArgumentCaptor.forClass(AgentActionEvent.class);
    verify(eventRepo).save(event.capture());
    assertThat(event.getValue().getType()).isEqualTo("STREAM_DISCONNECTED");
  }

  private AgentStreamTurnLifecycle lifecycle() {
    return new AgentStreamTurnLifecycle(
        turnRepo, new AgentEventRecorder(eventRepo, new ObjectMapper()), usageRepo);
  }

  private static AgentSessionService.StreamTurnContext ctx(String turnId) {
    return new AgentSessionService.StreamTurnContext(
        "s1", turnId, 1, "p1", null, "org-1", "u1", "DEEPSEEK", "deepseek-v4-flash-native", null);
  }

  private static AgentTurn turn(String id, String status) {
    return AgentTurn.builder()
        .id(id)
        .sessionId("s1")
        .orgId("org-1")
        .userId("u1")
        .sequence(1)
        .userMessage("hello")
        .assistantSummary("working")
        .intentType("CHAT_ONLY")
        .intentConfidence(0.8)
        .status(status)
        .build();
  }
}
