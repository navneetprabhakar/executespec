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
  void cancellationWinsAgainstLateTerminalClaim() {
    String runId = "terminal-cancel-" + UUID.randomUUID();
    runRepository.save(PipelineRun.builder().runId(runId).status("REVIEWING").build());

    PipelineRun cancelled = runRepository.findById(runId).orElseThrow();
    cancelled.setStatus("CANCELLED");
    cancelled.setFailureReason("Cancelled by user from status REVIEWING");
    cancelled.setCompletedAt(Instant.now());
    runRepository.save(cancelled);

    int lateClaim =
        runRepository.claimTerminalTransitionWithReasons(
            runId, "COMPLETE", "CLEAN_COMPLETION", null, Instant.now());

    assertThat(lateClaim).isZero();
    PipelineRun persisted = runRepository.findById(runId).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo("CANCELLED");
    assertThat(persisted.getFailureReason()).contains("Cancelled by user");
  }
}
