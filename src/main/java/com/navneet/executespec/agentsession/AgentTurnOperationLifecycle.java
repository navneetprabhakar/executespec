package com.navneet.executespec.agentsession;

import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.specchat.SpecChatService;

import lombok.extern.slf4j.Slf4j;

/** Owns terminal transitions for the durable LLM operation behind one streamed agent turn. */
@Slf4j
final class AgentTurnOperationLifecycle {

  private final LlmExecutionOperationCoordinator coordinator;

  AgentTurnOperationLifecycle(LlmExecutionOperationCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  boolean enabled() {
    return coordinator != null;
  }

  void complete(SpecChatService.DurableTurnOperation operation) {
    if (operation == null) {
      throw new IllegalStateException("durable agent turn did not claim an LLM operation");
    }
    if (!coordinator.complete(operation.lease())) {
      throw new IllegalStateException("durable agent turn operation could not be completed");
    }
  }

  void fail(
      SpecChatService.DurableTurnOperation operation, boolean alreadyCompleted, String message) {
    if (operation == null || alreadyCompleted || coordinator == null) return;
    if (!coordinator.fail(operation.lease(), "AGENT_TURN_FAILED", message, false, null)) {
      log.warn("durable agent turn failure could not terminalize operation");
    }
  }

  void cancel(SpecChatService.DurableTurnOperation operation, boolean alreadyCompleted) {
    if (operation == null || alreadyCompleted || coordinator == null) return;
    coordinator.requestCancellation(
        operation.reference(), SpecChatService.AGENT_TURN_OPERATION_KEY);
    if (!coordinator.acknowledgeCancellation(operation.lease())) {
      log.warn("durable agent turn cancellation could not be acknowledged");
    }
  }
}
