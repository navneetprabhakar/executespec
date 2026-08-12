package com.navneet.executespec.run.outcome;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceipt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Joins terminal run state to its newest durable BUILD generation causality. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunTerminalOutcomeProjectionService {

  private final WorkspaceGenerationAttemptRepository attempts;
  private final WorkspaceGenerationVerificationReceiptRepository receipts;

  public RunTerminalOutcome project(PipelineRun run) {
    return project(run, run == null ? null : run.getFailureReason(), null);
  }

  public RunTerminalOutcome project(PipelineRun run, String fallbackFailureReason) {
    return project(run, fallbackFailureReason, null);
  }

  public RunTerminalOutcome project(
      PipelineRun run, String fallbackFailureReason, Boolean creditsRefunded) {
    if (run == null) return null;
    WorkspaceGenerationFailureProjection generationFailure = generationFailure(run.getRunId());
    String failureReason =
        run.getFailureReason() == null || run.getFailureReason().isBlank()
            ? fallbackFailureReason
            : run.getFailureReason();
    return RunTerminalOutcomeContract.project(
        run.getRunId(),
        run.getStatus(),
        run.getStatusReason(),
        failureReason,
        run.getActualCostUsd(),
        run.getHardCapUsd() != null ? run.getHardCapUsd() : run.getUserCostCap(),
        run.getScmPrUrl() != null ? run.getScmPrUrl() : run.getGithubPrUrl(),
        RunTerminalOutcomeContract.autoPublicationExpected(run),
        creditsRefunded,
        generationFailure);
  }

  private WorkspaceGenerationFailureProjection generationFailure(String runId) {
    if (runId == null) return null;
    try {
      // Attempt history is newest-first. Only the newest physical attempt may explain the run's
      // terminal state; walking backward would let an obsolete rollback override a later accepted
      // generation.
      return attempts.findTop20ByRunIdOrderByCreatedAtDesc(runId).stream()
          .findFirst()
          .map(this::project)
          .orElse(null);
    } catch (RuntimeException evidenceUnavailable) {
      // Terminal notification/email/activity projection must degrade to the existing run contract
      // when the additive generation ledger cannot be read.
      log.warn(
          "workspace generation terminal evidence unavailable for run {}: {}",
          runId,
          evidenceUnavailable.getMessage());
      return null;
    }
  }

  private WorkspaceGenerationFailureProjection project(WorkspaceGenerationAttempt attempt) {
    WorkspaceGenerationVerificationReceipt receipt =
        receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attempt.getId()).orElse(null);
    return WorkspaceGenerationFailureProjection.project(attempt, receipt);
  }
}
