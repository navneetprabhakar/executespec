package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionOperation;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AgentTurnRecoveryServiceTest {

  private final AgentTurnRepository turns = mock(AgentTurnRepository.class);
  private final AgentSessionRepository sessions = mock(AgentSessionRepository.class);
  private final ProjectRepository projects = mock(ProjectRepository.class);
  private final LlmExecutionContextService contexts = mock(LlmExecutionContextService.class);
  private final LlmExecutionOperationCoordinator coordinator =
      mock(LlmExecutionOperationCoordinator.class);
  private final LlmExecutionOperationService operations = mock(LlmExecutionOperationService.class);
  private final AgentEventRecorder events = mock(AgentEventRecorder.class);
  private final TransactionTemplate transactions = mock(TransactionTemplate.class);
  private final AgentTurnRecoveryService recovery =
      new AgentTurnRecoveryService(
          turns, sessions, projects, contexts, coordinator, operations, events, transactions);

  @BeforeEach
  void executeTransactionsInline() {
    when(transactions.execute(any()))
        .thenAnswer(
            invocation -> {
              @SuppressWarnings("unchecked")
              TransactionCallback<Object> callback = invocation.getArgument(0);
              return callback.doInTransaction(mock(TransactionStatus.class));
            });
  }

  @Test
  void staleTurnIsFlushedBeforeExactOperationCancellationAndNeverReplayed() {
    Instant staleBefore = Instant.parse("2026-07-28T00:00:00Z");
    when(turns.databaseNow()).thenReturn(staleBefore.plus(Duration.ofHours(25)));
    AgentTurn turn = staleTurn("turn-1", staleBefore.minusSeconds(1));
    AgentSession session =
        AgentSession.builder()
            .id("session-1")
            .orgId("org-1")
            .userId("user-1")
            .title("chat")
            .status("ACTIVE")
            .build();
    LlmExecutionReference reference =
        new LlmExecutionReference("llmctx-turn-1", LlmExecutionScope.customer("org-1"));
    LlmExecutionContext context = mock(LlmExecutionContext.class);

    when(turns.findStaleStreaming(eq(staleBefore), any(Pageable.class))).thenReturn(List.of(turn));
    when(turns.findByIdForUpdate("turn-1")).thenReturn(Optional.of(turn));
    when(sessions.findById("session-1")).thenReturn(Optional.of(session));
    when(contexts.findReference(
            eq(reference.scope()), eq("spec-chat"), eq("CHAT_TURN"), any(), eq("chat-turn-v1")))
        .thenReturn(Optional.of(reference));
    when(contexts.load(reference.scope(), reference.contextId())).thenReturn(Optional.of(context));
    when(context.getContextType()).thenReturn(LlmExecutionContext.ContextType.AGENT_TURN);
    when(context.getUserId()).thenReturn("user-1");
    when(context.getOrgId()).thenReturn("org-1");
    when(context.getCorrelationId()).thenReturn("turn-1");
    when(coordinator.requestCancellation(reference, "agent-turn:model-loop"))
        .thenReturn(Optional.of(LlmExecutionOperation.Status.CANCEL_REQUESTED));
    when(operations.finalizeDueCancellations(10)).thenReturn(1);

    assertThat(recovery.reconcile(Duration.ofHours(25), 10)).isEqualTo(1);
    assertThat(turn.getStatus()).isEqualTo("ERROR");
    assertThat(turn.getAssistantSummary()).isEqualTo(AgentTurnRecoveryService.RECOVERY_SUMMARY);
    InOrder order = org.mockito.Mockito.inOrder(turns, coordinator);
    order.verify(turns).saveAndFlush(turn);
    order.verify(coordinator).requestCancellation(reference, "agent-turn:model-loop");
    verify(turns).databaseNow();
    verify(operations).finalizeDueCancellations(10);
  }

  @Test
  void missingDurableContextFailsTurnClosedAndRecordsReconciliationGap() {
    Instant staleBefore = Instant.parse("2026-07-28T00:00:00Z");
    when(turns.databaseNow()).thenReturn(staleBefore.plus(Duration.ofHours(25)));
    AgentTurn turn = staleTurn("turn-gap", staleBefore.minusSeconds(1));
    AgentSession session =
        AgentSession.builder()
            .id("session-1")
            .orgId("org-1")
            .userId("user-1")
            .title("chat")
            .status("ACTIVE")
            .build();
    when(turns.findStaleStreaming(eq(staleBefore), any(Pageable.class))).thenReturn(List.of(turn));
    when(turns.findByIdForUpdate("turn-gap")).thenReturn(Optional.of(turn));
    when(sessions.findById("session-1")).thenReturn(Optional.of(session));
    when(contexts.findReference(any(), eq("spec-chat"), eq("CHAT_TURN"), any(), eq("chat-turn-v1")))
        .thenReturn(Optional.empty());

    assertThat(recovery.reconcile(Duration.ofHours(25), 10)).isEqualTo(1);
    assertThat(turn.getStatus()).isEqualTo("ERROR");
    verify(coordinator, never()).requestCancellation(any(), any());
    ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
    verify(events)
        .append(
            eq("session-1"),
            eq("turn-gap"),
            eq("STREAM_RECOVERED_OUTCOME_UNCERTAIN"),
            payload.capture());
    assertThat(payload.getValue().toString()).contains("EXECUTION_CONTEXT_NOT_FOUND");
  }

  @Test
  void peerCompletionWinsTheRecoveryRowLock() {
    Instant staleBefore = Instant.parse("2026-07-28T00:00:00Z");
    when(turns.databaseNow()).thenReturn(staleBefore.plus(Duration.ofHours(25)));
    AgentTurn snapshot = staleTurn("turn-peer", staleBefore.minusSeconds(1));
    AgentTurn completed = staleTurn("turn-peer", staleBefore.minusSeconds(1));
    completed.setStatus("OK");
    when(turns.findStaleStreaming(eq(staleBefore), any(Pageable.class)))
        .thenReturn(List.of(snapshot));
    when(turns.findByIdForUpdate("turn-peer")).thenReturn(Optional.of(completed));

    assertThat(recovery.reconcile(Duration.ofHours(25), 10)).isZero();
    verify(turns, never()).saveAndFlush(any());
    verify(coordinator, never()).requestCancellation(any(), any());
    verify(operations).finalizeDueCancellations(10);
  }

  private static AgentTurn staleTurn(String id, Instant createdAt) {
    return AgentTurn.builder()
        .id(id)
        .sessionId("session-1")
        .orgId("org-1")
        .userId("user-1")
        .sequence(1)
        .intentType("ANSWER")
        .status("STREAMING")
        .createdAt(createdAt)
        .build();
  }
}
