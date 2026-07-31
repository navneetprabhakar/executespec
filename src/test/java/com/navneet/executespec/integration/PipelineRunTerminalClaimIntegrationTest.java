package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Proves the terminal transition cannot overwrite a user cancellation. */
class PipelineRunTerminalClaimIntegrationTest extends PostgresIntegrationBase {

  @Autowired private PipelineRunRepository runRepository;

  @Test
  void activeRunCanBeClaimedExactlyOnce() {
    String runId = "terminal-claim-" + UUID.randomUUID();
    runRepository.save(PipelineRun.builder().runId(runId).status("REVIEWING").build());

    int first =
        runRepository.claimTerminalTransitionWithReasons(
            runId, "COMPLETE", "CLEAN_COMPLETION", null, Instant.now());
    int second =
        runRepository.claimTerminalTransitionWithReasons(
            runId, "PARTIAL", "REVIEWER_UNRESOLVED", null, Instant.now());

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
    assertThat(runRepository.findById(runId).orElseThrow().getStatus()).isEqualTo("COMPLETE");
  }

  @Test
  void deterministicHttpValidationFailureDurablyTransitionsToFailedWithExactCode() {
    String runId = "terminal-validation-" + UUID.randomUUID();
    String code = "DETERMINISTIC_HTTP_REQUIREMENT_MISSING";
    runRepository.save(
        PipelineRun.builder()
            .runId(runId)
            .status("REVIEWING")
            .failureReason("VALIDATION_FAILED[" + code + "]: POST /items is absent")
            .build());

    int claimed =
        runRepository.claimTerminalTransitionWithReasons(
            runId,
            RunValidationKickoff.unavailableValidationTerminalStatus(code),
            code,
            "VALIDATION_FAILED[" + code + "]: POST /items is absent",
            Instant.now());

    assertThat(claimed).isEqualTo(1);
    PipelineRun persisted = runRepository.findById(runId).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo("FAILED");
    assertThat(persisted.getStatusReason()).isEqualTo(code);
    assertThat(persisted.getFailureReason()).contains("POST /items is absent");
  }

  @Test
  void cancellationWinsAgainstLateTerminalClaim() {
    String runId = "terminal-cancel-" + UUID.randomUUID();
    runRepository.save(PipelineRun.builder().runId(runId).status("REVIEWING").build());

    int cancellation =
        runRepository.claimTerminalTransitionWithReasons(
            runId, "CANCELLED", null, "Cancelled by user from status REVIEWING", Instant.now());

    int lateClaim =
        runRepository.claimTerminalTransitionWithReasons(
            runId, "COMPLETE", "CLEAN_COMPLETION", null, Instant.now());

    assertThat(cancellation).isEqualTo(1);
    assertThat(lateClaim).isZero();
    PipelineRun persisted = runRepository.findById(runId).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo("CANCELLED");
    assertThat(persisted.getFailureReason()).contains("Cancelled by user");
  }

  @Test
  void lateUsageFloorCanBeRaisedWithoutChangingCancelledStatus() {
    String runId = "terminal-cancel-usage-" + UUID.randomUUID();
    runRepository.save(PipelineRun.builder().runId(runId).status("WORKING").build());
    assertThat(
            runRepository.claimTerminalTransitionWithReasons(
                runId, "CANCELLED", null, "Cancelled by user from status WORKING", Instant.now()))
        .isEqualTo(1);

    runRepository.raiseUsageTotalsToAtLeast(runId, 409_127, 14_706, 0.010256);

    PipelineRun persisted = runRepository.findById(runId).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo("CANCELLED");
    assertThat(persisted.getTotalInputTokens()).isEqualTo(409_127);
    assertThat(persisted.getTotalOutputTokens()).isEqualTo(14_706);
    assertThat(persisted.getActualCostUsd()).isEqualTo(0.010256);
  }
}
