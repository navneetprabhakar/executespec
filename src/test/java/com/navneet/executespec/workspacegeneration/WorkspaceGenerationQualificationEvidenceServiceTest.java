package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationQualificationEvidenceServiceTest {

  @Test
  void exposesImmutableRejectedDiffForOperatorReviewWithoutInventingCandidateGeneration() {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    BuildEnvironmentReceiptStore environmentReceipts = mock(BuildEnvironmentReceiptStore.class);
    WorkspaceGenerationStepStore generationSteps = mock(WorkspaceGenerationStepStore.class);
    WorkspaceGenerationQualificationEvidenceService service =
        new WorkspaceGenerationQualificationEvidenceService(
            attempts, receipts, environmentReceipts, generationSteps);
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId("run-1")
            .taskId("T1")
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .beforeGeneration("a".repeat(64))
            .candidateGeneration(null)
            .rejectedDiffDigest("b".repeat(64))
            .rejectedDiffJson("{\"files\":[{\"path\":\"calculator.py\"}]}")
            .failureCode("TASK_OUTCOME_UNACCEPTED")
            .recoveryFailureCode("ROLLBACK_PROOF_FAILED")
            .recoveryFailureReason("residualPaths=[0:main.py]")
            .requestedProvider("ANTHROPIC")
            .requestedModel("claude-sonnet-5")
            .build();
    when(attempts.findById(attemptId)).thenReturn(Optional.of(attempt));
    when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId)).thenReturn(Optional.empty());
    var cleanup =
        new BuildEnvironmentReceiptStore.ReceiptEvidence(
            UUID.randomUUID(),
            "CHECKPOINT_CLEANUP",
            "CLEANUP",
            null,
            null,
            null,
            null,
            "PASSED",
            "[\"/tmp/run-1:node_modules/x.js\"]",
            "c".repeat(64),
            "{}",
            "d".repeat(64),
            java.time.Instant.EPOCH);
    when(environmentReceipts.readAll(attemptId)).thenReturn(java.util.List.of(cleanup));
    when(generationSteps.readAll(attemptId))
        .thenReturn(
            java.util.List.of(
                new WorkspaceGenerationStepStore.Step(
                    UUID.randomUUID(),
                    attemptId,
                    WorkspaceGenerationStepStore.Kind.ROLLBACK_RESTORE,
                    "repository:.",
                    "e".repeat(64),
                    WorkspaceGenerationStepStore.State.VERIFIED,
                    "a".repeat(64),
                    "f".repeat(40),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "{}")));

    var evidence = service.read(attemptId);

    assertThat(evidence.state()).isEqualTo(WorkspaceGenerationState.ROLLED_BACK);
    assertThat(evidence.priorAcceptedGeneration()).isEqualTo("a".repeat(64));
    assertThat(evidence.candidateGeneration()).isNull();
    assertThat(evidence.rejectedDiffDigest()).isEqualTo("b".repeat(64));
    assertThat(evidence.rejectedDiffJson()).contains("calculator.py");
    assertThat(evidence.recoveryFailureCode()).isEqualTo("ROLLBACK_PROOF_FAILED");
    assertThat(evidence.recoveryFailureReason()).contains("main.py");
    assertThat(evidence.latestVerification()).isNull();
    assertThat(evidence.environmentReceipts())
        .singleElement()
        .satisfies(
            receipt -> {
              assertThat(receipt.phase()).isEqualTo("CHECKPOINT_CLEANUP");
              assertThat(receipt.removedRootsJson()).contains("node_modules/x.js");
            });
    assertThat(evidence.filesystemSteps())
        .singleElement()
        .satisfies(
            step -> {
              assertThat(step.kind()).isEqualTo("ROLLBACK_RESTORE");
              assertThat(step.state()).isEqualTo("VERIFIED");
              assertThat(step.stepKey()).isEqualTo("repository:.");
            });
  }
}
