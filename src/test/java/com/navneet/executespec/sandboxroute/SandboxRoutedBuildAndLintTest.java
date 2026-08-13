package com.navneet.executespec.sandboxroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.smoketest.TestCommandResolver;
import com.navneet.executespec.smoketest.WorkspaceTestRunner;
import com.navneet.executespec.standards.enforcement.lint.LintExecutionProperties;
import com.navneet.executespec.standards.enforcement.lint.LintProcessRunner;
import com.navneet.executespec.tool.BuildProjectTool;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.MavenDependencyValidator;
import com.navneet.executespec.tool.ShellSandboxPolicy;
import com.navneet.executespec.tool.ShellTool;
import com.navneet.executespec.tool.ToolResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The call-site half of RR-282 / RR-1674: what the build, test and lint runners do when routing is
 * on and the sandbox cannot execute.
 *
 * <p>The wrong answer — the one this whole change exists to remove — is "run it on the host
 * anyway". Each assertion below pins the right answer: an explicit unavailable outcome the caller
 * surfaces, with the command left unexecuted.
 */
class SandboxRoutedBuildAndLintTest {

  @TempDir Path workspace;

  private static final SandboxExecResult UNAVAILABLE =
      SandboxExecResult.unavailable("no container engine");

  @Test
  void anUnavailableSandboxMakesTheBuildUnavailableInsteadOfRunningItOnTheHost() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.executeShell(anyString(), any(), anyString(), any())).thenReturn(UNAVAILABLE);

    ToolResult result = buildTool(routing).execute(JsonNodeFactory.instance.objectNode(), ctx());

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    ToolResult.Err err = (ToolResult.Err) result;
    assertThat(err.kind()).isEqualTo(ToolResult.ErrorKind.UNAVAILABLE);
    assertThat(err.message()).contains("NOT executed on the application host");
  }

  @Test
  void aRoutedBuildReportsTheSandboxExitStatusInTheSameEnvelopeShapeAsAHostBuild() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.executeShell(anyString(), any(), anyString(), any()))
        .thenReturn(SandboxExecResult.completed(2, "compiling", "boom", false, Duration.ZERO));

    ToolResult result = buildTool(routing).execute(JsonNodeFactory.instance.objectNode(), ctx());

    // Ok regardless of exit code, exactly like the host path: the harness reads exit and decides.
    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content())
        .contains("command=printf hi", "smokeSignal=EXECUTED", "exit=2", "compiling", "boom");
  }

  @Test
  void withRoutingOffTheBuildStillRunsOnTheHostExactlyAsBefore() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(false);

    ToolResult result = buildTool(routing).execute(JsonNodeFactory.instance.objectNode(), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content()).contains("exit=0", "hi");
  }

  @Test
  void anUnavailableSandboxMakesShellUnavailableInsteadOfRunningItOnTheHost() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.executeShell(anyString(), any(), anyString(), any())).thenReturn(UNAVAILABLE);
    ShellTool shell =
        new ShellTool(new ShellSandboxPolicy(), null, null, new ContextSanitizer(), routing);

    ToolResult result =
        shell.execute(
            JsonNodeFactory.instance.objectNode().put("command", "touch host-marker"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    ToolResult.Err err = (ToolResult.Err) result;
    assertThat(err.kind()).isEqualTo(ToolResult.ErrorKind.UNAVAILABLE);
    assertThat(err.message()).contains("NOT executed on the application host");
    assertThat(workspace.resolve("host-marker")).doesNotExist();
  }

  @Test
  void aRoutedShellPreservesTheSandboxExitStatusAndOutput() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.executeShell(anyString(), any(), anyString(), any()))
        .thenReturn(SandboxExecResult.completed(3, "inside", "failed", false, Duration.ZERO));
    ShellTool shell =
        new ShellTool(new ShellSandboxPolicy(), null, null, new ContextSanitizer(), routing);

    ToolResult result =
        shell.execute(JsonNodeFactory.instance.objectNode().put("command", "python3 -V"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content()).contains("exit=3", "inside", "failed");
  }

  @Test
  void anUnavailableSandboxYieldsTypedEnvironmentEvidenceRatherThanAHostRerun() throws Exception {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.executeShell(anyString(), any(), anyString(), any())).thenReturn(UNAVAILABLE);

    WorkspaceTestRunner runner =
        new WorkspaceTestRunner(
            new TestCommandResolver(), new ShellSandboxPolicy(), null, null, routing);
    java.nio.file.Files.writeString(
        workspace.resolve("package.json"), "{\"scripts\":{\"test\":\"vitest run\"}}");

    WorkspaceTestRunner.ExecutionEvidence evidence =
        runner.executeTests(new ExecutionContext("run-1", "T1", workspace));

    assertThat(evidence.status())
        .isEqualTo(WorkspaceTestRunner.ExecutionStatus.TOOLCHAIN_UNAVAILABLE);
    assertThat(evidence.result()).isNull();
    assertThat(evidence.executed()).isFalse();
    verify(routing).executeShell(eq("run-1"), eq(workspace), anyString(), any());
  }

  @Test
  void lintWithNoRunScopeIsUnavailableUnderRoutingAndIsNotRunOnTheHost() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.execute(eq(null), any(), any(), any()))
        .thenReturn(SandboxExecResult.unavailable("no run scope for this command"));

    LintProcessRunner runner = new LintProcessRunner(new LintExecutionProperties(), routing);
    LintProcessRunner.Result result = runner.run(workspace, List.of("eslint", "--version"), 5);

    assertThat(result.executed()).isFalse();
    assertThat(result.unavailableReason()).contains("NOT run on the application host");
  }

  @Test
  void lintWithRoutingOffStillExecutesOnTheHostExactlyAsBefore() {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(false);

    LintProcessRunner runner = new LintProcessRunner(new LintExecutionProperties(), routing);
    LintProcessRunner.Result result = runner.run(workspace, List.of("printf", "ok"), 20);

    assertThat(result.executed()).isTrue();
    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("ok");
  }

  private BuildProjectTool buildTool(SandboxCommandRoutingService routing) {
    BuildCommandResolver resolver = mock(BuildCommandResolver.class);
    MavenDependencyValidator validator = mock(MavenDependencyValidator.class);
    when(validator.validate(workspace))
        .thenReturn(new MavenDependencyValidator.ValidationResult(true, List.of(), 0));
    when(resolver.resolve(eq(workspace), eq((String) null)))
        .thenReturn(
            BuildCommand.builder()
                .command("printf hi")
                .timeoutSeconds(20)
                .sourceDescription("test")
                .build());
    return new BuildProjectTool(resolver, validator, null, null, new ShellSandboxPolicy(), routing);
  }

  private ExecutionContext ctx() {
    return new ExecutionContext("run-1", "task-1", workspace);
  }
}
