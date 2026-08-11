package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SingleShotArtifactContractTest {

  @Test
  void extractsOnlyExplicitCustomerArtifactPaths() {
    String spec =
        "Build FastAPI in `main.py`, runnable with `uvicorn main:app`. Include a "
            + "`requirements.txt`; GET `/health` returns `status ok`.";

    assertThat(SingleShotArtifactContract.explicitRequiredPaths(spec))
        .containsExactly("main.py", "requirements.txt");
  }

  @Test
  void bindsOnlyCustomerNamedArtifactsAndIgnoresModelAuthority() {
    Plan plan =
        Plan.builder()
            .tasks(
                List.of(
                    PlannedTask.builder()
                        .id("T1")
                        .title("API")
                        .description("implement")
                        .touchesFiles(List.of("main.py"))
                        .requiredArtifacts(List.of("model-invented.txt"))
                        .build(),
                    PlannedTask.builder()
                        .id("T2")
                        .title("Dependencies")
                        .description("declare")
                        .touchesFiles(List.of("requirements.txt"))
                        .build()))
            .build();

    Plan bound =
        SingleShotArtifactContract.bindRequiredPaths(
            plan, "Create `main.py` and `requirements.txt`.");

    assertThat(bound.tasks().get(0).requiredArtifacts()).containsExactly("main.py");
    assertThat(bound.tasks().get(1).requiredArtifacts()).containsExactly("requirements.txt");
    assertThat(bound.tasks())
        .flatExtracting(PlannedTask::requiredArtifacts)
        .doesNotContain("model-invented.txt");
  }

  @Test
  void qualifiedPythonAdapterSuppliesPlatformDefaultsWhenCustomerNamesNoFiles() {
    Plan plan =
        Plan.builder().projectType("python").runtimeSurface(Plan.RuntimeSurface.HTTP).build();

    assertThat(
            SingleShotArtifactContract.requiredPaths(
                plan, "Build a minimal FastAPI health service."))
        .containsExactly("main.py", "requirements.txt");
  }
}
