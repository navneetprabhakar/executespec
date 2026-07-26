package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlanTaskOwnershipValidatorTest {

  @Test
  void rejectsScaffoldThatPreCreatesProductionModulesOwnedByLaterTasks() {
    PlannedTask scaffold =
        task("T1", "Create project scaffold", "app/models.py", "app/__init__.py");
    PlannedTask implementation = task("T2", "Implement data model", "app/models.py");

    assertThat(PlanTaskOwnershipValidator.validate(List.of(scaffold, implementation)))
        .singleElement()
        .asString()
        .contains("app/models.py", "one primary owner");
  }

  @Test
  void allowsScaffoldManifestsAndEmptyPackageMarkers() {
    PlannedTask scaffold =
        task("T1", "Bootstrap project", "pyproject.toml", "app/__init__.py", "alembic.ini");
    PlannedTask implementation = task("T2", "Implement API", "app/main.py");

    assertThat(PlanTaskOwnershipValidator.validate(List.of(scaffold, implementation))).isEmpty();
  }

  private static PlannedTask task(String id, String title, String... files) {
    return PlannedTask.builder()
        .id(id)
        .title(title)
        .description(title)
        .complexity(PlannedTask.Complexity.MEDIUM)
        .touchesFiles(List.of(files))
        .dependsOn(List.of())
        .hardConstraints(List.of())
        .meetsRequirements(List.of())
        .build();
  }
}
