package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TaskExpansionContradictionValidatorTest {
  private static final String CANONICAL_IDEMPOTENCY =
      "Use one exact IdempotencyRecord contract everywhere: key, scope, request_body_hash, state,"
          + " nullable response_status, and nullable response_body; enforce uniqueness on (key,"
          + " scope), and use PROCESSING/COMPLETED states.";

  @Test
  void negatedOrderReplayOwnershipAndOrderScopedKeyAreNotPositiveOwnership() {
    PlannedTask task =
        task(
            "Reserve endpoint scope is \"order:{order_id}:reserve\" and the Idempotency-Key header"
                + " is required.",
            List.of(
                CANONICAL_IDEMPOTENCY,
                "Do not store replay ownership on Order.",
                "On replay, return response_status and response_body from IdempotencyRecord."));

    assertThat(violations(task))
        .doesNotContain(
            "Hard constraints assign replay ownership to Order despite the canonical"
                + " IdempotencyRecord contract");
  }

  @Test
  void separateOrderLifecycleAndIdempotencyRecordLifecycleAreNotConflated() {
    PlannedTask task =
        task(
            "Claim an IdempotencyRecord row with state PROCESSING, then atomically reserve stock,"
                + " set Order.status to RESERVED, write the audit rows, and update the"
                + " IdempotencyRecord to COMPLETED with response_status and response_body."
                + " Order.status lifecycle values are DRAFT, RESERVED, COMMITTED, CANCELLED."
                + " IdempotencyRecord.state values are PROCESSING and COMPLETED.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task))
        .doesNotContain(
            "IdempotencyRecord state machine uses a business state outside"
                + " PROCESSING/COMPLETED");
  }

  @Test
  void reservedInventoryColumnNearIdempotencyRecordIsNotAnEnumState() {
    PlannedTask task =
        task(
            "Claim an IdempotencyRecord with state PROCESSING, then run a guarded UPDATE"
                + " incrementing the reserved column where on_hand - reserved >= quantity, set"
                + " Order.status to RESERVED, and update the IdempotencyRecord to COMPLETED.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task))
        .doesNotContain(
            "IdempotencyRecord state machine uses a business state outside"
                + " PROCESSING/COMPLETED");
  }

  @Test
  void stillRejectsBusinessStateAssignedToIdempotencyRecord() {
    PlannedTask task =
        task(
            "Define IdempotencyRecord with the canonical fields. After storing the response,"
                + " update the IdempotencyRecord state to DRAFT.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task))
        .contains(
            "IdempotencyRecord state machine uses a business state outside"
                + " PROCESSING/COMPLETED");
  }

  @Test
  void stillRejectsPositiveReplayOwnershipOnOrder() {
    PlannedTask task =
        task(
            "Implement idempotent order reservation.",
            List.of(
                CANONICAL_IDEMPOTENCY,
                "Persist idempotency_key and response_body fields on Order for replay"
                    + " ownership."));

    assertThat(violations(task))
        .contains(
            "Hard constraints assign replay ownership to Order despite the canonical"
                + " IdempotencyRecord contract");
  }

  @Test
  void rejectsReplayIndicatorOnFirstExecutionResponse() {
    PlannedTask task =
        task(
            "For a new request, execute the mutation and return the normal response with"
                + " `idempotency_replayed: true`. On replay, return the stored response status and"
                + " stored response body with `idempotency_replayed: true`.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task))
        .containsExactly(IdempotencyResponseSemanticValidator.FIRST_RESPONSE_MARKED);
  }

  @Test
  void rejectsReplayThatDoesNotReturnStoredStatusAndBodyWithIndicator() {
    PlannedTask task =
        task(
            "For the first execution, return the normal response without the"
                + " `Idempotency-Replayed` header. On replay, recompute the response and omit the"
                + " `Idempotency-Replayed` header.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task))
        .containsExactly(IdempotencyResponseSemanticValidator.INCOMPLETE_REPLAY);
  }

  @Test
  void acceptsDistinctFirstExecutionAndStoredReplayResponses() {
    PlannedTask task =
        task(
            "For the first execution, return the normal response without the"
                + " `Idempotency-Replayed` header. On replay, return the stored response status and"
                + " stored response body with `Idempotency-Replayed: true`.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task)).isEmpty();
  }

  @Test
  void genericIdempotencyWithoutAReplayIndicatorContractRemainsValid() {
    PlannedTask task =
        task(
            "For the first execution, perform the mutation once. On replay, return the exact"
                + " stored response status and stored response body.",
            List.of(CANONICAL_IDEMPOTENCY));

    assertThat(violations(task)).isEmpty();
  }

  private static List<String> violations(PlannedTask task) {
    List<String> violations = new ArrayList<>();
    Plan plan = Plan.builder().requirements(List.of()).tasks(List.of(task)).build();
    TaskExpansionContradictionValidator.addViolations(task, plan, violations);
    return violations;
  }

  private static PlannedTask task(String description, List<String> constraints) {
    return PlannedTask.builder()
        .id("T6")
        .title("Implement order lifecycle with idempotent reservation")
        .description(description)
        .hardConstraints(constraints)
        .touchesFiles(List.of("backend/app/services/reservation_service.py"))
        .meetsRequirements(List.of())
        .dependsOn(List.of())
        .build();
  }
}
