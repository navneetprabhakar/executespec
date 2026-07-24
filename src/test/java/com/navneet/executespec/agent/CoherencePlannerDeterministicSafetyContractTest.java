package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CoherencePlannerDeterministicSafetyContractTest {

  @Test
  void reservationPlanGetsProviderNeutralTrustDomainAndMigrationContracts() {
    Plan plan =
        Plan.builder()
            .planTitle("Reservation service")
            .planSummary("FastAPI reservation service")
            .projectType("python")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .requirements(
                List.of(
                    requirement("R1", "Products have non-negative stock."),
                    requirement(
                        "R2",
                        "POST /reservations uses an Idempotency-Key scoped to the authenticated"
                            + " customer."),
                    requirement("R3", "Include schema migrations.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Models and Alembic",
                        "Set up Alembic and a model smoke test.",
                        List.of("app/database.py", "alembic/env.py", "tests/test_models.py")),
                    task(
                        "T2",
                        "Reservation service",
                        "Implement create_reservation(customer_id, product_id, quantity) with"
                            + " atomic stock decrement and get_reservation/cancel_reservation.",
                        List.of(
                            "app/services/reservation_service.py",
                            "tests/test_reservation_service.py")),
                    task(
                        "T3",
                        "Routes and app factory",
                        "Implement POST /reservations, GET /reservations/{id}, and cancellation"
                            + " routes.",
                        List.of("app/main.py", "app/routes.py", "tests/test_routes.py")),
                    task(
                        "T4",
                        "Concurrency tests",
                        "Use pytest and TestClient for stock and replay behavior.",
                        List.of("tests/test_concurrency.py")),
                    task("T5", "OpenAPI", "Generate openapi.yaml.", List.of("openapi.yaml")),
                    task("T6", "README", "Document running the server.", List.of("README.md"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.contractsApplied()).isEqualTo(13);
    assertThat(constraints(result, "T2"))
        .anyMatch(value -> value.contains("verified credential"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("cross-principal owned-resource"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"));
    assertThat(constraints(result, "T3"))
        .anyMatch(value -> value.contains("verified credential"))
        .anyMatch(value -> value.contains("quantity <= 0"))
        .anyMatch(value -> value.contains("default startup path"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"));
    assertThat(constraints(result, "T1"))
        .anyMatch(value -> value.contains("default startup path"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"));
    assertThat(constraints(result, "T4"))
        .anyMatch(value -> value.contains("Test fixtures must apply the configured migration"));
    assertThat(constraints(result, "T6")).anyMatch(value -> value.contains("default startup path"));
    assertThat(constraints(result, "T5")).isEmpty();
  }

  @Test
  void unrelatedPlanIsUnchanged() {
    Plan plan =
        Plan.builder()
            .planTitle("CLI")
            .requirements(List.of(requirement("R1", "Print the requested value.")))
            .tasks(List.of(task("T1", "CLI", "Print a value.", List.of("main.py"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(result.contractsApplied()).isZero();
    assertThat(result.plan()).isSameAs(plan);
  }

  @Test
  void authenticatedNonReservationPlanGetsGenericOwnedResourceContract() {
    Plan plan =
        Plan.builder()
            .planTitle("Blog")
            .requirements(
                List.of(requirement("R1", "Authenticated users can update their own posts.")))
            .tasks(
                List.of(
                    task(
                        "T1",
                        "Post routes",
                        "Implement authenticated post updates.",
                        List.of("app/routes.py", "tests/test_routes.py"))))
            .build();

    PlanSafetyContractEnricher.Result result = PlanSafetyContractEnricher.enrich(plan);

    assertThat(constraints(result, "T1"))
        .anyMatch(value -> value.contains("principal identity from a verified credential"))
        .anyMatch(value -> value.contains("cross-principal owned-resource"))
        .noneMatch(value -> value.toLowerCase().contains("reservation"));
  }

  private static RequirementSpec requirement(String id, String text) {
    return new RequirementSpec(id, text, RequirementSpec.RequirementKind.FUNCTIONAL);
  }

  private static PlannedTask task(
      String id, String title, String description, List<String> touchesFiles) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(description)
        .touchesFiles(touchesFiles)
        .hardConstraints(List.of())
        .build();
  }

  private static List<String> constraints(PlanSafetyContractEnricher.Result result, String taskId) {
    return result.plan().tasks().stream()
        .filter(task -> task.id().equals(taskId))
        .findFirst()
        .orElseThrow()
        .hardConstraints();
  }
}
