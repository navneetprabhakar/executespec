package com.navneet.executespec.workspacegeneration;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Owns the narrow durable transition from a claimed LLM operation to workspace authority. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationAdmissionStore {

  private final WorkspaceGenerationAttemptRepository attempts;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void acquireAuthority(
      UUID attemptId,
      UUID workspaceToken,
      String llmOperationId,
      int llmAttemptNumber,
      UUID llmLeaseToken) {
    int updated =
        attempts.acquireAuthority(
            attemptId,
            workspaceToken,
            llmOperationId,
            llmAttemptNumber,
            llmLeaseToken,
            WorkspaceGenerationState.OPERATION_CLAIMED,
            WorkspaceGenerationState.AUTHORITY_ACQUIRED,
            Instant.now());
    if (updated != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_AUTHORITY_ACQUIRE_CAS_FAILED");
    }
  }
}
