package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskAcceptanceContractResolverTest {

  @TempDir Path workspace;

  private final TaskAcceptanceContractResolver resolver =
      new TaskAcceptanceContractResolver(new ObjectMapper());

  @Test
  void canonicalContractIsTypedSortedAndIndependentOfPlannerOrdering() {
    PlannedTask first =
        PlannedTask.builder()
            .id("T2")
            .title("Implement service")
            .description("Add behavior")
            .touchesFiles(List.of("src/Z.java", "src/A.java"))
            .build();
    PlannedTask reordered =
        first.toBuilder().touchesFiles(List.of("src/A.java", "src/Z.java")).build();

    TaskAcceptanceContract contract = resolver.resolve(first, 1);
    TaskAcceptanceContract same = resolver.resolve(reordered, 1);

    assertThat(contract.schemaVersion()).isEqualTo(1);
    assertThat(contract.taskId()).isEqualTo("T2");
    assertThat(contract.taskIndex()).isEqualTo(1);
    assertThat(contract.verificationPolicy()).isEqualTo("SERVER_RESOLVED_V1");
    assertThat(contract.buildRequirement()).isEqualTo("REQUIRED_NON_NOOP");
    assertThat(contract.testRequirement()).isEqualTo("REQUIRED");
    assertThat(contract.declaredTouchPaths()).containsExactly("src/A.java", "src/Z.java");
    assertThat(contract.digest()).isEqualTo(same.digest()).matches("[0-9a-f]{64}");
  }

  @Test
  void documentationOnlyTaskMayOmitTestsButActualCodeClassificationCannot() {
    PlannedTask documentation =
        PlannedTask.builder()
            .id("T1")
            .title("Documentation update")
            .description("Update README copy")
            .touchesFiles(List.of("README.md"))
            .build();
    PlannedTask code = documentation.toBuilder().touchesFiles(List.of("src/main/App.java")).build();

    assertThat(resolver.resolve(documentation, 0).testRequirement()).isEqualTo("OPTIONAL");
    assertThat(resolver.resolve(code, 0).testRequirement()).isEqualTo("REQUIRED");
  }

  @Test
  void authorityDigestBindsPlanGoalAndWorkspaceSelection() {
    PlannedTask task = PlannedTask.builder().id("T1").title("Task").build();
    Plan plan = Plan.builder().planTitle("Plan").tasks(List.of(task)).build();

    String baseline = resolver.authorityDigest(plan, "goal-a", "repo-a");

    assertThat(resolver.authorityDigest(plan, "goal-b", "repo-a")).isNotEqualTo(baseline);
    assertThat(resolver.authorityDigest(plan, "goal-a", "repo-b")).isNotEqualTo(baseline);
    assertThat(baseline).matches("[0-9a-f]{64}");
  }

  @Test
  void failureFirstContractFreezesGreenfieldCommandsAndChecksBeforeMutation() {
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Build Python API")
            .description("Create a Python service with tests")
            .touchesFiles(List.of("tests/test_app.py", "src/app.py"))
            .build();
    GreenfieldBaselinePlan baseline = new GreenfieldBaselineService().plan(workspace, task);

    TaskAcceptanceContract contract = resolver.resolveFailureFirst(task, 0, workspace, baseline);
    TaskAcceptanceContract reordered =
        resolver.resolveFailureFirst(
            task.toBuilder().touchesFiles(List.of("src/app.py", "tests/test_app.py")).build(),
            0,
            workspace,
            baseline);

    assertThat(contract.schemaVersion()).isEqualTo(2);
    assertThat(contract.verificationPolicy()).isEqualTo("SERVER_RESOLVED_FAILURE_FIRST_V2");
    assertThat(contract.runtimeSurface()).isEqualTo("PYTHON_LIBRARY");
    assertThat(contract.baselineKind()).isEqualTo("PYTHON");
    assertThat(contract.buildCommand().command()).isEqualTo("python3 -m compileall -q .");
    assertThat(contract.buildCommand().source()).isEqualTo("inferred:python");
    assertThat(contract.testCommand().command()).isEqualTo("python3 -m pytest -q");
    assertThat(contract.requiredPaths()).containsExactly("src/app.py", "tests/test_app.py");
    assertThat(contract.prefixChecks())
        .extracting(TaskAcceptanceContract.AcceptanceCheck::kind)
        .containsExactly(
            "REQUIRED_PATHS_PRESENT", "BUILD_COMMAND_AVAILABLE", "TEST_COMMAND_AVAILABLE");
    assertThat(contract.finalChecks())
        .extracting(TaskAcceptanceContract.AcceptanceCheck::kind)
        .containsExactly("MEANINGFUL_DIFF", "BUILD_PASSED", "TESTS_PASSED");
    assertThat(contract.digest()).isEqualTo(reordered.digest()).matches("[0-9a-f]{64}");
  }

  @Test
  void multiRepositoryContractFreezesAtomicScopesAndCombinedCommands() throws Exception {
    java.nio.file.Files.createDirectories(workspace.resolve("repos/api/.git"));
    java.nio.file.Files.createDirectories(workspace.resolve("repos/web/.git"));
    PlannedTask task =
        PlannedTask.builder()
            .id("T1")
            .title("Python API and React frontend")
            .description("Change both sides of one interface")
            .touchesFiles(List.of("repos/api/src/app.py", "repos/web/src/App.tsx"))
            .build();
    GreenfieldBaselinePlan baseline = new GreenfieldBaselineService().plan(workspace, task);

    TaskAcceptanceContract contract = resolver.resolveFailureFirst(task, 0, workspace, baseline);

    assertThat(contract.baselineKind()).isEqualTo("MULTI_REPOSITORY");
    assertThat(contract.allowedRepositoryScopes()).containsExactly("repos/api", "repos/web");
    assertThat(contract.buildCommand().command())
        .isEqualTo(
            "(cd repos/api && python3 -m compileall -q .) && (cd repos/web && npm run build"
                + " --if-present)");
    assertThat(contract.buildCommand().source()).isEqualTo("inferred:changed-multi-root");
    assertThat(contract.testCommand().command())
        .isEqualTo("(cd repos/api && python3 -m pytest -q) && (cd repos/web && npm test --silent)");
    assertThat(contract.testCommand().framework()).isEqualTo("MULTI");
  }

  @Test
  void frozenMultiRepositoryCommandsEqualThePostBaselineServerResolvers() throws Exception {
    java.nio.file.Files.createDirectories(workspace.resolve("repos/api"));
    java.nio.file.Files.createDirectories(workspace.resolve("repos/web"));
    try (Git ignored = Git.init().setDirectory(workspace.resolve("repos/api").toFile()).call();
        Git alsoIgnored = Git.init().setDirectory(workspace.resolve("repos/web").toFile()).call()) {
      PlannedTask task =
          PlannedTask.builder()
              .id("T1")
              .title("Python API and React frontend")
              .description("Change both sides")
              .touchesFiles(List.of("repos/api/src/app.py", "repos/web/src/App.tsx"))
              .build();
      GreenfieldBaselineService baselines = new GreenfieldBaselineService();
      GreenfieldBaselinePlan baseline = baselines.plan(workspace, task);
      TaskAcceptanceContract contract = resolver.resolveFailureFirst(task, 0, workspace, baseline);

      baselines.materialize(workspace, baseline);

      var actualBuild =
          new com.navneet.executespec.smoketest.BuildCommandResolver().resolve(workspace, null);
      var actualTest =
          new com.navneet.executespec.smoketest.TestCommandResolver()
              .resolve(workspace)
              .orElseThrow();
      assertThat(contract.buildCommand().command()).isEqualTo(actualBuild.command());
      assertThat(contract.buildCommand().source()).isEqualTo(actualBuild.sourceDescription());
      assertThat(contract.testCommand().command()).isEqualTo(actualTest.command());
      assertThat(contract.testCommand().source()).isEqualTo(actualTest.source());
      assertThat(contract.testCommand().framework()).isEqualTo(actualTest.framework().name());
    }
  }
}
