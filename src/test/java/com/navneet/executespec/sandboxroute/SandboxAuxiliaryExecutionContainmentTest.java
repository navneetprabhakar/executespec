package com.navneet.executespec.sandboxroute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.lint.LintPass;
import com.navneet.executespec.reviewer.PostEditFormatter;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.smoketest.CliSmokeRunner;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.workspacegeneration.WorkspaceGenerationDispatchAuthority;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Functional containment pin for subprocesses that used to sit outside the main build/test tool
 * path. Once strict routing is enabled, generated CLI and lint commands use the sandbox seam and
 * the legacy post-review formatter does not fall back to the host.
 */
class SandboxAuxiliaryExecutionContainmentTest {

  @Test
  void cliSmokeUsesArgumentVectorInsideTheRunSandbox(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("main.py"), "print('ok')\n");
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.execute(eq("run-1"), any(), any(), any()))
        .thenReturn(SandboxExecResult.completed(0, "ok\n", "", false, Duration.ofMillis(5)));

    CliSmokeRunner.Result result = new CliSmokeRunner(routing).run("run-1", workspace);

    assertThat(result.status()).isEqualTo(CliSmokeRunner.Status.PASSED);
    verify(routing)
        .execute(
            "run-1",
            workspace.toAbsolutePath().normalize(),
            List.of("python3", "main.py"),
            Duration.ofSeconds(15));
  }

  @Test
  void generationBoundCliSmokeUsesOnlyVerificationAuthority(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("main.py"), "print('ok')\n");
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    VerificationSandboxAuthority authority =
        new VerificationSandboxAuthority(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "run-1",
            "a".repeat(64),
            WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
            workspace);
    when(routing.executeVerification(eq(authority), any(), any()))
        .thenReturn(SandboxExecResult.completed(0, "ok\n", "", false, Duration.ofMillis(5)));
    ExecutionContext context =
        new ExecutionContext("run-1", "verify-cli", workspace.toAbsolutePath())
            .withVerificationSandboxAuthority(authority);

    CliSmokeRunner.Result result = new CliSmokeRunner(routing).run(context);

    assertThat(result.status()).isEqualTo(CliSmokeRunner.Status.PASSED);
    verify(routing)
        .executeVerification(authority, List.of("python3", "main.py"), Duration.ofSeconds(15));
    verify(routing, never()).execute(any(), any(), any(), any());
  }

  @Test
  void lintUsesSandboxAndNeverFallsBackWhenSandboxIsUnavailable(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("package.json"), "{\"name\":\"sandboxed\"}");
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.execute(eq("run-1"), any(), any(), any()))
        .thenReturn(SandboxExecResult.unavailable("runtime unavailable"));

    LintPass.LintReport report = new LintPass(routing).run("run-1", workspace);

    assertThat(report.results()).hasSize(1);
    assertThat(report.results().getFirst().success()).isFalse();
    assertThat(report.results().getFirst().error()).contains("runtime unavailable");
    verify(routing)
        .execute(
            "run-1",
            workspace.toAbsolutePath().normalize(),
            List.of("npx", "--no-install", "prettier", "--write", "--ignore-unknown", "."),
            Duration.ofMinutes(5));
  }

  @Test
  void postEditFormatterUsesContainerPathsUnderStrictRouting(@TempDir Path workspace)
      throws Exception {
    Files.writeString(workspace.resolve("main.py"), "print('ok')\n");
    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.routingEnabled()).thenReturn(true);
    when(routing.execute(eq("run-1"), any(), any(), any()))
        .thenReturn(SandboxExecResult.completed(0, "", "", false, Duration.ofMillis(5)));

    PostEditFormatter.Result result = new PostEditFormatter(routing).run(workspace, "run-1");

    assertThat(result.filesReformatted()).isZero();
    assertThat(result.formattersRun()).containsExactly("black on /workspace");
    assertThat(result.errors()).isEmpty();
    verify(routing)
        .execute(
            "run-1",
            workspace.toAbsolutePath().normalize(),
            List.of("black", "--quiet", "/workspace"),
            Duration.ofSeconds(60));
  }
}
