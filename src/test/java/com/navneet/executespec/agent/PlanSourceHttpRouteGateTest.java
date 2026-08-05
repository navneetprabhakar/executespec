package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanSourceHttpRouteGateTest {

  private static final String INCIDENT_REQUEST =
      "Add GET /api/system/ping and prove POST /api/system/ping is rejected with HTTP 405.";

  @Test
  void methodNotAllowedAcceptanceDoesNotBecomeARequiredProducerRoute() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Add GET /api/system/ping endpoint")
                        .description("Return the deterministic response")
                        .build()))
            .build();

    assertThat(PlanSourceHttpRouteGate.violations(INCIDENT_REQUEST, plan)).isEmpty();
  }

  @Test
  void executableProducerCannotContradictMethodNotAllowedAcceptance() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Add GET /api/system/ping and POST /api/system/ping endpoints")
                        .description("Implement both methods")
                        .build()))
            .build();

    assertThat(PlanSourceHttpRouteGate.violations(INCIDENT_REQUEST, plan))
        .containsExactly(
            "SOURCE_HTTP_FORBIDDEN_METHOD_PRESENT: executable producer plan must preserve the "
                + "source's method-not-allowed contract for POST /api/system/ping");
  }
}
