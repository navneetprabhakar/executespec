package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1283 — a post-merge agent committing one generated artefact must not sweep the rest of the
 * concurrently shared run workspace into its commit. Exercises real git.
 */
class GitToolCommitScopeTest {

  @TempDir Path root;

  private GitTool gitTool;
  private Path workspace;

  private static final String RUN_ID = "run-commit-scope";

  @BeforeEach
  void setUp() throws Exception {
    gitTool = new GitTool(null);
    ReflectionTestUtils.setField(gitTool, "workspaceRoot", root.toString());
    workspace = root.resolve(RUN_ID);
    Files.createDirectories(workspace.resolve("api-docs"));
    run("git", "init", "-q");
    run("git", "config", "user.email", "t@example.com");
    run("git", "config", "user.name", "t");
    run("git", "config", "commit.gpgsign", "false");
    Files.writeString(workspace.resolve("README.md"), "seed\n");
    run("git", "add", "README.md");
    run("git", "commit", "-qm", "seed");
  }

  private String run(String... cmd) throws Exception {
    Process p =
        new ProcessBuilder(cmd).directory(workspace.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    p.waitFor();
    return out;
  }

  @Test
  void commitContainsOnlyTheNamedPathEvenWhenTheWorkspaceIsDirty() throws Exception {
    Files.writeString(workspace.resolve("api-docs/openapi.yaml"), "openapi: 3.0.0\n");
    // Another post-merge agent writing concurrently into the same workspace.
    Files.createDirectories(workspace.resolve(".agent-context"));
    Files.writeString(workspace.resolve(".agent-context/raw-model-output.txt"), "REJECTED\n");
    Files.writeString(workspace.resolve("customer-secret-notes.txt"), "unrelated customer file\n");

    GitTool.CommitOutcome outcome =
        gitTool.commitPaths(RUN_ID, "chore: openapi", java.util.List.of("api-docs/openapi.yaml"));

    assertThat(outcome.status()).isEqualTo(GitTool.CommitOutcome.Status.COMMITTED);
    assertThat(outcome.sha()).isNotBlank();

    String committed = run("git", "show", "--name-only", "--pretty=format:", "HEAD").trim();
    assertThat(committed).isEqualTo("api-docs/openapi.yaml");
    assertThat(committed).doesNotContain(".agent-context").doesNotContain("customer-secret-notes");

    // The unrelated files are still untracked — nothing was silently published.
    assertThat(run("git", "status", "--porcelain"))
        .contains("customer-secret-notes.txt")
        .contains(".agent-context");
  }

  /** Another thread leaving entries staged in the shared index must not join this commit. */
  @Test
  void concurrentlyStagedUnrelatedIndexEntriesAreExcluded() throws Exception {
    Files.writeString(workspace.resolve("api-docs/openapi.yaml"), "openapi: 3.0.0\n");
    Files.writeString(workspace.resolve("half-written.tmp"), "partial\n");
    run("git", "add", "half-written.tmp");

    GitTool.CommitOutcome outcome =
        gitTool.commitPaths(RUN_ID, "chore: openapi", java.util.List.of("api-docs/openapi.yaml"));

    assertThat(outcome.status()).isEqualTo(GitTool.CommitOutcome.Status.COMMITTED);
    assertThat(run("git", "show", "--name-only", "--pretty=format:", "HEAD").trim())
        .isEqualTo("api-docs/openapi.yaml");
  }

  @Test
  void anUnchangedArtefactReportsNothingToCommitRatherThanFailure() throws Exception {
    Files.writeString(workspace.resolve("api-docs/openapi.yaml"), "openapi: 3.0.0\n");
    gitTool.commitPaths(RUN_ID, "chore: openapi", java.util.List.of("api-docs/openapi.yaml"));

    GitTool.CommitOutcome second =
        gitTool.commitPaths(RUN_ID, "chore: openapi", java.util.List.of("api-docs/openapi.yaml"));

    assertThat(second.status()).isEqualTo(GitTool.CommitOutcome.Status.NOTHING_TO_COMMIT);
    assertThat(second.persisted()).isTrue();
  }

  @Test
  void missingOrEscapingPathsAreRefusedBeforeAnyGitMutation() {
    assertThat(gitTool.commitPaths(RUN_ID, "m", java.util.List.of("api-docs/absent.yaml")).status())
        .isEqualTo(GitTool.CommitOutcome.Status.FAILED);
    assertThat(gitTool.commitPaths(RUN_ID, "m", java.util.List.of("../../etc/passwd")).status())
        .isEqualTo(GitTool.CommitOutcome.Status.FAILED);
    assertThat(gitTool.commitPaths(RUN_ID, "m", java.util.List.of()).status())
        .isEqualTo(GitTool.CommitOutcome.Status.FAILED);
  }
}
