package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver.Framework;
import com.navneet.executespec.smoketest.TestCommandResolver.TestCommand;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.smoketest.WorkspaceTestRunner.DetailedResult;
import com.navneet.executespec.testgen.model.TestExecutionSummary;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.ToolResult;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskAcceptanceVerifierTest {

  @TempDir Path workspace;

  private final BuildCommandResolver buildCommands = mock(BuildCommandResolver.class);
  private final TestCommandResolver testCommands = mock(TestCommandResolver.class);
  private final BuildProjectTool buildTool = mock(BuildProjectTool.class);
  private final WorkspaceTestRunner testRunner = mock(WorkspaceTestRunner.class);
  private final ExecutionContext context =
      new ExecutionContext("run-1", "T1", Path.of(".").toAbsolutePath().normalize());
  private TaskAcceptanceVerifier verifier;

  @BeforeEach
  void setUp() {
    verifier =
        new TaskAcceptanceVerifier(
            buildCommands, testCommands, buildTool, testRunner, new ObjectMapper());
    when(buildCommands.resolve(any(Path.class), nullable(String.class)))
        .thenReturn(
            BuildCommand.builder()
                .command("mvn -q -DskipTests compile")
                .timeoutSeconds(300)
                .sourceDescription("inferred:maven")
                .build());
    when(buildTool.execute(any(), any()))
        .thenReturn(
            ToolResult.ok(
                "command=mvn -q -DskipTests compile\n"
                    + "cwd=/workspace\nsource=inferred:maven\nexit=0\noutput:\n"));
  }

  @Test
  void rejectsAZeroDiffGenerationEvenWhenTheBuildPasses() {
    when(testCommands.resolve(any(Path.class))).thenReturn(Optional.empty());

    var result = verifier.verify(context, optionalTests(), List.of());

    assertThat(result.outcome()).isEqualTo("FAILED");
    assertThat(result.buildPassed()).isTrue();
  }

  @Test
  void actualCodeChangesUpgradePlannerOptionalTestsToRequired() {
    when(testCommands.resolve(any(Path.class))).thenReturn(Optional.empty());

    var result = verifier.verify(context, optionalTests(), List.of("src/main/App.java"));

    assertThat(result.outcome()).isEqualTo("UNAVAILABLE");
    assertThat(result.testsExecuted()).isFalse();
    assertThat(result.serverResolvedCommandContract())
        .contains("\"effectiveTestRequirement\":\"REQUIRED\"");
  }

  @Test
  void acceptsAnActualCodeChangeOnlyWithExactBuildAndPassingTests() {
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(any(Path.class))).thenReturn(Optional.of(command));
    TestExecutionSummary summary =
        TestExecutionSummary.builder().totalTests(2).passed(2).failed(0).errors(0).build();
    when(testRunner.runTestsDetailed(anyString(), any(Path.class)))
        .thenReturn(Optional.of(new DetailedResult(summary, "Tests run: 2", command.command(), 0)));

    var result = verifier.verify(context, optionalTests(), List.of("src/main/App.java"));

    assertThat(result.outcome()).isEqualTo("PASSED");
    assertThat(result.buildPassed()).isTrue();
    assertThat(result.testsExecuted()).isTrue();
    assertThat(result.testsPassed()).isEqualTo(2);
    verify(buildCommands).resolve(context.workspaceRoot(), null);
    verify(buildTool)
        .execute(
            org.mockito.ArgumentMatchers.argThat(node -> node != null && node.isEmpty()),
            org.mockito.ArgumentMatchers.eq(context));
    assertThat(result.serverResolvedCommandContract())
        .contains("\"buildSource\":\"inferred:maven\"")
        .doesNotContain("planner");
  }

  @Test
  void schemaV2AcceptsOnlyTheFrozenCommandsRequiredPathsAndNonEmptyTests() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary =
        TestExecutionSummary.builder().totalTests(2).passed(2).failed(0).errors(0).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(Optional.of(new DetailedResult(summary, "Tests run: 2", command.command(), 0)));

    var result = verifier.verify(v2Context, failureFirstContract(), List.of("src/main/App.java"));

    assertThat(result.outcome()).isEqualTo("PASSED");
    assertThat(result.serverResolvedCommandContract())
        .contains("\"schemaVersion\":2")
        .contains("\"frozenBuildMatches\":true")
        .contains("\"requiredPathsPresent\":true");
  }

  @Test
  void schemaV2RejectsCommandDriftEvenWhenTheDriftedBuildPasses() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    when(testCommands.resolve(workspace))
        .thenReturn(Optional.of(new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven")));
    when(buildCommands.resolve(workspace, null))
        .thenReturn(
            BuildCommand.builder()
                .command("mvn -q package -DskipTests")
                .timeoutSeconds(300)
                .sourceDescription("inferred:maven")
                .build());
    when(buildTool.execute(any(), any()))
        .thenReturn(
            ToolResult.ok(
                "command=mvn -q package -DskipTests\n"
                    + "cwd=/workspace\nsource=inferred:maven\nexit=0\noutput:\n"));

    var result = verifier.verify(v2Context, failureFirstContract(), List.of("src/main/App.java"));

    assertThat(result.outcome()).isEqualTo("FAILED");
    assertThat(result.buildPassed()).isFalse();
    assertThat(result.evidenceJson())
        .contains("\"frozenBuildMatches\":false")
        .contains("\"commandClass\":\"BROAD_VERIFICATION\"");
    assertThat(result.serverResolvedCommandContract())
        .contains("\"commandClass\":\"BROAD_VERIFICATION\"");
  }

  @Test
  void schemaV2RejectsAWorkerModifiedBootstrapTest() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.createDirectories(workspace.resolve(".executespec/bootstrap"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    Files.writeString(workspace.resolve(".executespec/bootstrap/test_acceptance.py"), "tampered\n");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary = TestExecutionSummary.builder().totalTests(1).passed(1).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(Optional.of(new DetailedResult(summary, "1 passed", command.command(), 0)));

    var result =
        verifier.verify(
            v2Context, bootstrapBoundContract("original\n"), List.of("src/main/App.java"));

    assertThat(result.outcome()).isEqualTo("FAILED");
    assertThat(result.evidenceJson())
        .contains("\"bootstrapArtifactsMatch\":false")
        .contains("\"kind\":\"BOOTSTRAP_ARTIFACT_INTEGRITY\"");
  }

  @Test
  void heldOutEndpointCheckRejectsCandidateEvenWhenGeneratedTestsPass() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    Files.writeString(
        workspace.resolve("openapi.json"),
        "{\"openapi\":\"3.0.0\",\"paths\":{\"/other\":{\"get\":{}}}}");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary =
        TestExecutionSummary.builder().totalTests(2).passed(2).failed(0).errors(0).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(
            Optional.of(
                new DetailedResult(summary, "2 generated tests pass", command.command(), 0)));

    var result =
        verifier.verify(
            v2Context, endpointContract(), List.of("src/main/App.java", "openapi.json"));

    assertThat(result.outcome()).isEqualTo("FAILED");
    assertThat(result.testsPassed()).isEqualTo(2);
    assertThat(result.evidenceJson())
        .contains("\"endpointExpectationsPassed\":false")
        .contains("\"kind\":\"ENDPOINT_CONTRACT\"")
        .contains("\"status\":\"FAILED\"");
  }

  @Test
  void heldOutEndpointCheckPassesTheRequiredMethodAndPath() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    Files.writeString(
        workspace.resolve("openapi.json"),
        "{\"openapi\":\"3.0.0\",\"paths\":{\"/health\":{\"get\":{}}}}");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary = TestExecutionSummary.builder().totalTests(1).passed(1).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(
            Optional.of(new DetailedResult(summary, "1 test passes", command.command(), 0)));

    var result =
        verifier.verify(
            v2Context, endpointContract(), List.of("src/main/App.java", "openapi.json"));

    assertThat(result.outcome()).isEqualTo("PASSED");
    assertThat(result.evidenceJson()).contains("\"endpointExpectationsPassed\":true");
  }

  @Test
  void heldOutEndpointCheckIgnoresPathParameterVariableNames() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    Files.writeString(
        workspace.resolve("openapi.json"),
        "{\"openapi\":\"3.0.0\",\"paths\":{\"/todos/{todo_id}\":{\"delete\":{}}}}");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary = TestExecutionSummary.builder().totalTests(1).passed(1).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(
            Optional.of(new DetailedResult(summary, "1 test passes", command.command(), 0)));

    var result =
        verifier.verify(
            v2Context,
            endpointContract("DELETE /todos/{id}"),
            List.of("src/main/App.java", "openapi.json"));

    assertThat(result.outcome()).isEqualTo("PASSED");
    assertThat(result.evidenceJson()).contains("\"endpointExpectationsPassed\":true");
  }

  @Test
  void initialProbeUsesMissingPathAsExecutableRedSignalWithoutRunningCommands() {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    when(testCommands.resolve(workspace))
        .thenReturn(Optional.of(new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven")));

    var result = verifier.probeInitial(v2Context, failureFirstContract());

    assertThat(result.observed()).isTrue();
    assertThat(result.code()).isEqualTo("REQUIRED_PATH_MISSING");
    assertThat(result.failedTargets()).containsExactly("src/main/App.java");
    org.mockito.Mockito.verifyNoInteractions(buildTool, testRunner);
  }

  @Test
  void initialProbeUsesMissingHeldOutEndpointAsExecutableRedSignal() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    when(testCommands.resolve(workspace))
        .thenReturn(Optional.of(new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven")));

    var result = verifier.probeInitial(v2Context, endpointContract());

    assertThat(result.observed()).isTrue();
    assertThat(result.code()).isEqualTo("ENDPOINT_CONTRACT_UNSATISFIED");
    assertThat(result.failedTargets()).containsExactly("GET /health");
    org.mockito.Mockito.verifyNoInteractions(buildTool, testRunner);
  }

  @Test
  void initialProbeFailsBeforeProviderWhenRequiredTestCommandIsUnavailable() {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    when(testCommands.resolve(workspace)).thenReturn(Optional.empty());

    var result = verifier.probeInitial(v2Context, failureFirstContract());

    assertThat(result.observed()).isFalse();
    assertThat(result.outcome()).isEqualTo("UNAVAILABLE");
    assertThat(result.code()).isEqualTo("INITIAL_COMMAND_CONTRACT_DRIFT");
    org.mockito.Mockito.verifyNoInteractions(buildTool, testRunner);
  }

  @Test
  void initialProbeRefusesGreenWorkspaceInsteadOfInventingFailure() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary = TestExecutionSummary.builder().totalTests(2).passed(2).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(
            Optional.of(new DetailedResult(summary, "2 tests passed", command.command(), 0)));

    var result = verifier.probeInitial(v2Context, failureFirstContract());

    assertThat(result.observed()).isFalse();
    assertThat(result.outcome()).isEqualTo("ALREADY_PASSED");
    assertThat(result.code()).isEqualTo("NO_INITIAL_FAILURE");
    assertThat(result.passed()).isEqualTo(2);
    assertThat(result.total()).isEqualTo(2);
    assertThat(result.commandClass()).isEqualTo("REPRODUCTION");
  }

  @Test
  void initialProbeCapturesExistingFailingTestEvidence() throws Exception {
    ExecutionContext v2Context = new ExecutionContext("run-1", "T1", workspace);
    Files.createDirectories(workspace.resolve("src/main"));
    Files.writeString(workspace.resolve("src/main/App.java"), "class App {}\n");
    TestCommand command = new TestCommand("mvn -q test", 600, Framework.SUREFIRE, "maven");
    when(testCommands.resolve(workspace)).thenReturn(Optional.of(command));
    TestExecutionSummary summary =
        TestExecutionSummary.builder().totalTests(2).passed(1).failed(1).build();
    when(testRunner.runTestsDetailed("run-1", workspace))
        .thenReturn(
            Optional.of(
                new DetailedResult(summary, "AppTest expected 2 but got 1", command.command(), 1)));

    var result = verifier.probeInitial(v2Context, failureFirstContract());

    assertThat(result.observed()).isTrue();
    assertThat(result.code()).isEqualTo("INITIAL_TESTS_FAILED");
    assertThat(result.detail()).contains("expected 2 but got 1");
    assertThat(result.passed()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1);
    assertThat(result.total()).isEqualTo(2);
  }

  private static TaskAcceptanceContract optionalTests() {
    return new TaskAcceptanceContract(
        1,
        "T1",
        0,
        "SERVER_RESOLVED_V1",
        "REQUIRED_NON_NOOP",
        "OPTIONAL",
        List.of("README.md"),
        "a".repeat(64));
  }

  private static TaskAcceptanceContract failureFirstContract() {
    return new TaskAcceptanceContract(
        2,
        "T1",
        0,
        "SERVER_RESOLVED_FAILURE_FIRST_V2",
        "REQUIRED_NON_NOOP",
        "REQUIRED",
        List.of("src/main/App.java"),
        "JAVA_MAVEN_MODULE",
        "JAVA_MAVEN",
        new TaskAcceptanceContract.ResolvedCommand(
            "mvn -q -DskipTests compile", 300, "inferred:maven", "MAVEN"),
        new TaskAcceptanceContract.ResolvedCommand("mvn -q test", 600, "maven", "SUREFIRE"),
        List.of("src/main/App.java"),
        List.of(),
        List.of(),
        true,
        List.of("."),
        List.of(),
        List.of(),
        "b".repeat(64));
  }

  private static TaskAcceptanceContract endpointContract() {
    return endpointContract("GET /health");
  }

  private static TaskAcceptanceContract endpointContract(String endpoint) {
    TaskAcceptanceContract base = failureFirstContract();
    return new TaskAcceptanceContract(
        base.schemaVersion(),
        base.taskId(),
        base.taskIndex(),
        base.verificationPolicy(),
        base.buildRequirement(),
        base.testRequirement(),
        List.of("src/main/App.java", "openapi.json"),
        "HTTP_SERVICE",
        base.baselineKind(),
        base.buildCommand(),
        base.testCommand(),
        base.requiredPaths(),
        base.requiredTestSelectors(),
        List.of(endpoint),
        true,
        base.allowedRepositoryScopes(),
        base.prefixChecks(),
        List.of(
            new TaskAcceptanceContract.AcceptanceCheck("REPOSITORY_SCOPE", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck(
                "REQUIRED_PATHS_PRESENT", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("MEANINGFUL_DIFF", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("BUILD_PASSED", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("TESTS_PASSED", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("ENDPOINT_CONTRACT", "openapi", true)),
        "c".repeat(64));
  }

  private static TaskAcceptanceContract bootstrapBoundContract(String originalContent) {
    TaskAcceptanceContract base = failureFirstContract();
    return new TaskAcceptanceContract(
        base.schemaVersion(),
        base.taskId(),
        base.taskIndex(),
        base.verificationPolicy(),
        base.buildRequirement(),
        base.testRequirement(),
        base.declaredTouchPaths(),
        base.runtimeSurface(),
        base.baselineKind(),
        base.buildCommand(),
        base.testCommand(),
        base.requiredPaths(),
        base.requiredTestSelectors(),
        base.endpointExpectations(),
        true,
        base.allowedRepositoryScopes(),
        base.prefixChecks(),
        List.of(
            new TaskAcceptanceContract.AcceptanceCheck("REPOSITORY_SCOPE", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck(
                "REQUIRED_PATHS_PRESENT", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck(
                "BOOTSTRAP_ARTIFACT_INTEGRITY", ".executespec/bootstrap", true),
            new TaskAcceptanceContract.AcceptanceCheck("MEANINGFUL_DIFF", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("BUILD_PASSED", "candidate", false),
            new TaskAcceptanceContract.AcceptanceCheck("TESTS_PASSED", "candidate", false)),
        Map.of(".executespec/bootstrap/test_acceptance.py", DigestUtils.sha256Hex(originalContent)),
        "d".repeat(64));
  }
}
