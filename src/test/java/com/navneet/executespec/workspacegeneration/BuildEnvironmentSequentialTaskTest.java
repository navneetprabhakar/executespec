package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.tool.ExecutionContext;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Proves task N+1 reconstructs every supported environment after task N ignored-byte cleanup. */
class BuildEnvironmentSequentialTaskTest {

  @TempDir Path temporary;

  @ParameterizedTest(name = "{0}")
  @MethodSource("ecosystems")
  void secondTaskRematerializesAfterFirstTaskCleanup(String ecosystem, List<FixtureFile> manifests)
      throws Exception {
    Path workspace = Files.createDirectories(temporary.resolve(ecosystem.toLowerCase()));
    git(workspace, "init", "-b", "main");
    git(workspace, "config", "user.email", "test@executespec.local");
    git(workspace, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(
        workspace.resolve(".gitignore"),
        ".executespec/environment/\nnode_modules/\ntarget/\nbuild/\n.gradle/\n");
    for (FixtureFile file : manifests) {
      Path target = workspace.resolve(file.path());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.content());
    }
    Files.writeString(workspace.resolve("product.txt"), "accepted product\n");
    git(workspace, "add", "-A");
    git(workspace, "commit", "-m", "baseline");

    SandboxCommandRoutingService routing = mock(SandboxCommandRoutingService.class);
    when(routing.runtimeImageIdentity()).thenReturn("toolchain@sha256:" + "a".repeat(64));
    Path marker = workspace.resolve(".executespec/environment/ready-" + ecosystem);
    when(routing.executeShell(anyString(), any(), anyString(), any(Duration.class)))
        .thenAnswer(
            invocation -> {
              Files.createDirectories(marker.getParent());
              Files.writeString(marker, "materialized\n");
              return SandboxExecResult.completed(0, "", "", false, Duration.ofMillis(1));
            });
    ObjectMapper json = new ObjectMapper();
    CorrectionWorkspaceTransaction generations = new CorrectionWorkspaceTransaction();
    BuildEnvironmentMaterializer materializer =
        new BuildEnvironmentMaterializer(
            new BuildEnvironmentPlanResolver(json, routing),
            routing,
            generations,
            mock(BuildEnvironmentReceiptStore.class),
            json);
    WorkspaceIgnoredArtifactGuard ignored = new WorkspaceIgnoredArtifactGuard(json);
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = generations.begin(workspace);
    String accepted = checkpoint.generation();

    materializer.materialize(
        new ExecutionContext("run-r6-" + ecosystem, "T1", workspace),
        UUID.randomUUID(),
        BuildEnvironmentMaterializer.Phase.WORKER,
        () -> {});
    assertThat(marker).exists();
    assertThat(generations.captureGeneration(workspace)).isEqualTo(accepted);

    WorkspaceIgnoredArtifactGuard.Baseline baseline = ignored.capture(checkpoint, () -> {});
    ignored.cleanAttemptArtifacts(checkpoint, baseline.json(), baseline.digest(), () -> {});
    assertThat(marker).doesNotExist();

    materializer.materialize(
        new ExecutionContext("run-r6-" + ecosystem, "T2", workspace),
        UUID.randomUUID(),
        BuildEnvironmentMaterializer.Phase.WORKER,
        () -> {});
    assertThat(marker).exists();
    assertThat(generations.captureGeneration(workspace)).isEqualTo(accepted);
    verify(routing, times(2)).executeShell(anyString(), any(), anyString(), any(Duration.class));
  }

  private static Stream<Arguments> ecosystems() {
    return Stream.of(
        Arguments.of(
            "NODE",
            List.of(
                file("package.json", "{\"dependencies\":{\"left-pad\":\"1.3.0\"}}"),
                file("package-lock.json", "{}"))),
        Arguments.of("MAVEN", List.of(file("pom.xml", "<project/>"))),
        Arguments.of("GRADLE", List.of(file("build.gradle", "plugins {}"))),
        Arguments.of(
            "RUST",
            List.of(
                file("Cargo.toml", "[package]\nname='fixture'\nversion='0.1.0'"),
                file("Cargo.lock", "version = 3"))),
        Arguments.of(
            "GO",
            List.of(file("go.mod", "module example.test/fixture\n"), file("go.sum", "fixture"))),
        Arguments.of("PYTHON", List.of(file("requirements.txt", "fastapi==0.115.12\n"))));
  }

  private static FixtureFile file(String path, String content) {
    return new FixtureFile(path, content);
  }

  private static String git(Path workspace, String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process =
        new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
    return output;
  }

  private record FixtureFile(String path, String content) {}
}
