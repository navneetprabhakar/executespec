package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.CorrectionWorkspaceTransaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceIgnoredArtifactGuardTest {

  @TempDir Path workspace;

  @Test
  void removesEveryIgnoredRootSoNoUnattestedDependencyStateCrossesTasks() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(
        workspace.resolve(".gitignore"),
        "target/\ndist/\nscratch/\nbaseline-cache/\n",
        StandardCharsets.UTF_8);
    Files.writeString(workspace.resolve("source.txt"), "source\n");
    git("add", "-A");
    git("commit", "-m", "baseline");

    Files.createDirectories(workspace.resolve("target"));
    Files.writeString(workspace.resolve("target/stale.class"), "stale");
    Files.createDirectories(workspace.resolve("baseline-cache"));
    Files.writeString(workspace.resolve("baseline-cache/dependency.bin"), "keep");

    CorrectionWorkspaceTransaction transactions = new CorrectionWorkspaceTransaction();
    CorrectionWorkspaceTransaction.Checkpoint checkpoint = transactions.begin(workspace);
    WorkspaceIgnoredArtifactGuard guard = new WorkspaceIgnoredArtifactGuard(new ObjectMapper());
    WorkspaceIgnoredArtifactGuard.Baseline baseline = guard.prepare(checkpoint, () -> {});

    assertThat(workspace.resolve("target")).doesNotExist();
    assertThat(workspace.resolve("baseline-cache")).doesNotExist();

    Files.createDirectories(workspace.resolve("dist"));
    Files.writeString(workspace.resolve("dist/app.js"), "generated");
    Files.createDirectories(workspace.resolve("scratch"));
    Files.writeString(workspace.resolve("scratch/hidden.txt"), "hidden");

    var removed = guard.cleanAttemptArtifacts(checkpoint, baseline.json(), () -> {});

    assertThat(workspace.resolve("dist")).doesNotExist();
    assertThat(workspace.resolve("scratch")).doesNotExist();
    assertThat(removed).anyMatch(path -> path.endsWith(":scratch"));
  }

  @Test
  void cleansIgnoredSiblingsWithoutDeletingNestedRepositories() throws Exception {
    git("init", "-b", "main");
    git("config", "user.email", "test@executespec.local");
    git("config", "user.name", "ExecuteSpec Test");
    Files.writeString(workspace.resolve("source.txt"), "source\n");
    git("add", "-A");
    git("commit", "-m", "baseline");

    Path nested = Files.createDirectories(workspace.resolve("repos/api"));
    git(nested, "init", "-b", "main");
    git(nested, "config", "user.email", "test@executespec.local");
    git(nested, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(nested.resolve("api.txt"), "accepted\n");
    git(nested, "add", "-A");
    git(nested, "commit", "-m", "api baseline");
    CorrectionWorkspaceTransaction.Checkpoint checkpoint =
        new CorrectionWorkspaceTransaction().begin(workspace);

    Files.writeString(workspace.resolve(".gitignore"), "repos/\n", StandardCharsets.UTF_8);
    Path hiddenSibling = Files.createDirectories(workspace.resolve("repos/cache"));
    Files.writeString(hiddenSibling.resolve("state.bin"), "unattested");

    new WorkspaceIgnoredArtifactGuard(new ObjectMapper()).prepare(checkpoint, () -> {});

    assertThat(nested.resolve("api.txt")).hasContent("accepted\n");
    assertThat(hiddenSibling).doesNotExist();
  }

  private String git(String... args) throws Exception {
    return git(workspace, args);
  }

  private static String git(Path directory, String... args) throws Exception {
    java.util.List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(java.util.List.of(args));
    Process process =
        new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (process.waitFor() != 0) throw new AssertionError(String.join(" ", command) + ": " + output);
    return output.trim();
  }
}
