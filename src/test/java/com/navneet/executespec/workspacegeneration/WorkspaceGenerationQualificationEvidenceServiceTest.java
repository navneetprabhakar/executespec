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
    WorkspaceGenerationQualificationEvidenceService service =
        new WorkspaceGenerationQualificationEvidenceService(attempts, receipts);
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

    var evidence = service.read(attemptId);

    assertThat(evidence.state()).isEqualTo(WorkspaceGenerationState.ROLLED_BACK);
    assertThat(evidence.priorAcceptedGeneration()).isEqualTo("a".repeat(64));
    assertThat(evidence.candidateGeneration()).isNull();
    assertThat(evidence.rejectedDiffDigest()).isEqualTo("b".repeat(64));
    assertThat(evidence.rejectedDiffJson()).contains("calculator.py");
    assertThat(evidence.recoveryFailureCode()).isEqualTo("ROLLBACK_PROOF_FAILED");
    assertThat(evidence.recoveryFailureReason()).contains("main.py");
    assertThat(evidence.latestVerification()).isNull();
  }
}
