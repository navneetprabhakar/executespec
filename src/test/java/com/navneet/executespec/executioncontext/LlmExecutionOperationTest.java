package com.navneet.executespec.executioncontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LlmExecutionOperationTest {

  private static final Instant START = Instant.parse("2026-07-28T00:00:00Z");
  private static final LlmExecutionOperationDescriptor DESCRIPTOR =
      LlmExecutionOperationDescriptor.of("worker/task-1", "WORKER", "run-1|task-1|plan-sha", 3);

  @Test
  void activeAttemptCannotBeStolenAndExpiredAttemptWaitsForExternalAttemptHorizon() {
    LlmExecutionOperation operation = operation();

    assertThat(operation.claim("owner-a", START, Duration.ofSeconds(30)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.CLAIMED);
    int firstAttempt = operation.getAttempt();
    UUID firstToken = operation.getLeaseToken();
    assertThat(
            operation.protectUntil(
                "owner-a", firstToken, firstAttempt, START, START.plusSeconds(90)))
        .isTrue();

    assertThat(operation.claim("owner-b", START.plusSeconds(10), Duration.ofSeconds(30)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.REJECTED);
    assertThat(operation.claim("owner-b", START.plusSeconds(31), Duration.ofSeconds(30)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.REJECTED);
    assertThat(operation.claim("owner-b", START.plusSeconds(91), Duration.ofSeconds(30)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.CLAIMED);

    assertThat(operation.getAttempt()).isEqualTo(2);
    assertThat(operation.getLeaseToken()).isNotEqualTo(firstToken);
    assertThat(
            operation.heartbeat(
                "owner-a", firstToken, firstAttempt, START.plusSeconds(92), Duration.ofSeconds(30)))
        .isFalse();
    assertThat(operation.complete("owner-a", firstToken, firstAttempt, START.plusSeconds(92)))
        .isFalse();
  }

  @Test
  void ownerStringAloneNeverRedisclosesAnActiveClaimToken() {
    LlmExecutionOperation operation = operation();
    operation.claim("unique-owner", START, Duration.ofMinutes(1));
    UUID token = operation.getLeaseToken();

    assertThat(operation.claim("unique-owner", START.plusSeconds(5), Duration.ofMinutes(1)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.REJECTED);
    assertThat(operation.getAttempt()).isEqualTo(1);
    assertThat(operation.getLeaseToken()).isEqualTo(token);
  }

  @Test
  void cancellationSurvivesOwnerDeathAndTerminalizesOnlyAfterBothSafetyHorizons() {
    LlmExecutionOperation operation = operation();
    operation.claim("owner-a", START, Duration.ofSeconds(30));
    UUID token = operation.getLeaseToken();
    operation.protectUntil("owner-a", token, 1, START, START.plusSeconds(90));

    assertThat(operation.requestCancellation(START.plusSeconds(5)))
        .isEqualTo(LlmExecutionOperation.Status.CANCEL_REQUESTED);
    assertThat(operation.requestCancellation(START.plusSeconds(31)))
        .isEqualTo(LlmExecutionOperation.Status.CANCEL_REQUESTED);
    assertThat(operation.requestCancellation(START.plusSeconds(91)))
        .isEqualTo(LlmExecutionOperation.Status.CANCELLED);
    assertThat(operation.getCompletedAt()).isEqualTo(START.plusSeconds(91));
    assertThat(operation.getLeaseOwner()).isNull();
    assertThat(operation.getLeaseToken()).isNull();
  }

  @Test
  void activeOwnerCanAcknowledgeCancellationButStaleTupleCannot() {
    LlmExecutionOperation operation = operation();
    operation.claim("owner-a", START, Duration.ofMinutes(1));
    UUID token = operation.getLeaseToken();
    operation.requestCancellation(START.plusSeconds(1));

    assertThat(
            operation.acknowledgeCancellation(
                "owner-a", UUID.randomUUID(), 1, START.plusSeconds(2)))
        .isFalse();
    assertThat(operation.acknowledgeCancellation("owner-a", token, 2, START.plusSeconds(2)))
        .isFalse();
    assertThat(operation.acknowledgeCancellation("owner-a", token, 1, START.plusSeconds(2)))
        .isTrue();
    assertThat(operation.getStatus()).isEqualTo(LlmExecutionOperation.Status.CANCELLED);
  }

  @Test
  void retryableFailureRequeuesButAttemptBudgetEventuallyTerminalizes() {
    LlmExecutionOperation operation = operation();
    operation.claim("owner-a", START, Duration.ofMinutes(1));
    UUID first = operation.getLeaseToken();
    assertThat(
            operation.fail(
                "owner-a", first, 1, START.plusSeconds(1), "TIMEOUT", "timeout", true, null))
        .isTrue();
    assertThat(operation.getStatus()).isEqualTo(LlmExecutionOperation.Status.QUEUED);

    operation.claim("owner-b", START.plusSeconds(2), Duration.ofMinutes(1));
    UUID second = operation.getLeaseToken();
    operation.fail("owner-b", second, 2, START.plusSeconds(3), "TIMEOUT", "timeout", true, null);
    operation.claim("owner-c", START.plusSeconds(4), Duration.ofMinutes(1));
    UUID third = operation.getLeaseToken();
    operation.fail("owner-c", third, 3, START.plusSeconds(5), "TIMEOUT", "timeout", true, null);

    assertThat(operation.getStatus()).isEqualTo(LlmExecutionOperation.Status.FAILED);
    assertThat(operation.getAttempt()).isEqualTo(3);
  }

  @Test
  void retryableFailureCannotBypassAnAdmittedExternalAttemptHorizon() {
    LlmExecutionOperation operation = operation();
    operation.claim("owner-a", START, Duration.ofMinutes(1));
    UUID token = operation.getLeaseToken();
    operation.protectUntil("owner-a", token, 1, START, START.plusSeconds(90));
    operation.fail("owner-a", token, 1, START.plusSeconds(1), "TIMEOUT", "timeout", true, null);

    assertThat(operation.claim("owner-b", START.plusSeconds(2), Duration.ofMinutes(1)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.REJECTED);
    assertThat(operation.claim("owner-b", START.plusSeconds(91), Duration.ofMinutes(1)))
        .isEqualTo(LlmExecutionOperation.ClaimOutcome.CLAIMED);
  }

  private static LlmExecutionOperation operation() {
    return LlmExecutionOperation.queued("llmop_test", "llmctx_test", DESCRIPTOR, 1, START);
  }
}
