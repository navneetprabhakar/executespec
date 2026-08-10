package com.navneet.executespec.workspacegeneration;

import java.time.Instant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Persists the immutable initial acceptance probe before a BUILD provider may dispatch. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationInitialFailureRecorder {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final ObjectMapper objectMapper;

  @Transactional
  public void record(
      WorkspaceGenerationLifecycle.Prepared prepared,
      TaskAcceptanceVerifier.InitialFailure evidence) {
    prepared.lease().assertOwned();
    String json;
    try {
      json = objectMapper.writeValueAsString(evidence);
    } catch (JsonProcessingException failure) {
      throw new IllegalStateException("INITIAL_FAILURE_EVIDENCE_SERIALIZATION_FAILED", failure);
    }
    int updated =
        attempts.recordObservedInitialFailure(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            WorkspaceGenerationState.MUTATING,
            json,
            Instant.now());
    if (updated != 1) {
      throw new IllegalStateException("INITIAL_FAILURE_EVIDENCE_CAS_FAILED");
    }
  }
}
