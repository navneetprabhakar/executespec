package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GreenfieldBaselineServiceTest {

  @TempDir Path workspace;

  private final GreenfieldBaselineService service = new GreenfieldBaselineService();

  @Test
  void planningIsSideEffectFreeAndPythonMaterializationIsIdempotent() throws Exception {
    PlannedTask task =
        task("Implement Python calculator", List.of("calculator.py", "test_calculator.py"));

    GreenfieldBaselinePlan plan = service.plan(workspace, task);

    assertThat(plan.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.PYTHON);
    assertThat(plan.runtimeSurface()).isEqualTo("PYTHON_LIBRARY");
    assertThat(plan.initialFailureEvidence())
        .contains("REQUIRED_PATH_MISSING:calculator.py", "FINAL_ACCEPTANCE_NOT_YET_PROVEN");
    assertThat(workspace).isEmptyDirectory();

    assertThat(service.materialize(workspace, plan))
        .containsExactly("requirements.txt", "tests/test_executespec_acceptance.py");
    assertThat(Files.readString(workspace.resolve("requirements.txt"))).isEqualTo("pytest\n");
    assertThat(service.materialize(workspace, plan)).isEmpty();
  }

  @Test
  void supportsNodeJavaAndFrontendWithoutWritingProductImplementation() {
    GreenfieldBaselinePlan node =
        service.plan(workspace, task("TypeScript library", List.of("src/index.ts")));
    assertThat(node.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.NODE_TYPESCRIPT);
    assertThat(node.filesToCreate())
        .containsOnlyKeys("package.json", "tsconfig.json", "tests/executespec-acceptance.test.ts");

    GreenfieldBaselinePlan java =
        service.plan(workspace, task("Java service", List.of("src/main/java/App.java")));
    assertThat(java.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.JAVA_MAVEN);
    assertThat(java.filesToCreate())
        .containsOnlyKeys("pom.xml", "src/test/java/ExecutespecAcceptanceTest.java");

    GreenfieldBaselinePlan frontend =
        service.plan(workspace, task("React frontend", List.of("src/App.tsx")));
    assertThat(frontend.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.FRONTEND_REACT);
    assertThat(frontend.filesToCreate())
        .containsOnlyKeys(
            "package.json", "tsconfig.json", "index.html", "tests/executespec-acceptance.test.ts");
    assertThat(frontend.filesToCreate()).doesNotContainKeys("src/App.tsx", "src/main.tsx");
  }

  @Test
  void runtimeSurfaceComesFromApprovedPlanNotFileExtensionGuessing() {
    PlannedTask task = task("Build Python REST API", List.of("src/app.py", "openapi.yaml"));
    Plan plan =
        Plan.builder()
            .planTitle("API")
            .projectType("python")
            .runtimeSurface(Plan.RuntimeSurface.HTTP)
            .tasks(List.of(task))
            .build();

    GreenfieldBaselinePlan baseline = service.plan(workspace, task, plan);

    assertThat(baseline.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.PYTHON);
    assertThat(baseline.runtimeSurface()).isEqualTo("HTTP_SERVICE");
  }

  @Test
  void baselineAcceptanceSurfaceStartsRedAndNamesTheMissingContractPath() throws Exception {
    PlannedTask task = task("Implement Python calculator", List.of("calculator.py"));
    GreenfieldBaselinePlan plan = service.plan(workspace, task);

    service.materialize(workspace, plan);

    String verifier = Files.readString(workspace.resolve("tests/test_executespec_acceptance.py"));
    assertThat(verifier)
        .contains("Path(\"calculator.py\").exists()")
        .contains("required path missing: calculator.py");
    assertThat(workspace.resolve("calculator.py")).doesNotExist();
  }

  @Test
  void existingRepositoryIsNeverRebaselined() throws Exception {
    Files.writeString(workspace.resolve("customer.txt"), "keep");

    GreenfieldBaselinePlan plan =
        service.plan(workspace, task("Python task", List.of("calculator.py")));

    assertThat(plan.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.EXISTING);
    assertThat(plan.greenfield()).isFalse();
    assertThat(service.materialize(workspace, plan)).isEmpty();
    assertThat(Files.readString(workspace.resolve("customer.txt"))).isEqualTo("keep");
  }

  @Test
  void ambiguousEmptyTaskFailsClosedInsteadOfGuessingAStack() {
    GreenfieldBaselinePlan plan = service.plan(workspace, task("Create the thing", List.of()));

    assertThat(plan.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.UNSUPPORTED);
    assertThat(plan.supported()).isFalse();
    assertThat(plan.filesToCreate()).isEmpty();
    assertThat(plan.initialFailureEvidence()).containsExactly("FINAL_ACCEPTANCE_NOT_YET_PROVEN");
  }

  @Test
  void unsafePlannerPathsNeverBecomeBaselineWriteTargets() {
    GreenfieldBaselinePlan plan =
        service.plan(
            workspace,
            task("Python task", List.of("../../outside.py", "/tmp/escape.py", "safe.py")));

    assertThat(plan.requiredTaskPaths()).containsExactly("safe.py");
  }

  @Test
  void plansAndMaterializesOnlyTheTargetedEmptyRepositories() throws Exception {
    Files.createDirectories(workspace.resolve("repos/api/.git"));
    Files.createDirectories(workspace.resolve("repos/web/.git"));
    Files.createDirectories(workspace.resolve("repos/untouched/.git"));
    PlannedTask task =
        task(
            "Python API and React frontend contract",
            List.of("repos/api/src/app.py", "repos/web/src/App.tsx"));

    GreenfieldBaselinePlan plan = service.plan(workspace, task);

    assertThat(plan.kind()).isEqualTo(GreenfieldBaselinePlan.Kind.MULTI_REPOSITORY);
    assertThat(plan.repositoryTargets())
        .extracting(GreenfieldBaselinePlan.RepositoryTarget::relativeRoot)
        .containsExactly("repos/api", "repos/web");
    assertThat(plan.filesToCreate().keySet())
        .allMatch(path -> path.startsWith("repos/api/") || path.startsWith("repos/web/"));
    service.materialize(workspace, plan);
    assertThat(workspace.resolve("repos/api/requirements.txt")).exists();
    assertThat(workspace.resolve("repos/web/package.json")).exists();
    assertThat(workspace.resolve("repos/untouched/package.json")).doesNotExist();
    assertThat(workspace.resolve("repos/untouched/requirements.txt")).doesNotExist();
  }

  private static PlannedTask task(String title, List<String> files) {
    return PlannedTask.builder()
        .id("T1")
        .title(title)
        .description(title)
        .touchesFiles(files)
        .build();
  }
}
