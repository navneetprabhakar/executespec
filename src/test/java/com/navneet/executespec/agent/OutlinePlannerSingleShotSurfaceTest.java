package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.contractsurface.ExactHttpConstraintParser;

import org.junit.jupiter.api.Test;

class OutlinePlannerSingleShotSurfaceTest {

  @Test
  void shortWebAppRequestMeansBrowserUiRatherThanApi() {
    Plan.RuntimeSurface surface =
        SingleShotSurfaceClassifier.infer("lets create a simple web todo app");

    assertThat(surface).isEqualTo(Plan.RuntimeSurface.STATIC_WEB);
    assertThat(SingleShotSurfaceClassifier.projectType(surface)).isEqualTo("node-frontend");
  }

  @Test
  void explicitApiOrBackendRequestStillMeansHttp() {
    assertThat(SingleShotSurfaceClassifier.infer("create a simple web todo REST API"))
        .isEqualTo(Plan.RuntimeSurface.HTTP);
    assertThat(SingleShotSurfaceClassifier.infer("create a backend server for todo items"))
        .isEqualTo(Plan.RuntimeSurface.HTTP);
  }

  @Test
  void recognizesOtherExplicitRuntimeSurfaces() {
    assertThat(SingleShotSurfaceClassifier.infer("create a todo CLI"))
        .isEqualTo(Plan.RuntimeSurface.CLI);
    assertThat(SingleShotSurfaceClassifier.infer("create a reusable todo library"))
        .isEqualTo(Plan.RuntimeSurface.LIBRARY);
  }

  @Test
  void sourceFunctionChangeWithoutExternalRuntimeDefaultsToLibrary() {
    assertThat(
            SingleShotSurfaceClassifier.infer(
                "Extend src/catalog.js filterCatalog without replacing or truncating the file."))
        .isEqualTo(Plan.RuntimeSurface.LIBRARY);
  }

  @Test
  void negatedHttpConstraintDoesNotOverrideCliSurface() {
    assertThat(
            SingleShotSurfaceClassifier.infer(
                "Create a TypeScript CLI. Do not create an HTTP server."))
        .isEqualTo(Plan.RuntimeSurface.CLI);
  }

  @Test
  void multiEndpointSingleShotPlanPreservesEveryExactHttpOperation() {
    String request =
        """
Build a Python FastAPI REST API for a simple to-do list, in a single `main.py`, runnable with `uvicorn main:app`.

Requirements:
- `GET /health` returns `{\"status\": \"ok\"}`.
- `GET /todos` returns the list of todos (start empty).
- `POST /todos` accepts `{\"title\": \"...\"}`, creates a todo with an auto-incrementing integer `id` and `done=false`, and returns it.
- `DELETE /todos/{id}` deletes the todo with that id and returns `{\"deleted\": true}`; returns 404 if it doesn't exist.
- Use an in-memory store (a list/dict) — no database.
- Include a `requirements.txt` with `fastapi` and `uvicorn`.

Keep it minimal and idiomatic.
""";
    RequirementSpec requirement =
        new RequirementSpec(
            "R1",
            "Build a Python FastAPI REST API for a simple to-do list, in a single `main.py`,"
                + " runnable with `uvicorn main:app`.",
            RequirementSpec.RequirementKind.FUNCTIONAL);
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Build FastAPI Todo API")
            .description(request + "\n\nAlso include unit tests for every new endpoint.")
            .complexity(PlannedTask.Complexity.LOW)
            .taskType(PlannedTask.TaskType.LEAF)
            .meetsRequirements(List.of("R1"))
            .build();
    Plan plan =
        Plan.builder()
            .planTitle(task.title())
            .planSummary(request)
            .projectType("http-api")
            .smokeTestStrategy(Plan.SmokeTestStrategy.BUILD)
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .classification(Classification.SINGLE_SHOT)
            .tasks(List.of(task))
            .requirements(List.of(requirement))
            .build();

    Plan normalized = TaskExpanderAgent.normalizeFinalPlan(plan);
    assertThat(normalized.tasks().getFirst().touchesFiles()).isEmpty();
    assertThat(PlanSafetyEvidenceSupport.isTestOnlyTask(normalized.tasks().getFirst())).isFalse();
    assertThat(ExactHttpConstraintParser.parse(normalized.tasks().getFirst().description()))
        .hasSize(4);
    Plan compiled = TaskExecutionBriefCompiler.compileExecutable(plan, request);

    assertThat(compiled.tasks()).hasSize(1);
  }
}
