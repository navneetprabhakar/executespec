package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskAcceptanceVerifierTest {

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
}
