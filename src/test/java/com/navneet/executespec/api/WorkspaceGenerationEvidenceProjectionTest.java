package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationKind;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationState;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceipt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationEvidenceProjectionTest {

  @Test
  void projectsFrozenContractRouteUsageAndVerificationReceipt() throws Exception {
    UUID attemptId = UUID.randomUUID();
    UUID receiptId = UUID.randomUUID();
    ObjectMapper objectMapper = new ObjectMapper();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    TaskAcceptanceContract contract =
        new TaskAcceptanceContract(
            2,
            "T1",
            0,
            "FAILURE_FIRST",
            "REQUIRED",
            "REQUIRED",
            List.of("src/todo.ts"),
            "NODE",
            "NODE",
            new TaskAcceptanceContract.ResolvedCommand("npm run build", 300, "SERVER", "npm"),
            new TaskAcceptanceContract.ResolvedCommand("npm test", 300, "SERVER", "node-test"),
            List.of("src/todo.ts"),
            List.of("todo"),
            List.of(),
            true,
            List.of("."),
            List.of(new TaskAcceptanceContract.AcceptanceCheck("INITIAL_FAILURE", "todo", false)),
            List.of(new TaskAcceptanceContract.AcceptanceCheck("TESTS_GREEN", "npm test", false)),
            "c".repeat(64));
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId("run-1")
            .taskId("T1")
            .taskIndex(0)
            .attemptNumber(1)
            .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
            .state(WorkspaceGenerationState.ACCEPTED)
            .beforeGeneration("a".repeat(64))
            .candidateGeneration("b".repeat(64))
            .taskContractDigest(contract.digest())
            .taskContractJson(objectMapper.writeValueAsString(contract))
            .requestedProvider("openai")
            .requestedModel("gpt-5.4-mini")
            .effectiveProvider("openai")
            .effectiveModel("gpt-5.4")
            .workerIterations(7)
            .workerToolCalls(12)
            .modelCalls(8L)
            .inputTokens(1_000L)
            .outputTokens(250L)
            .cacheReadTokens(400L)
            .cacheWriteTokens(50L)
            .reasoningTokens(75L)
            .costUsd(new BigDecimal("0.12340000"))
            .createdAt(Instant.parse("2026-08-10T00:00:00Z"))
            .resolvedAt(Instant.parse("2026-08-10T00:01:00Z"))
            .build();
    WorkspaceGenerationVerificationReceipt receipt =
        WorkspaceGenerationVerificationReceipt.builder()
            .id(receiptId)
            .attemptId(attemptId)
            .outcome("PASSED")
            .buildPassed(true)
            .testsExecuted(true)
            .testsPassed(2)
            .testsFailed(0)
            .evidenceDigest("e".repeat(64))
            .build();
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("run-1")).thenReturn(List.of(attempt));
    when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId))
        .thenReturn(Optional.of(receipt));

    RunDetail.WorkspaceGenerationAttemptEvidence evidence =
        new WorkspaceGenerationEvidenceProjection(attempts, receipts, objectMapper)
            .forRun("run-1")
            .getFirst();

    assertThat(evidence.taskContractDigest()).isEqualTo(contract.digest());
    assertThat(evidence.runtimeSurface()).isEqualTo("NODE");
    assertThat(evidence.buildCommand()).isEqualTo("npm run build");
    assertThat(evidence.testCommand()).isEqualTo("npm test");
    assertThat(evidence.acceptanceChecks()).hasSize(2);
    assertThat(evidence.requestedModel()).isEqualTo("gpt-5.4-mini");
    assertThat(evidence.effectiveModel()).isEqualTo("gpt-5.4");
    assertThat(evidence.workerIterations()).isEqualTo(7);
    assertThat(evidence.modelCalls()).isEqualTo(8L);
    assertThat(evidence.costUsd()).isEqualByComparingTo("0.12340000");
    assertThat(evidence.verificationReceiptId()).isEqualTo(receiptId.toString());
    assertThat(evidence.verificationOutcome()).isEqualTo("PASSED");
    assertThat(evidence.verificationTestsPassed()).isEqualTo(2);
  }
}
