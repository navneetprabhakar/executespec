package com.navneet.executespec.agentsession;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.executioncontext.LlmExecutionContext;
import com.navneet.executespec.executioncontext.LlmExecutionContextService;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationService;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.specchat.SpecChatService;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Fail-closed recovery for provider turns abandoned in {@code STREAMING}. */
@Service
@RequiredArgsConstructor
@Slf4j
class AgentTurnRecoveryService {

  static final String RECOVERY_SUMMARY =
      "OUTCOME_UNCERTAIN: the streaming process ended without a durable completion. "
          + "No provider call was replayed.";

  private final AgentTurnRepository turnRepository;
  private final AgentSessionRepository sessionRepository;
  private final ProjectRepository projectRepository;
  private final LlmExecutionContextService contextService;
  private final LlmExecutionOperationCoordinator operationCoordinator;
  private final LlmExecutionOperationService operationService;
  private final AgentEventRecorder eventRecorder;
  private final TransactionTemplate transactionTemplate;

  int reconcile(Duration staleAge, int limit) {
    if (staleAge == null || staleAge.isNegative() || staleAge.isZero()) {
      throw new IllegalArgumentException("staleAge must be positive");
    }
    if (limit <= 0 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
    Instant staleBefore = turnRepository.databaseNow().minus(staleAge);
    var candidates = turnRepository.findStaleStreaming(staleBefore, PageRequest.of(0, limit));
    int recovered = 0;
    for (AgentTurn candidate : candidates) {
      Boolean changed =
          transactionTemplate.execute(ignored -> recoverLocked(candidate.getId(), staleBefore));
      if (Boolean.TRUE.equals(changed)) recovered++;
    }
    // This service alone decides whether an operation is due: the operation service rechecks the
    // database clock plus both lease_expires_at and not_reclaim_before under locks. It never starts
    // transport and never reclaims/replays a provider attempt.
    int finalized = operationService.finalizeDueCancellations(limit);
    if (recovered > 0 || finalized > 0) {
      log.warn(
          "Recovered {} stale streaming agent turn(s); finalized {} due operation cancellation(s)",
          recovered,
          finalized);
    }
    return recovered;
  }

  private boolean recoverLocked(String turnId, Instant staleBefore) {
    AgentTurn turn = turnRepository.findByIdForUpdate(turnId).orElse(null);
    if (turn == null
        || !"STREAMING".equals(turn.getStatus())
        || turn.getCreatedAt() == null
        || turn.getCreatedAt().isAfter(staleBefore)) {
      return false;
    }

    AgentSession session = sessionRepository.findById(turn.getSessionId()).orElse(null);
    RecoveredReference recoveredReference;
    try {
      recoveredReference = recoverReference(turn, session);
    } catch (RuntimeException lookupFailure) {
      recoveredReference =
          RecoveredReference.gap(
              "EXECUTION_CONTEXT_LOOKUP_FAILED:" + lookupFailure.getClass().getSimpleName());
    }

    // The business outcome is flushed first. Any operation cancellation below joins this same
    // transaction, so a crash cannot commit one side without the other.
    turn.setStatus("ERROR");
    turn.setAssistantSummary(RECOVERY_SUMMARY);
    turnRepository.saveAndFlush(turn);

    String operationReconciliation;
    if (recoveredReference.reference() == null) {
      operationReconciliation = recoveredReference.gapReason();
    } else {
      try {
        var status =
            operationCoordinator.requestCancellation(
                recoveredReference.reference(), SpecChatService.AGENT_TURN_OPERATION_KEY);
        operationReconciliation =
            status
                .map(value -> "CANCELLATION_REQUESTED:" + value.name())
                .orElse("OPERATION_NOT_FOUND");
      } catch (RuntimeException cancellationFailure) {
        operationReconciliation =
            "CANCELLATION_REQUEST_FAILED:" + cancellationFailure.getClass().getSimpleName();
      }
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("reason", "OUTCOME_UNCERTAIN");
    payload.put("providerReplayAttempted", false);
    payload.put("staleBefore", staleBefore.toString());
    payload.put("operationReconciliation", operationReconciliation);
    eventRecorder.append(
        turn.getSessionId(), turn.getId(), "STREAM_RECOVERED_OUTCOME_UNCERTAIN", payload);
    return true;
  }

  private RecoveredReference recoverReference(AgentTurn turn, AgentSession session) {
    if (session == null || !session.getId().equals(turn.getSessionId())) {
      return RecoveredReference.gap("SESSION_NOT_FOUND");
    }
    String hostOrgId = turn.getOrgId();
    String projectId = session.getProjectId();
    if (projectId != null && !projectId.isBlank()) {
      var project = projectRepository.findById(projectId);
      if (project.isEmpty()) return RecoveredReference.gap("PROJECT_NOT_FOUND");
      hostOrgId = project.get().getOrgId();
    }
    if (hostOrgId == null || hostOrgId.isBlank()) {
      return RecoveredReference.gap("HOST_ORG_NOT_PROVABLE");
    }

    String sourceId =
        "chat:"
            + UUID.nameUUIDFromBytes(
                (turn.getSessionId() + "\u001f" + turn.getId()).getBytes(StandardCharsets.UTF_8));
    LlmExecutionScope scope = LlmExecutionScope.customer(hostOrgId);
    var reference =
        contextService.findReference(scope, "spec-chat", "CHAT_TURN", sourceId, "chat-turn-v1");
    if (reference.isEmpty()) return RecoveredReference.gap("EXECUTION_CONTEXT_NOT_FOUND");

    LlmExecutionContext context =
        contextService.load(scope, reference.get().contextId()).orElse(null);
    if (context == null
        || context.getContextType() != LlmExecutionContext.ContextType.AGENT_TURN
        || !turn.getUserId().equals(context.getUserId())
        || !hostOrgId.equals(context.getOrgId())
        || !java.util.Objects.equals(projectId, context.getProjectId())
        || !turn.getId().equals(context.getCorrelationId())) {
      return RecoveredReference.gap("EXECUTION_CONTEXT_IDENTITY_MISMATCH");
    }
    return new RecoveredReference(reference.get(), null);
  }

  private record RecoveredReference(LlmExecutionReference reference, String gapReason) {
    static RecoveredReference gap(String reason) {
      return new RecoveredReference(null, reason);
    }
  }
}
