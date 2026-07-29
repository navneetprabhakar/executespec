package com.navneet.executespec.agentsession;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.agentsession.subagent.SubagentProtocolService;
import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.specchat.SpecChatService;

/**
 * Renews and terminalizes the child-session and LLM-operation leases for one subagent execution.
 */
final class SubagentOperationLifecycle {

  private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;

  private final SubagentProtocolService protocolService;
  private final LlmExecutionOperationCoordinator coordinator;

  SubagentOperationLifecycle(
      SubagentProtocolService protocolService, LlmExecutionOperationCoordinator coordinator) {
    this.protocolService = protocolService;
    this.coordinator = coordinator;
  }

  Lease start(String childSessionId, String userId, String orgId) {
    Lease lease = new Lease();
    lease.heartbeat =
        Thread.ofVirtual()
            .name("subagent-heartbeat-" + childSessionId)
            .start(() -> heartbeat(childSessionId, userId, orgId, lease));
    return lease;
  }

  private void heartbeat(String childSessionId, String userId, String orgId, Lease lease) {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
        protocolService.heartbeat(childSessionId, userId, orgId);
        SpecChatService.DurableTurnOperation active = lease.operation();
        if (active != null
            && coordinator != null
            && !coordinator.heartbeat(active.lease(), Duration.ofMinutes(30))) {
          return;
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException renewalFailure) {
        // One advisory renewal failure must not silently end a live child lease.
      }
    }
  }

  void complete(Lease lease) {
    if (coordinator == null || lease.operation() == null) return;
    if (!coordinator.complete(lease.operation().lease())) {
      throw new IllegalStateException("subagent LLM operation could not be completed");
    }
  }

  void terminate(Lease lease, RuntimeException failure) {
    if (coordinator == null || lease.operation() == null) return;
    try {
      if (coordinator.acknowledgeCancellation(lease.operation().lease())) return;
      coordinator.fail(
          lease.operation().lease(),
          "SUBAGENT_EXECUTION_FAILED",
          SubagentTurnSupport.safeMessage(failure),
          false,
          null);
    } catch (RuntimeException ignored) {
      // Preserve the original child execution failure.
    }
  }

  static final class Lease implements AutoCloseable {
    private final AtomicReference<SpecChatService.DurableTurnOperation> operation =
        new AtomicReference<>();
    private Thread heartbeat;

    void bind(SpecChatService.DurableTurnOperation value) {
      operation.set(value);
    }

    SpecChatService.DurableTurnOperation operation() {
      return operation.get();
    }

    @Override
    public void close() {
      heartbeat.interrupt();
    }
  }
}
