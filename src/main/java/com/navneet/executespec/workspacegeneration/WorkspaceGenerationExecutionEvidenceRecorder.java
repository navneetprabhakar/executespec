package com.navneet.executespec.workspacegeneration;

import java.math.BigDecimal;
import java.time.Instant;

import com.navneet.executespec.cost.RunCostTracker;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Freezes physical worker route and usage evidence before candidate verification starts. */
@Service
@RequiredArgsConstructor
public class WorkspaceGenerationExecutionEvidenceRecorder {

  private final WorkspaceGenerationAttemptRepository attempts;

  @Transactional
  public void record(
      WorkspaceGenerationLifecycle.Prepared prepared,
      String provider,
      String model,
      int iterations,
      int toolCalls,
      RunCostTracker.Snapshot usage) {
    prepared.lease().assertOwned();
    RunCostTracker.Snapshot safe =
        usage == null ? new RunCostTracker.Snapshot(0, 0, 0, 0, 0.0, 0, 0) : usage;
    int updated =
        attempts.recordExecutionEvidence(
            prepared.attemptId(),
            prepared.lease().ownerToken(),
            WorkspaceGenerationState.MUTATING,
            required(provider, "WORKSPACE_GENERATION_EFFECTIVE_PROVIDER_INVALID"),
            required(model, "WORKSPACE_GENERATION_EFFECTIVE_MODEL_INVALID"),
            nonNegative(iterations, "iterations"),
            nonNegative(toolCalls, "toolCalls"),
            Math.max(0, safe.calls()),
            Math.max(0, safe.inputTokens()),
            Math.max(0, safe.outputTokens()),
            Math.max(0, safe.cacheReadTokens()),
            Math.max(0, safe.cacheWriteTokens()),
            Math.max(0, safe.reasoningTokens()),
            BigDecimal.valueOf(Math.max(0.0, safe.costUsd())),
            Instant.now());
    if (updated != 1) {
      throw new IllegalStateException("WORKSPACE_GENERATION_EXECUTION_EVIDENCE_CAS_FAILED");
    }
  }

  private static String required(String value, String code) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(code);
    return value;
  }

  private static int nonNegative(int value, String field) {
    if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
    return value;
  }
}
