package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SourceRequirementEvidenceTest {

  @Test
  void selectsWholeOriginalClausesForAssignedRequirementsOnly() {
    String source =
        """
        Build a warehouse inventory API.
        - A failed multi-line transfer must roll back every stock mutation and audit insert.
        - GET /api/products returns product identifiers and names.
        - The dashboard uses a responsive two-column layout.
        """;
    PlannedTask backend =
        PlannedTask.builder()
            .id("T2")
            .title("Implement backend")
            .meetsRequirements(List.of("R2", "R3"))
            .build();
    PlannedTask frontend =
        PlannedTask.builder()
            .id("T3")
            .title("Implement frontend")
            .meetsRequirements(List.of("R4"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R2",
                        "A failed multi-line transfer must roll back every stock mutation and audit"
                            + " insert.",
                        RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R3",
                        "GET /api/products returns product identifiers and names.",
                        RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R4",
                        "Dashboard has a responsive two-column layout.",
                        RequirementSpec.RequirementKind.FUNCTIONAL)))
            .tasks(List.of(backend, frontend))
            .build();

    String selected = SourceRequirementEvidence.taskScopedClauses(source, plan, backend);

    assertThat(selected)
        .containsOnlyOnce(
            "- A failed multi-line transfer must roll back every stock mutation and audit insert.")
        .containsOnlyOnce("- GET /api/products returns product identifiers and names.")
        .doesNotContain("responsive two-column layout");
  }

  @Test
  void fallsBackToCompleteSourceWhenAnyAssignedRequirementCannotBeMapped() {
    String source = "Build the service.\nPreserve an exact rollback contract.";
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Implement service")
            .meetsRequirements(List.of("R1", "R2"))
            .build();
    Plan plan =
        Plan.builder()
            .requirements(
                List.of(
                    new RequirementSpec(
                        "R1",
                        "Preserve exact rollback.",
                        RequirementSpec.RequirementKind.FUNCTIONAL),
                    new RequirementSpec(
                        "R2",
                        "Use an unrelated opaque requirement vocabulary.",
                        RequirementSpec.RequirementKind.CONSTRAINT)))
            .tasks(List.of(task))
            .build();

    assertThat(SourceRequirementEvidence.taskScopedClauses(source, plan, task)).isEqualTo(source);
  }
}
