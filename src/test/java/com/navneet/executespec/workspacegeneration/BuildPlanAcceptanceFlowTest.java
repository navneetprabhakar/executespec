package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agent.Plan;
import com.navneet.executespec.agent.PlannedTask;
import com.navneet.executespec.agent.SingleShotArtifactContract;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.BuildVerificationResult;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.TestOutputParser;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.tool.BuildExecutionEvidence;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real command regression across approved-plan artifacts, baseline admission, and acceptance.
 * Physical isolation/leases are covered separately by the PostgreSQL and sandbox integration tests.
 */
class BuildPlanAcceptanceFlowTest {

  @TempDir Path workspace;
  private final ObjectMapper json = new ObjectMapper();
  private final BuildCommandResolver builds = new BuildCommandResolver();
  private final TestCommandResolver tests = new TestCommandResolver();
  private final GreenfieldBaselineService baselines = new GreenfieldBaselineService();
  private final TaskAcceptanceContractResolver contracts =
      new TaskAcceptanceContractResolver(json, builds, tests);

  @Test
  void threeTaskGreenfieldPlanAcceptsBootstrapFeatureOnGreenBaselineAndDocumentation()
      throws Exception {
    Plan approved =
        SingleShotArtifactContract.bindRequiredPaths(
            Plan.builder()
                .projectType("python")
                .runtimeSurface(Plan.RuntimeSurface.LIBRARY)
                .tasks(
                    List.of(
                        task(
                            "T1",
                            "Bootstrap Python library",
                            List.of("main.py", "requirements.txt", "test_main.py")),
                        task(
                            "T2", "Implement calculator", List.of("service.py", "test_service.py")),
                        task("T3", "Update documentation", List.of("README.md"))))
                .build(),
            "Build a Python library with arithmetic behavior and documentation.");
    BuildPlanCapabilityPolicy.requireSupported(
        approved, workspace, WorkspaceOrigin.GREENFIELD, BuildProtocolVersion.SHARED_KERNEL_V2);
    TaskAcceptanceVerifier verifier = verifier();
    for (int index = 0; index < approved.tasks().size(); index++) {
      PlannedTask task = approved.tasks().get(index);
      GreenfieldBaselinePlan baseline =
          baselines.plan(workspace, task, approved, WorkspaceOrigin.GREENFIELD);
      TaskAcceptanceContract contract =
          contracts.resolveFailureFirst(task, approved, index, workspace, baseline);
      baselines.materialize(workspace, baseline);
      ExecutionContext context = new ExecutionContext("flow-run", task.id(), workspace);
      TaskAcceptanceVerifier.InitialFailure initial =
          verifier.probeInitial(
              authorized(context, WorkspaceGenerationDispatchAuthority.Phase.MUTATION), contract);
      assertThat(initial.admits(contract)).as("task %s baseline %s", task.id(), initial).isTrue();
      if (index > 0) assertThat(initial.outcome()).isEqualTo("ALREADY_PASSED");
      switch (task.id()) {
        case "T1" -> {
          Files.writeString(workspace.resolve("main.py"), "def ready(): return True\n");
          Files.writeString(workspace.resolve("requirements.txt"), "");
          Files.writeString(
              workspace.resolve("test_main.py"),
              "from main import ready\ndef test_ready(): assert ready()\n");
        }
        case "T2" -> {
          Files.writeString(workspace.resolve("service.py"), "def add(a, b): return a + b\n");
          Files.writeString(
              workspace.resolve("test_service.py"),
              "from service import add\n"
                  + "def test_add(): assert add(2, 3) == 5\n"
                  + "def test_negative(): assert add(-2, 3) == 1\n");
        }
        case "T3" ->
            Files.writeString(workspace.resolve("README.md"), "Run python3 -m pytest -q.\n");
        default -> throw new AssertionError("unexpected task");
      }
      var verification =
          verifier.verify(
              authorized(context, WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION),
              contract,
              task.touchesFiles());
      assertThat(verification.outcome())
          .as("task %s evidence %s", task.id(), verification.evidenceJson())
          .isEqualTo("PASSED");
    }
    assertThat(run("python3 -m pytest -q").exit()).isZero();
  }

  @Test
  void nodeCandidateCannotPassOnFileExistenceWhileItsBehaviorTestFails() throws Exception {
    PlannedTask task =
        task("T1", "Node JavaScript library", List.of("index.js", "behavior.test.js")).toBuilder()
            .requiredArtifacts(List.of("index.js"))
            .build();
    GreenfieldBaselinePlan baseline = baselines.plan(workspace, task, WorkspaceOrigin.GREENFIELD);
    baselines.materialize(workspace, baseline);
    Files.writeString(workspace.resolve("index.js"), "module.exports = {};\n");
    Files.writeString(
        workspace.resolve("behavior.test.js"),
        "const test = require('node:test'); const assert = require('node:assert/strict');"
            + " test('exports requested function', () => assert.equal(typeof"
            + " require('./index.js').execute, 'function'));\n");
    String command = tests.resolve(workspace).orElseThrow().command();
    assertThat(run(command).exit()).isNotZero();
    Files.writeString(workspace.resolve("index.js"), "exports.execute = () => 42;\n");
    assertThat(run(command).exit()).isZero();
    Files.writeString(
        workspace.resolve("behavior.test.js"),
        "const test = require('node:test'); test.skip('not executed', () => {});\n");
    assertThat(run(command).exit()).isNotZero();
  }

  private TaskAcceptanceVerifier verifier() {
    BuildProjectTool build = mock(BuildProjectTool.class);
    WorkspaceTestRunner runner = mock(WorkspaceTestRunner.class);
    when(build.executeForVerification(any()))
        .thenAnswer(
            invocation -> {
              var command = builds.resolve(workspace, null);
              CommandResult executed = run(command.command());
              String output =
                  "command="
                      + command.command()
                      + "\nsource="
                      + command.sourceDescription()
                      + "\nexit="
                      + executed.exit()
                      + "\noutput:\n"
                      + executed.output();
              return new BuildExecutionEvidence(
                  BuildExecutionEvidence.Disposition.EXECUTED,
                  BuildVerificationResult.parse(output),
                  output,
                  null,
                  "DISPOSABLE_EXACT_GENERATION",
                  false);
            });
    when(runner.executeTests(any(ExecutionContext.class)))
        .thenAnswer(
            invocation -> {
              var command = tests.resolve(workspace).orElseThrow();
              CommandResult executed = run(command.command());
              var summary =
                  TestOutputParser.parse(command.framework(), executed.output(), executed.exit())
                      .orElseThrow();
              return new WorkspaceTestRunner.ExecutionEvidence(
                  executed.exit() == 0
                      ? WorkspaceTestRunner.ExecutionStatus.PASSED
                      : WorkspaceTestRunner.ExecutionStatus.PRODUCT_FAILED,
                  new WorkspaceTestRunner.DetailedResult(
                      summary, executed.output(), command.command(), executed.exit()),
                  null,
                  command.command(),
                  true);
            });
    DisposableVerificationWorkspaceService disposable =
        mock(DisposableVerificationWorkspaceService.class);
    when(disposable.execute(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Function<ExecutionContext, Object> command = invocation.getArgument(2);
              return new DisposableVerificationWorkspaceService.Execution<>(
                  command.apply(invocation.getArgument(0)),
                  "generation",
                  "generation",
                  1,
                  1,
                  "before",
                  "after",
                  0,
                  Duration.ZERO,
                  true);
            });
    return new TaskAcceptanceVerifier(
        builds,
        tests,
        build,
        runner,
        mock(PythonHttpRuntimeAcceptanceService.class),
        json,
        new TaskAcceptanceDisposableRunner(disposable, json));
  }

  private CommandResult run(String command) throws Exception {
    ProcessBuilder builder =
        new ProcessBuilder("/bin/sh", "-c", command)
            .directory(workspace.toFile())
            .redirectErrorStream(true);
    builder
        .environment()
        .put("PYTHONPYCACHEPREFIX", workspace.resolve(".executespec/pycache").toString());
    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("fixture command exceeded deadline: " + command);
    }
    return new CommandResult(
        process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static PlannedTask task(String id, String title, List<String> files) {
    return PlannedTask.builder().id(id).title(title).description(title).touchesFiles(files).build();
  }

  private static ExecutionContext authorized(
      ExecutionContext context, WorkspaceGenerationDispatchAuthority.Phase phase) {
    var lease = mock(com.navneet.executespec.integration.RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn(context.runId());
    var prepared =
        new WorkspaceGenerationLifecycle.Prepared(java.util.UUID.randomUUID(), null, lease);
    return context.withWorkspaceGenerationAuthority(
        new WorkspaceGenerationDispatchAuthority(
            context.runId(),
            context.taskId(),
            prepared,
            phase,
            mock(WorkspaceGenerationLifecycle.class)));
  }

  private record CommandResult(int exit, String output) {}
}
