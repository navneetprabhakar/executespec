package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PlanApiSyntaxInventoryGateTest {

  @Test
  void rejectsUnclosedRouteCodeSpanInsideInventory() {
    String description =
        """
        Register the router prefix %c/api/v1%c.

        **Routes inventory:**
        - %cGET /api/audit — list audit events
        - %cGET /api/v1/products%c — list products

        **Schemas**
        Define response schemas.
        """
            .formatted(96, 96, 96, 96, 96);

    assertThat(violations(description))
        .anyMatch(
            value -> value.contains("malformed Markdown route inventory entry GET /api/audit"));
  }

  @Test
  void rejectsDuplicateExactOperationInsideOneInventory() {
    String description =
        """
        **Routes inventory:**
        - %cGET /api/v1/products%c — list products
        - %cPOST /api/v1/products%c — create product
        - %cGET /api/v1/products%c — accidental duplicate
        """
            .formatted(96, 96, 96, 96, 96, 96);

    assertThat(violations(description))
        .contains("T1: duplicate route inventory declaration GET /api/v1/products");
  }

  @Test
  void rejectsEntriesOutsideAnExplicitVersionedProducerPrefix() {
    String description =
        """
        Register the router prefix %c/api/v1%c.

        **Routes inventory:**
        - %cGET /api/v1/products%c — list products
        - %cGET /api/audit%c — drifted product route
        """
            .formatted(96, 96, 96, 96, 96, 96);

    assertThat(violations(description))
        .contains("T1: route namespace drift GET /api/audit; declared router prefix is /api/v1");
  }

  @Test
  void ignoresFrameworkRoutesOutsidePrefixAndRepeatedExplanatoryMentions() {
    String description =
        """
        Register the router prefix %c/api/v1%c.

        **Routes inventory:**
        - %cGET /api/v1/products%c — list products
        - %cGET /api/health%c — framework readiness
        - %cGET /api/docs%c — framework documentation
        - %cGET /api/openapi.json%c — framework schema

        **Client notes**
        Call GET /api/v1/products, then mention GET /api/v1/products again while explaining retry.
        """
            .formatted(96, 96, 96, 96, 96, 96, 96, 96, 96, 96);

    assertThat(violations(description)).isEmpty();
  }

  @Test
  void ordinaryRepeatedRouteMentionsWithoutAnInventoryRemainValid() {
    assertThat(
            violations(
                "Implement GET /api/v1/products. Test GET /api/v1/products through the public"
                    + " server."))
        .isEmpty();
  }

  @Test
  void duplicateDeclarationsInSeparateInventoriesAreScopedIndependently() {
    String description =
        """
        **Routes inventory:**
        - %cGET /api/v1/products%c — producer

        **Schemas**
        Define schemas.

        **API endpoints to cover:**
        - %cGET /api/v1/products%c — integration coverage
        """
            .formatted(96, 96, 96, 96);

    assertThat(violations(description)).isEmpty();
  }

  private static List<String> violations(String description) {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Implement API")
            .description(description)
            .complexity(PlannedTask.Complexity.LOW)
            .touchesFiles(List.of("backend/app/routes/products.py"))
            .dependsOn(List.of())
            .hardConstraints(List.of())
            .meetsRequirements(List.of())
            .build();
    Plan plan = Plan.builder().tasks(List.of(task)).requirements(List.of()).build();
    List<String> violations = new ArrayList<>();

    PlanApiSyntaxGate.addViolations(plan, violations);

    return violations;
  }
}
