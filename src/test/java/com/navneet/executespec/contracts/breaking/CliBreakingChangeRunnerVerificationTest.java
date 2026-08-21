package com.navneet.executespec.contracts.breaking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.sandboxroute.VerificationSandboxAuthority;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class CliBreakingChangeRunnerVerificationTest {

  @Test
  void exactGenerationInputsAndCommandStayInsideVerificationRuntime(@TempDir Path workspace) {
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    VerificationSandboxAuthority authority = mock(VerificationSandboxAuthority.class);
    when(authority.runId()).thenReturn("run-1");
    when(authority.workspaceRoot()).thenReturn(workspace.toAbsolutePath());
    when(routing.executeVerification(eq(authority), any(), any()))
        .thenReturn(
            SandboxExecResult.completed(1, "error breaking\n", "", false, Duration.ofMillis(5)));
    ExecutionContext context =
        new ExecutionContext("run-1", "contract-diff", workspace.toAbsolutePath())
            .withVerificationSandboxAuthority(authority);
    CliBreakingChangeRunner runner = new CliBreakingChangeRunner(routing);

    CliBreakingChangeRunner.CliResult result =
        runner.run(
            context,
            "old",
            "new",
            "oasdiff",
            (oldPath, newPath) ->
                List.of("oasdiff", "breaking", oldPath.toString(), newPath.toString()),
            30);

    assertThat(result.executed()).isTrue();
    assertThat(result.exitCode()).isEqualTo(1);
    ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
    verify(routing).executeVerification(eq(authority), argv.capture(), eq(Duration.ofSeconds(30)));
    assertThat(argv.getValue().get(2)).startsWith("/workspace/.es-contract-diff-");
    assertThat(argv.getValue().get(3)).startsWith("/workspace/.es-contract-diff-");
    assertThat(workspace).isEmptyDirectory();
  }
}
