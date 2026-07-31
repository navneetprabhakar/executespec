package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorkspaceToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path workspace;

  @Test
  void statusAndDiffUseNestedBrownfieldRepositoryInsteadOfOuterWorkspace() throws Exception {
    run(workspace, "git", "init", "-q");
    Path repo = workspace.resolve("repos/montecarlo");
    Files.createDirectories(repo);
    run(repo, "git", "init", "-q");
    Files.writeString(repo.resolve("tracked.txt"), "before\n");
    run(repo, "git", "add", "tracked.txt");
    run(
        repo,
        "git",
        "-c",
        "user.name=ExecuteSpec Test",
        "-c",
        "user.email=test@executespec.ai",
        "commit",
        "-qm",
        "baseline");
    Files.writeString(repo.resolve("tracked.txt"), "after\n");
    Files.writeString(repo.resolve("new.txt"), "new\n");

    ExecutionContext context = new ExecutionContext("run", "task", workspace.toAbsolutePath());
    ToolResult status = new GitStatusTool().execute(JSON.createObjectNode(), context);
    ToolResult diff = new GitDiffTool().execute(JSON.createObjectNode(), context);

    assertThat(status).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) status).content())
        .contains("## repos/montecarlo", " M tracked.txt", "?? new.txt")
        .doesNotContain("?? repos/");
    assertThat(diff).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) diff).content())
        .contains("## repos/montecarlo", "-before", "+after");
  }

  @Test
  void prefixedDiffPathRoutesIntoMatchingNestedRepository() throws Exception {
    Path repo = workspace.resolve("repos/service");
    Files.createDirectories(repo);
    run(repo, "git", "init", "-q");
    Files.writeString(repo.resolve("a.txt"), "one\n");
    Files.writeString(repo.resolve("b.txt"), "one\n");
    run(repo, "git", "add", ".");
    run(
        repo,
        "git",
        "-c",
        "user.name=ExecuteSpec Test",
        "-c",
        "user.email=test@executespec.ai",
        "commit",
        "-qm",
        "baseline");
    Files.writeString(repo.resolve("a.txt"), "two\n");
    Files.writeString(repo.resolve("b.txt"), "two\n");

    var args = JSON.createObjectNode().put("path", "repos/service/a.txt");
    ToolResult result =
        new GitDiffTool()
            .execute(args, new ExecutionContext("run", "task", workspace.toAbsolutePath()));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content())
        .contains("a.txt", "+two")
        .doesNotContain("b.txt");
  }

  private static void run(Path directory, String... command) throws Exception {
    Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
    int exit = process.waitFor();
    assertThat(exit).withFailMessage(new String(process.getErrorStream().readAllBytes())).isZero();
  }
}
