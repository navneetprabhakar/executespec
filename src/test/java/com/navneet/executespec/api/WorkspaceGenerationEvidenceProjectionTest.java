package com.navneet.executespec.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.workspacegeneration.TaskAcceptanceContract;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttempt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationAttemptRepository;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationKind;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationState;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceipt;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationVerificationReceiptRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class WorkspaceGenerationEvidenceProjectionTest {

  @Test
  void malformedOrAbsentHistoricalJsonDegradesToEmptyTypedEvidence() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId("historical-run")
            .taskId("legacy-task")
            .taskIndex(0)
            .attemptNumber(1)
            .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .taskContractJson("{not-json")
            .observedInitialFailureEvidenceJson("[]")
            .createdAt(Instant.EPOCH)
            .build();
    WorkspaceGenerationVerificationReceipt receipt =
        WorkspaceGenerationVerificationReceipt.builder()
            .id(UUID.randomUUID())
            .attemptId(attemptId)
            .outcome("FAILED")
            .evidenceJson("{malformed")
            .build();
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("historical-run"))
        .thenReturn(List.of(attempt));
    when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId))
        .thenReturn(Optional.of(receipt));

    RunDetail.WorkspaceGenerationAttemptEvidence evidence =
        new WorkspaceGenerationEvidenceProjection(attempts, receipts, new ObjectMapper())
            .forRun("historical-run")
            .getFirst();

    assertThat(evidence.initialFailureEvidence()).isNull();
    assertThat(evidence.runtimeSurface()).isNull();
    assertThat(evidence.acceptanceChecks()).isEmpty();
    assertThat(evidence.verificationOutcome()).isEqualTo("FAILED");
    assertThat(evidence.acceptedGeneration()).isNull();
  }

  @Test
  void projectsRetryableAndExhaustedRecoveryAsDistinctDurableApiStates() {
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    Instant now = Instant.parse("2026-08-13T10:00:00Z");
    WorkspaceGenerationAttempt retrying =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId("recovery-api")
            .taskId("T1")
            .taskIndex(0)
            .attemptNumber(1)
            .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
            .state(WorkspaceGenerationState.RECOVERY_FAILED)
            .recoveryAttemptCount(2)
            .recoveryNextAttemptAt(now.plusSeconds(60))
            .recoverySupportId(UUID.randomUUID())
            .createdAt(now)
            .build();
    WorkspaceGenerationAttempt exhausted =
        WorkspaceGenerationAttempt.builder()
            .id(UUID.randomUUID())
            .runId("recovery-api")
            .taskId("T2")
            .taskIndex(1)
            .attemptNumber(1)
            .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
            .state(WorkspaceGenerationState.RECOVERY_FAILED)
            .recoveryAttemptCount(3)
            .recoverySupportId(UUID.randomUUID())
            .recoveryExhaustedAt(now)
            .resolvedAt(now)
            .createdAt(now)
            .build();
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("recovery-api"))
        .thenReturn(List.of(exhausted, retrying));

    List<RunDetail.WorkspaceGenerationAttemptEvidence> evidence =
        new WorkspaceGenerationEvidenceProjection(attempts).forRun("recovery-api");

    assertThat(evidence.get(0).recoveryStatus()).isEqualTo("EXHAUSTED");
    assertThat(evidence.get(0).recoveryUserActionRequired()).isTrue();
    assertThat(evidence.get(1).recoveryStatus()).isEqualTo("RETRY_SCHEDULED");
    assertThat(evidence.get(1).recoveryUserActionRequired()).isFalse();
  }

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
            List.of(
                new TaskAcceptanceContract.AcceptanceCheck(
                    "INITIAL_FAILURE", "secret endpoint", true)),
            List.of(
                new TaskAcceptanceContract.AcceptanceCheck("TESTS_GREEN", "npm test", false),
                new TaskAcceptanceContract.AcceptanceCheck(
                    "VISIBLE_FAILED_TARGET", "todo creates an item", false)),
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
            .observedInitialFailureEvidenceJson(
                "{\"outcome\":\"FAILURE_OBSERVED\",\"code\":\"INITIAL_TESTS_FAILED\","
                    + "\"detail\":\"one test failed\",\"command\":\"npm test\","
                    + "\"failedTargets\":[\"todo creates an item\",\"secret endpoint\"]}")
            .initialFailureObservedAt(Instant.parse("2026-08-10T00:00:05Z"))
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
            .failureCategory(null)
            .environmentCode(null)
            .executionBoundary("DISPOSABLE_EXACT_GENERATION")
            .evidenceJson(
                "{\"acceptanceCheckResults\":[{\"kind\":\"TESTS_GREEN\","
                    + "\"status\":\"PASSED\",\"detail\":\"tests passed\"}]}")
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
    assertThat(evidence.priorAcceptedGeneration()).isEqualTo("a".repeat(64));
    assertThat(evidence.acceptedGeneration()).isEqualTo("b".repeat(64));
    assertThat(evidence.initialFailureEvidence().outcome()).isEqualTo("FAILURE_OBSERVED");
    assertThat(evidence.initialFailureEvidence().code()).isEqualTo("INITIAL_TESTS_FAILED");
    assertThat(evidence.initialFailureEvidence().detail())
        .isEqualTo("The frozen test command failed before generation.");
    assertThat(evidence.initialFailureEvidence().failedTargets())
        .containsExactly("todo creates an item");
    assertThat(evidence.runtimeSurface()).isEqualTo("NODE");
    assertThat(evidence.buildCommand()).isEqualTo("npm run build");
    assertThat(evidence.testCommand()).isEqualTo("npm test");
    assertThat(evidence.acceptanceChecks()).hasSize(3);
    assertThat(evidence.verificationFailureCategory()).isNull();
    assertThat(evidence.verificationEnvironmentCode()).isNull();
    assertThat(evidence.verificationExecutionBoundary()).isEqualTo("DISPOSABLE_EXACT_GENERATION");
    assertThat(evidence.acceptanceChecks())
        .anySatisfy(
            check -> {
              assertThat(check.kind()).isEqualTo("INITIAL_FAILURE");
              assertThat(check.target()).isEqualTo("hidden");
              assertThat(check.hidden()).isTrue();
              assertThat(check.detail()).isNull();
            });
    assertThat(evidence.acceptanceChecks())
        .anySatisfy(
            check -> {
              assertThat(check.kind()).isEqualTo("TESTS_GREEN");
              assertThat(check.outcome()).isEqualTo("PASSED");
              assertThat(check.detail()).isEqualTo("tests passed");
            });
    assertThat(evidence.requestedModel()).isEqualTo("gpt-5.4-mini");
    assertThat(evidence.effectiveModel()).isEqualTo("gpt-5.4");
    assertThat(evidence.workerIterations()).isEqualTo(7);
    assertThat(evidence.modelCalls()).isEqualTo(8L);
    assertThat(evidence.costUsd()).isEqualByComparingTo("0.12340000");
    assertThat(evidence.verificationReceiptId()).isEqualTo(receiptId.toString());
    assertThat(evidence.verificationOutcome()).isEqualTo("PASSED");
    assertThat(evidence.verificationTestsPassed()).isEqualTo(2);
    assertThat(evidence.failureProjection()).isNull();
  }

  @Test
  void projectsEnvironmentFailureCausalityIntoTheRunDetailDto() {
    UUID attemptId = UUID.randomUUID();
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId("environment-run")
            .taskId("T1")
            .taskIndex(0)
            .attemptNumber(1)
            .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
            .state(WorkspaceGenerationState.ROLLED_BACK)
            .beforeGeneration("a".repeat(64))
            .candidateGeneration("b".repeat(64))
            .failureCode("TASK_VERIFICATION_REJECTED")
            .createdAt(Instant.EPOCH)
            .build();
    WorkspaceGenerationVerificationReceipt receipt =
        WorkspaceGenerationVerificationReceipt.builder()
            .id(UUID.randomUUID())
            .attemptId(attemptId)
            .outcome("UNAVAILABLE")
            .failureCategory("ENVIRONMENT")
            .environmentCode("BUILD_POLICY_DENIED")
            .executionBoundary("DISPOSABLE_EXACT_GENERATION")
            .build();
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc("environment-run"))
        .thenReturn(List.of(attempt));
    when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId))
        .thenReturn(Optional.of(receipt));

    RunDetail.WorkspaceGenerationAttemptEvidence evidence =
        new WorkspaceGenerationEvidenceProjection(attempts, receipts, new ObjectMapper())
            .forRun("environment-run")
            .getFirst();

    assertThat(evidence.verificationFailureCategory()).isEqualTo("ENVIRONMENT");
    assertThat(evidence.verificationEnvironmentCode()).isEqualTo("BUILD_POLICY_DENIED");
    assertThat(evidence.failureProjection().category())
        .isEqualTo(
            com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection
                .Category.VERIFICATION_ENVIRONMENT_UNAVAILABLE);
    assertThat(evidence.failureProjection().recommendedAction())
        .isEqualTo(
            com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection
                .RecommendedAction.RETRY_AFTER_ENVIRONMENT_RECOVERY);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("customerFailureFixtures")
  void everyCustomerFailureFixtureIsProjectedIntoTheApiDto(
      String name,
      WorkspaceGenerationState state,
      boolean candidatePresent,
      String failureCode,
      String recoveryFailureCode,
      String receiptOutcome,
      String receiptCategory,
      com.navneet.executespec.workspacegeneration.WorkspaceGenerationFailureProjection.Category
          expectedCategory) {
    UUID attemptId = UUID.randomUUID();
    String runId = "fixture-" + name;
    WorkspaceGenerationAttemptRepository attempts =
        mock(WorkspaceGenerationAttemptRepository.class);
    WorkspaceGenerationVerificationReceiptRepository receipts =
        mock(WorkspaceGenerationVerificationReceiptRepository.class);
    WorkspaceGenerationAttempt attempt =
        WorkspaceGenerationAttempt.builder()
            .id(attemptId)
            .runId(runId)
            .taskId("T1")
            .taskIndex(0)
            .attemptNumber(1)
            .attemptKind(WorkspaceGenerationKind.BUILD_TASK)
            .state(state)
            .beforeGeneration("a".repeat(64))
            .candidateGeneration(candidatePresent ? "b".repeat(64) : null)
            .failureCode(failureCode)
            .recoveryFailureCode(recoveryFailureCode)
            .createdAt(Instant.EPOCH)
            .build();
    when(attempts.findTop20ByRunIdOrderByCreatedAtDesc(runId)).thenReturn(List.of(attempt));
    if (receiptOutcome == null) {
      when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId))
          .thenReturn(Optional.empty());
    } else {
      WorkspaceGenerationVerificationReceipt receipt =
          WorkspaceGenerationVerificationReceipt.builder()
              .id(UUID.randomUUID())
              .attemptId(attemptId)
              .outcome(receiptOutcome)
              .failureCategory(receiptCategory)
              .executionBoundary("DISPOSABLE_EXACT_GENERATION")
              .build();
      when(receipts.findFirstByAttemptIdOrderByCreatedAtDesc(attemptId))
          .thenReturn(Optional.of(receipt));
    }

    RunDetail.WorkspaceGenerationAttemptEvidence evidence =
        new WorkspaceGenerationEvidenceProjection(attempts, receipts, new ObjectMapper())
            .forRun(runId)
            .getFirst();

    assertThat(evidence.failureProjection()).isNotNull();
    assertThat(evidence.failureProjection().category()).isEqualTo(expectedCategory);
    assertThat(evidence.failureProjection().candidatePresent()).isEqualTo(candidatePresent);
    assertThat(evidence.failureProjection().publicationBlocked()).isTrue();
  }

  private static Stream<Arguments> customerFailureFixtures() {
    return Stream.of(
        Arguments.of(
            "generated-rejected-restored",
            WorkspaceGenerationState.ROLLED_BACK,
            true,
            "TASK_VERIFICATION_REJECTED",
            null,
            "FAILED",
            "PRODUCT",
            WorkspaceGenerationFailureProjection.Category.ROLLBACK_PROVEN),
        Arguments.of(
            "rollback-unproven",
            WorkspaceGenerationState.RECOVERY_FAILED,
            true,
            "TASK_VERIFICATION_REJECTED",
            "ROLLBACK_PROOF_FAILED",
            "FAILED",
            "PRODUCT",
            WorkspaceGenerationFailureProjection.Category.ROLLBACK_UNPROVEN),
        Arguments.of(
            "no-candidate",
            WorkspaceGenerationState.ADMITTED,
            false,
            "PROVIDER_DISPATCH_FAILED",
            null,
            null,
            null,
            WorkspaceGenerationFailureProjection.Category.NO_CANDIDATE),
        Arguments.of(
            "environment-unavailable",
            WorkspaceGenerationState.ROLLED_BACK,
            true,
            "TASK_VERIFICATION_REJECTED",
            null,
            "UNAVAILABLE",
            "ENVIRONMENT",
            WorkspaceGenerationFailureProjection.Category.VERIFICATION_ENVIRONMENT_UNAVAILABLE),
        Arguments.of(
            "checkpoint-unacknowledged",
            WorkspaceGenerationState.ROLLED_BACK,
            true,
            "CHECKPOINT_UPLOAD_UNACKNOWLEDGED",
            null,
            null,
            null,
            WorkspaceGenerationFailureProjection.Category.CHECKPOINT_FAILED),
        Arguments.of(
            "frontier-cas-conflict",
            WorkspaceGenerationState.VERIFYING,
            true,
            "FRONTIER_CAS_CONFLICT",
            null,
            null,
            null,
            WorkspaceGenerationFailureProjection.Category.FRONTIER_CONFLICT),
        Arguments.of(
            "terminal-operation-conflict",
            WorkspaceGenerationState.VERIFYING,
            true,
            "TERMINAL_OPERATION_CONFLICT",
            null,
            null,
            null,
            WorkspaceGenerationFailureProjection.Category.INTERNAL_PROTOCOL_CONFLICT));
  }
}
