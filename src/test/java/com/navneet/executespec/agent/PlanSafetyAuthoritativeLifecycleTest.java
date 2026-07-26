package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanSafetyAuthoritativeLifecycleTest {
  @Test
  void authoritativeOrderLifecycleOverridesLegacyReservationDefaults() {
    PlannedTask orderTask =
        PlannedTask.builder()
            .id("T4")
            .title("Implement order model and migration")
            .description(
                "Create the Order model. Order status (string: RESERVED | CANCELLED, default"
                    + " RESERVED).")
            .touchesFiles(
                List.of("backend/app/models/order.py", "backend/alembic/versions/002_orders.py"))
            .dependsOn(List.of())
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .build();
    Plan plan = Plan.builder().tasks(List.of(orderTask)).requirements(List.of()).build();

    PlannedTask enriched =
        PlanSafetyContractEnricher.enrich(
                plan,
                "Orders have DRAFT, RESERVED, COMMITTED, and CANCELLED states. Reserve an order,"
                    + " commit a RESERVED order, and cancel a RESERVED order.")
            .plan()
            .tasks()
            .getFirst();

    assertThat(enriched.description())
        .contains("string: DRAFT | RESERVED | COMMITTED | CANCELLED, default DRAFT");
    assertThat(enriched.hardConstraints())
        .contains(
            "Order.status lifecycle values are exactly DRAFT, RESERVED, COMMITTED, CANCELLED;"
                + " every producer, consumer, UI, and test must use those exact values.");
  }

  @Test
  void requiredIdempotencyKeyCannotRemainOptionalInTypedClient() {
    String normalized =
        PlanSafetyTextSupport.rewriteIdempotencyHeaderTransport(
            "reserveOrder must accept an optional `idempotencyKey: string` parameter and also"
                + " exposes idempotencyKey?: string.");

    assertThat(normalized)
        .contains("required `idempotencyKey: string` parameter")
        .contains("idempotencyKey: string")
        .doesNotContain("optional", "idempotencyKey?");
  }

  @Test
  void canonicalizesOrderAndReservationLifecycleAliasesInDownstreamTestContracts() {
    PlannedTask schema =
        PlannedTask.builder()
            .id("T2")
            .title("Persist order and reservation aggregates")
            .description(
                "Create the Order model with Order.status PENDING or RESERVED. Persist one"
                    + " Reservation parent with Reservation.status ACTIVE or RELEASED and"
                    + " ReservationLine children.")
            .touchesFiles(
                List.of(
                    "backend/app/models/order.py",
                    "backend/app/models/reservation.py",
                    "backend/alembic/versions/002_orders.py"))
            .dependsOn(List.of())
            .hardConstraints(
                List.of(
                    "Order.status lifecycle values are exactly PENDING and RESERVED.",
                    "Reservation.status lifecycle is exactly ACTIVE and RELEASED."))
            .meetsRequirements(List.of("R1"))
            .build();
    PlannedTask acceptance =
        PlannedTask.builder()
            .id("T8")
            .title("Verify reservation lifecycle")
            .description(
                "Assert Order.state becomes RESERVED and Reservation.state becomes RELEASED."
                    + " Leave Job.state unchanged because it is a different aggregate.")
            .touchesFiles(List.of("backend/tests/test_reservations.py"))
            .dependsOn(List.of("T2"))
            .hardConstraints(
                List.of("The public acceptance assertions read Order.state and Reservation.state."))
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .tasks(List.of(schema, acceptance))
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Persist orders, reservation lines, and reservation lifecycle; release or"
                            + " cancel a reservation exactly once.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .build();

    Plan enriched = PlanSafetyContractEnricher.enrich(plan).plan();
    PlannedTask normalized = enriched.tasks().get(1);

    assertThat(normalized.description())
        .contains("Order.status", "Reservation.status", "Job.state")
        .doesNotContain("Order.state", "Reservation.state");
    assertThat(normalized.hardConstraints())
        .anyMatch(
            constraint ->
                constraint.contains("Order.status")
                    && constraint.contains("Reservation.status")
                    && !constraint.contains("Order.state")
                    && !constraint.contains("Reservation.state"));
    assertThat(PlanContractQualityGate.violations(enriched))
        .noneMatch(value -> value.contains("lifecycle alternates between persisted fields"));
  }
}
