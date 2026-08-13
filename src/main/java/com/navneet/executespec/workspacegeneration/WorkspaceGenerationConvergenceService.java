package com.navneet.executespec.workspacegeneration;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Converges a prepared BUILD generation from committed durable state.
 *
 * <p>The coordinator must never infer an accepted or rejected terminal outcome from the point at
 * which an exception was thrown. In particular, entering {@code accept()} does not prove that its
 * transaction committed. This service re-reads the attempt and makes the only permitted in-process
 * choice: reconcile an already-terminal attempt, or roll back a still-mutable attempt using the
 * BUILD lease that is still held by the caller.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationConvergenceService {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final WorkspaceGenerationRollbackService rollback;
  private final WorkspaceGenerationTerminalReconciler terminals;

  public enum Disposition {
    ACCEPTED_AND_RECONCILED,
    ROLLED_BACK_AND_RECONCILED
  }

  /** Reconciles a normal terminal transition after ACCEPTED or ROLLED_BACK committed. */
  public Disposition reconcileTerminal(WorkspaceGenerationLifecycle.Prepared prepared) {
    WorkspaceGenerationAttempt durable = require(prepared);
    return switch (durable.getState()) {
      case ACCEPTED -> {
        terminals.reconcileRun(durable.getRunId());
        yield Disposition.ACCEPTED_AND_RECONCILED;
      }
      case ROLLED_BACK -> {
        terminals.reconcileRolledBack(durable);
        yield Disposition.ROLLED_BACK_AND_RECONCILED;
      }
      default -> throw new IllegalStateException("WORKSPACE_GENERATION_NOT_TERMINAL");
    };
  }

  /**
   * Converges after a coordinator exception without consulting request-thread booleans.
   *
   * <p>ROLLING_BACK and RECOVERY_FAILED are deliberately not retried here. The BUILD lease is
   * released by the caller's finally block; scheduled recovery can then acquire the mutually
   * exclusive RECOVERY authority and resume from durable rollback evidence.
   */
  public Disposition convergeAfterFailure(
      WorkspaceGenerationLifecycle.Prepared prepared, String failureCode, String failureReason) {
    WorkspaceGenerationAttempt durable = require(prepared);
    return switch (durable.getState()) {
      case ACCEPTED -> {
        terminals.reconcileRun(durable.getRunId());
        yield Disposition.ACCEPTED_AND_RECONCILED;
      }
      case ROLLED_BACK -> {
        terminals.reconcileRolledBack(durable);
        yield Disposition.ROLLED_BACK_AND_RECONCILED;
      }
      case PREPARED, MUTATING, VERIFYING, VERIFIED, CHECKPOINTING -> {
        rollbackMutable(prepared, failureCode, failureReason);
        WorkspaceGenerationAttempt rolledBack = require(prepared);
        if (rolledBack.getState() != WorkspaceGenerationState.ROLLED_BACK) {
          throw new IllegalStateException("WORKSPACE_GENERATION_ROLLBACK_UNPROVEN");
        }
        terminals.reconcileRolledBack(rolledBack);
        yield Disposition.ROLLED_BACK_AND_RECONCILED;
      }
      case ROLLING_BACK, RECOVERY_FAILED ->
          throw new IllegalStateException("WORKSPACE_GENERATION_RECOVERY_REQUIRED");
      default -> throw new IllegalStateException("WORKSPACE_GENERATION_FAILURE_STATE_CONFLICT");
    };
  }

  private void rollbackMutable(
      WorkspaceGenerationLifecycle.Prepared prepared, String failureCode, String failureReason) {
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = prepared.checkpoint();
    rollback.rollback(
        prepared.attemptId(),
        prepared.lease().ownerToken(),
        checkpoint,
        prepared.lease()::assertOwned,
        failureCode,
        failureReason);
  }

  private WorkspaceGenerationAttempt require(WorkspaceGenerationLifecycle.Prepared prepared) {
    return attempts
        .findById(prepared.attemptId())
        .orElseThrow(() -> new IllegalStateException("WORKSPACE_GENERATION_ATTEMPT_NOT_FOUND"));
  }
}
