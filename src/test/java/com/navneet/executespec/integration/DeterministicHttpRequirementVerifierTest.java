package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeterministicHttpRequirementVerifierTest {

  @TempDir Path workspace;

  private final DeterministicHttpRequirementVerifier verifier =
      new DeterministicHttpRequirementVerifier(new SpringHttpSurfaceExtractor());

  @Test
  void exactRequiredRouteMustExistInCurrentSource() throws Exception {
    writeController("/todos");
    Plan plan =
        Plan.builder()
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("Expose POST /todos")
                        .description("Create the endpoint")
                        .build()))
            .build();

    assertThat(verifier.verify(plan, null, workspace).missing()).isEmpty();
    assertThatThrownBy(
            () ->
                verifier.assertSatisfied(
                    plan, "The application must also expose GET /todos/{id}", workspace))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GET /todos/{id}");
  }

  @Test
  void nonHttpAndAmbiguousRequirementsAreNotApplicable() {
    Plan cli = Plan.builder().runtimeSurface(Plan.RuntimeSurface.CLI).build();
    Plan ambiguous = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThat(verifier.verify(cli, "implement POST /todos", workspace).applicable()).isFalse();
    assertThat(verifier.verify(ambiguous, "add a todo endpoint", workspace).applicable()).isFalse();
  }

  @Test
  void explicitRemovalIsNotTreatedAsARequiredNewRoute() {
    Plan plan = Plan.builder().runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThat(verifier.verify(plan, "Remove POST /legacy safely", workspace).applicable())
        .isFalse();
  }

  private void writeController(String path) throws Exception {
    Path file = workspace.resolve("src/main/java/example/TodoController.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package example;
        @RestController
        class TodoController {
          @PostMapping("%s")
          void create() {}
        }
        """
            .formatted(path));
  }
}
