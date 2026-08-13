package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.sandboxroute.VerificationSandboxAuthority;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DisposableVerificationWorkspaceServiceTest {

  @TempDir Path temp;

  private Path canonical;
  private Path disposable;
  private CorrectionWorkspaceTransaction generations;
  private SandboxCommandRoutingService routing;
  private VerificationSandboxAuthority physicalAuthority;
  private DisposableVerificationWorkspaceService service;
  private ExecutionContext context;

  @BeforeEach
  void setUp() throws Exception {
    canonical = Files.createDirectories(temp.resolve("canonical"));
    disposable = Files.createDirectories(temp.resolve("sandboxes/s1/workspace"));
    run(canonical, "git", "init", "-q");
    Files.writeString(canonical.resolve("tracked.txt"), "accepted\n");
    run(canonical, "git", "add", "tracked.txt");
    run(
        canonical,
        "git",
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-qm",
        "baseline");
    generations = new CorrectionWorkspaceTransaction();
    routing = mock(SandboxCommandRoutingService.class);
    physicalAuthority = mock(VerificationSandboxAuthority.class);
    when(physicalAuthority.runId()).thenReturn("run-1");
    when(physicalAuthority.workspaceRoot()).thenReturn(disposable);
    when(routing.openVerification(any(), any(), any(), any())).thenReturn(physicalAuthority);
    when(routing.verificationCommandReceiptIds(physicalAuthority))
        .thenReturn(java.util.List.of(java.util.UUID.randomUUID()));
    service = new DisposableVerificationWorkspaceService(generations, routing);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn("run-1");
    WorkspaceGenerationLifecycle lifecycle = mock(WorkspaceGenerationLifecycle.class);
    WorkspaceGenerationDispatchAuthority generationAuthority =
        new WorkspaceGenerationDispatchAuthority(
            "run-1",
            "T1",
            new WorkspaceGenerationLifecycle.Prepared(java.util.UUID.randomUUID(), null, lease),
            WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
            lifecycle);
    context =
        new ExecutionContext("run-1", "T1", canonical)
            .withWorkspaceGenerationAuthority(generationAuthority);
  }

  @Test
  void commandSideEffectsStayInTheSandboxOwnedCopyAndCanonicalGenerationIsUnchanged()
      throws Exception {
    String before = generations.captureGeneration(canonical);

    var execution =
        service.execute(
            context,
            WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
            isolated -> {
              assertThat(isolated.workspaceRoot()).isEqualTo(disposable);
              assertThat(isolated.verificationSandboxAuthority()).isSameAs(physicalAuthority);
              try {
                Files.writeString(isolated.workspaceRoot().resolve("tracked.txt"), "rewritten\n");
                Files.writeString(
                    isolated.workspaceRoot().resolve("generated.tmp"), "side effect\n");
              } catch (java.io.IOException failure) {
                throw new RuntimeException(failure);
              }
              return "done";
            });

    assertThat(execution.value()).isEqualTo("done");
    assertThat(execution.sideEffectEntryCount()).isPositive();
    assertThat(execution.commandReceiptIds()).hasSize(1);
    assertThat(Files.readString(canonical.resolve("tracked.txt"))).isEqualTo("accepted\n");
    assertThat(generations.captureGeneration(canonical)).isEqualTo(before);
    assertThat(disposable).doesNotExist();
    verify(routing).activateVerification(physicalAuthority, before);
    verify(routing).closeVerification(physicalAuthority);
  }

  @Test
  void sandboxAdmissionFailureIsTypedAndNeverRunsTheCommand() {
    when(routing.openVerification(any(), any(), any(), any()))
        .thenThrow(
            new SandboxCommandRoutingService.VerificationSandboxException(
                "VERIFICATION_SANDBOX_NOT_READY"));
    java.util.concurrent.atomic.AtomicBoolean called =
        new java.util.concurrent.atomic.AtomicBoolean();

    assertThatThrownBy(
            () ->
                service.execute(
                    context,
                    WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
                    ignored -> {
                      called.set(true);
                      return null;
                    }))
        .isInstanceOf(DisposableVerificationWorkspaceService.DisposableVerificationException.class)
        .extracting(
            failure ->
                ((DisposableVerificationWorkspaceService.DisposableVerificationException) failure)
                    .kind())
        .isEqualTo(DisposableVerificationWorkspaceService.FailureKind.POLICY_DENIED);
    assertThat(called).isFalse();
    verify(routing, never()).activateVerification(any(), any());
  }

  @Test
  void safeRelativeSymlinkIsPreservedInDisposableGeneration() throws Exception {
    Files.createSymbolicLink(canonical.resolve("tracked-link"), Path.of("tracked.txt"));

    var execution =
        service.execute(
            context,
            WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
            isolated -> {
              Path link = isolated.workspaceRoot().resolve("tracked-link");
              assertThat(Files.isSymbolicLink(link)).isTrue();
              try {
                assertThat(Files.readSymbolicLink(link)).isEqualTo(Path.of("tracked.txt"));
              } catch (java.io.IOException failure) {
                throw new RuntimeException(failure);
              }
              return "preserved";
            });

    assertThat(execution.value()).isEqualTo("preserved");
    assertThat(execution.canonicalGenerationBefore())
        .isEqualTo(execution.disposableGenerationBefore());
  }

  @Test
  void escapingSymlinkIsRefusedBeforeSandboxAdmission() throws Exception {
    Files.createSymbolicLink(canonical.resolve("escape"), Path.of("../../outside"));

    assertThatThrownBy(
            () ->
                service.execute(
                    context,
                    WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
                    ignored -> null))
        .isInstanceOf(DisposableVerificationWorkspaceService.DisposableVerificationException.class)
        .extracting(
            failure ->
                ((DisposableVerificationWorkspaceService.DisposableVerificationException) failure)
                    .kind())
        .isEqualTo(DisposableVerificationWorkspaceService.FailureKind.POLICY_DENIED);
    verify(routing, never()).openVerification(any(), any(), any(), any());
  }

  @Test
  void cleanupFailureIsSuppressedBehindTheAuthoritativeCommandFailure() {
    doThrow(new IllegalStateException("teardown failed"))
        .when(routing)
        .closeVerification(physicalAuthority);
    IllegalArgumentException primary = new IllegalArgumentException("command failed");

    assertThatThrownBy(
            () ->
                service.execute(
                    context,
                    WorkspaceGenerationDispatchAuthority.Phase.VERIFICATION,
                    ignored -> {
                      throw primary;
                    }))
        .isSameAs(primary)
        .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
  }

  private static void run(Path cwd, String... argv) throws Exception {
    Process process = new ProcessBuilder(argv).directory(cwd.toFile()).start();
    assertThat(process.waitFor()).isZero();
  }
}
