package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildEnvironmentMaterializerTest {

  @TempDir Path workspace;

  private SandboxCommandRoutingService routing;
  private BuildEnvironmentReceiptStore receipts;
  private BuildEnvironmentMaterializer materializer;
  private WorkspaceIgnoredArtifactGuard ignored;
  private CorrectionWorkspaceTransaction generations;

  @BeforeEach
  void setUp() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve(".gitignore"), "node_modules/\n");
    Files.writeString(
        workspace.resolve("package.json"), "{\"dependencies\":{\"left-pad\":\"1.3.0\"}}");
    Files.writeString(workspace.resolve("package-lock.json"), "{}");
    Files.writeString(workspace.resolve("app.js"), "export const value = 1;\n");
    git("add", "-A");
    git("commit", "-m", "baseline");
    routing = mock(SandboxCommandRoutingService.class);
    when(routing.runtimeImageIdentity()).thenReturn("toolchain@sha256:" + "a".repeat(64));
    receipts = mock(BuildEnvironmentReceiptStore.class);
    generations = new CorrectionWorkspaceTransaction();
    ObjectMapper json = new ObjectMapper();
    materializer =
        new BuildEnvironmentMaterializer(
            new BuildEnvironmentPlanResolver(json, routing), routing, generations, receipts, json);
    ignored = new WorkspaceIgnoredArtifactGuard(json);
  }

  @Test
  void twoSequentialTasksRematerializeAfterIgnoredCleanupWithoutChangingProductGeneration()
      throws Exception {
    when(routing.executeShell(anyString(), any(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              Path dependency = Files.createDirectories(workspace.resolve("node_modules/left-pad"));
              Files.writeString(dependency.resolve("index.js"), "module.exports = x => x");
              return SandboxExecResult.completed(0, "", "", false, Duration.ofMillis(10));
            });
    ExecutionContext context = new ExecutionContext("run-r6", "T1", workspace.toAbsolutePath());
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = generations.begin(workspace);
    String acceptedGeneration = checkpoint.generation();

    BuildEnvironmentMaterializer.Receipt first =
        materializer.materialize(
            context, UUID.randomUUID(), BuildEnvironmentMaterializer.Phase.WORKER, () -> {});
    assertThat(first.generationAfter()).isEqualTo(acceptedGeneration);
    assertThat(workspace.resolve("node_modules/left-pad/index.js")).exists();

    WorkspaceIgnoredArtifactGuard.Baseline baseline = ignored.capture(checkpoint, () -> {});
    ignored.cleanAttemptArtifacts(checkpoint, baseline.json(), baseline.digest(), () -> {});
    assertThat(workspace.resolve("node_modules")).doesNotExist();

    BuildEnvironmentMaterializer.Receipt second =
        materializer.materialize(
            new ExecutionContext("run-r6", "T2", workspace.toAbsolutePath()),
            UUID.randomUUID(),
            BuildEnvironmentMaterializer.Phase.WORKER,
            () -> {});
    assertThat(second.generationAfter()).isEqualTo(acceptedGeneration);
    assertThat(workspace.resolve("node_modules/left-pad/index.js")).exists();
    verify(routing, times(2)).executeShell(anyString(), any(), anyString(), any(Duration.class));
  }

  @Test
  void installerThatChangesTrackedProductBytesIsRejectedAndReceipted() throws Exception {
    when(routing.executeShell(anyString(), any(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              Files.writeString(workspace.resolve("app.js"), "export const value = 2;\n");
              return SandboxExecResult.completed(0, "", "", false, Duration.ofMillis(10));
            });

    assertThatThrownBy(
            () ->
                materializer.materialize(
                    new ExecutionContext("run-r6", "T1", workspace.toAbsolutePath()),
                    UUID.randomUUID(),
                    BuildEnvironmentMaterializer.Phase.WORKER,
                    () -> {}))
        .isInstanceOf(BuildEnvironmentMaterializer.BuildEnvironmentException.class)
        .hasMessage("BUILD_ENVIRONMENT_CHANGED_PRODUCT_GENERATION");
    verify(receipts)
        .recordMaterialization(
            any(),
            anyString(),
            any(),
            org.mockito.ArgumentMatchers.eq("PRODUCT_MUTATION_REFUSED"),
            anyString(),
            anyString());
  }

  @Test
  void sandboxRoutingExceptionIsAnInternalProtocolReceiptNotAProductFailure() {
    when(routing.executeShell(anyString(), any(), anyString(), any(Duration.class)))
        .thenThrow(new IllegalStateException("container dispatcher crashed"));

    assertThatThrownBy(
            () ->
                materializer.materialize(
                    new ExecutionContext("run-r6", "T1", workspace.toAbsolutePath()),
                    UUID.randomUUID(),
                    BuildEnvironmentMaterializer.Phase.WORKER,
                    () -> {}))
        .isInstanceOf(BuildEnvironmentMaterializer.BuildEnvironmentException.class)
        .satisfies(
            failure ->
                assertThat(
                        ((BuildEnvironmentMaterializer.BuildEnvironmentException) failure)
                            .receipt()
                            .disposition())
                    .isEqualTo(BuildEnvironmentMaterializer.Disposition.INTERNAL_PROTOCOL));
    verify(receipts)
        .recordMaterialization(
            any(),
            anyString(),
            any(),
            org.mockito.ArgumentMatchers.eq("INTERNAL_PROTOCOL"),
            anyString(),
            anyString());
  }

  private String git(String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
    return output;
  }
}
