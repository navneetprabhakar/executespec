package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.contractsurface.ExactHttpConstraintParser;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;

class OutlinePlannerSingleShotSurfaceTest {

  @Test
  void synthesizedPlanCarriesExplicitArtifactsAsAcceptanceAuthority() {
    RunTitleSummarizer titles = mock(RunTitleSummarizer.class);
    when(titles.summarize(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn("Build FastAPI todo API");
    OutlinePlannerAgent planner =
        new OutlinePlannerAgent(
            mock(LlmRouter.class),
            new ObjectMapper(),
            mock(com.navneet.executespec.observability.Metrics.class),
            mock(com.navneet.executespec.router.ModelResolver.class),
            mock(com.navneet.executespec.config.featureflag.FeatureFlagService.class),
            mock(PromptAssembler.class),
            titles);
    String spec =
        "Build a FastAPI service in `main.py`, runnable with `uvicorn main:app`, and include "
            + "`requirements.txt`. GET `/health` returns ok.";

    Plan plan =
        planner.synthesizeSingleShot(
            PlannerRequest.builder()
                .spec(spec)
                .classification(Classification.SINGLE_SHOT)
                .context(new ExecutionContext("run-1", "__plan__", Path.of("/tmp/run-1")))
                .build());

    assertThat(plan.tasks()).singleElement();
    assertThat(plan.tasks().getFirst().requiredArtifacts())
        .containsExactly("main.py", "requirements.txt");
    assertThat(plan.tasks().getFirst().touchesFiles()).isEmpty();
    assertThat(plan.projectType()).isEqualTo("python");
  }

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
  void excludedSurfacesDoNotReclassifyPositiveLibraryWebOrApiRequests() {
    assertThat(SingleShotSurfaceClassifier.infer("No authentication, build a REST API."))
        .isEqualTo(Plan.RuntimeSurface.HTTP);
    assertThat(
            SingleShotSurfaceClassifier.infer(
                "Create a minimal Python library. This is a library, not an HTTP service or a"
                    + " CLI."))
        .isEqualTo(Plan.RuntimeSurface.LIBRARY);
    assertThat(SingleShotSurfaceClassifier.infer("Build a REST API without a CLI or library."))
        .isEqualTo(Plan.RuntimeSurface.HTTP);
    assertThat(SingleShotSurfaceClassifier.infer("Build a website. No backend API or CLI."))
        .isEqualTo(Plan.RuntimeSurface.STATIC_WEB);
    assertThat(SingleShotSurfaceClassifier.infer("Not an HTTP server, but a terminal CLI."))
        .isEqualTo(Plan.RuntimeSurface.CLI);
    assertThat(SingleShotSurfaceClassifier.infer("Not only a CLI but also a reusable library."))
        .isEqualTo(Plan.RuntimeSurface.CLI);
    assertThat(
            SingleShotSurfaceClassifier.projectType(
                "Build a Node.js library, not Python or FastAPI.", Plan.RuntimeSurface.LIBRARY))
        .isEqualTo("node-backend");
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
