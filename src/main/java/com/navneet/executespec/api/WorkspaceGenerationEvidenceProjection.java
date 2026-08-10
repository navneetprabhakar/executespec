package com.navneet.executespec.api;

import java.util.List;

import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Projects the bounded customer-safe BUILD generation ledger for run detail. */
@Component
@RequiredArgsConstructor
public class WorkspaceGenerationEvidenceProjection {

  private final WorkspaceGenerationAttemptRepository attempts;

  public List<RunDetail.WorkspaceGenerationAttemptEvidence> forRun(String runId) {
    if (runId == null) return List.of();
    return attempts.findTop20ByRunIdOrderByCreatedAtDesc(runId).stream()
        .map(WorkspaceGenerationEvidenceProjection::project)
        .toList();
  }

  private static RunDetail.WorkspaceGenerationAttemptEvidence project(
      WorkspaceGenerationAttempt attempt) {
    return new RunDetail.WorkspaceGenerationAttemptEvidence(
        attempt.getId().toString(),
        attempt.getTaskId(),
        attempt.getTaskIndex(),
        attempt.getAttemptNumber(),
        attempt.getAttemptKind().name(),
        attempt.getState().name(),
        attempt.getBeforeGeneration(),
        attempt.getCandidateGeneration(),
        attempt.getRejectedDiffDigest(),
        attempt.getFailureCode(),
        attempt.getRecoveryFailureCode(),
        attempt.getRecoveryFailureAt(),
        attempt.getCreatedAt(),
        attempt.getResolvedAt());
  }
}
