package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * RR-1668 — feature-branch pushes must not unconditionally {@code --force}. These are real-git
 * tests (no mocks) driving {@link GitTool#commitAndPushSubdir} against a bare "remote" in a {@link
 * TempDir}, proving the three behaviours the fix must guarantee:
 *
 * <ol>
 *   <li>a lease conflict — the remote branch advanced past the SHA this run cloned it from — ABORTS
 *       the push and leaves the human's commit intact;
 *   <li>a fresh branch the run created still pushes (create, no lease conflict);
 *   <li>the happy path where the remote is still at the cloned SHA force-updates as before.
 * </ol>
 */
class GitToolForceWithLeaseTest {

  @TempDir Path tmp;

  private GitTool newGitTool(Path workspaceRoot) {
    GitTool tool = new GitTool(null); // commitAndPushSubdir never touches the redis lock
    ReflectionTestUtils.setField(tool, "workspaceRoot", workspaceRoot.toString());
    return tool;
  }

  // ---- git helpers -------------------------------------------------------------------------

  private String git(Path dir, String... args) throws IOException, InterruptedException {
    java.util.List<String> cmd = new java.util.ArrayList<>();
    cmd.add("git");
    cmd.add("-C");
    cmd.add(dir.toString());
    cmd.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    pb.environment().put("GIT_AUTHOR_NAME", "t");
    pb.environment().put("GIT_AUTHOR_EMAIL", "t@t.t");
    pb.environment().put("GIT_COMMITTER_NAME", "t");
    pb.environment().put("GIT_COMMITTER_EMAIL", "t@t.t");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " -> " + exit + "\n" + out);
    }
    return out.trim();
  }

  /** A bare remote plus a clean clone that has one commit on {@code main}. */
  private record Fixture(Path remote, Path repo, String cloneSha) {}

  private Fixture initRemoteAndClone(Path remote, Path repo) throws Exception {
    git(remote.getParent(), "init", "--bare", remote.getFileName().toString());
    git(repo.getParent(), "clone", remote.toString(), repo.getFileName().toString());
    Files.writeString(repo.resolve("f.txt"), "v1\n");
    git(repo, "add", "f.txt");
    git(repo, "commit", "-m", "c1");
    git(repo, "branch", "-M", "main");
    git(repo, "push", remote.toString(), "main");
    String sha = git(repo, "rev-parse", "HEAD");
    return new Fixture(remote, repo, sha);
  }

  private String remoteTip(Path remote, String branch) throws Exception {
    // "<sha>\trefs/heads/<branch>"
    String line = git(remote, "show-ref", "refs/heads/" + branch);
    return line.split("\\s+")[0];
  }

  private boolean remoteHasBranch(Path remote, String branch) {
    try {
      git(remote, "show-ref", "--verify", "refs/heads/" + branch);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  // ---- (1) lease conflict aborts -----------------------------------------------------------

  @Test
  void leaseConflictAbortsAndPreservesTheHumanCommit() throws Exception {
    // Layout: workspaceRoot=<tmp>/ws, run repo at <tmp>/ws/run1/repos/app, remote at
    // <tmp>/remote.git
    Path remote = tmp.resolve("remote.git");
    Path ws = tmp.resolve("ws");
    Path repo = ws.resolve("run1").resolve("repos").resolve("app");
    Files.createDirectories(repo.getParent());
    Fixture fx = initRemoteAndClone(remote, repo);

    // The run "cloned" branch feat at the current SHA; publish feat to the remote at that SHA.
    git(repo, "checkout", "-b", "feat");
    git(repo, "push", remote.toString(), "feat");
    String clonedFeatSha = fx.cloneSha();
    assertThat(remoteTip(remote, "feat")).isEqualTo(clonedFeatSha);

    // A human pushes a NEW commit to feat AFTER the run cloned it (via an independent clone).
    Path human = tmp.resolve("human");
    git(tmp, "clone", remote.toString(), "human");
    git(human, "checkout", "feat");
    Files.writeString(human.resolve("human.txt"), "human work\n");
    git(human, "add", "human.txt");
    git(human, "commit", "-m", "human commit");
    git(human, "push", remote.toString(), "feat");
    String humanSha = remoteTip(remote, "feat");
    assertThat(humanSha).isNotEqualTo(clonedFeatSha);

    // The run now tries to publish feat, pinned to the SHA it cloned. Make a local change first.
    git(repo, "checkout", "main");
    Files.writeString(repo.resolve("f.txt"), "run change\n");

    GitToolResult result =
        newGitTool(ws)
            .commitAndPushSubdir(
                "run1", "repos/app", remote.toString(), null, "feat", "run commit", clonedFeatSha);

    assertThat(result.isOk()).as("push must be refused, not forced").isFalse();
    assertThat(result.value()).contains("Refusing to overwrite");
    // The human's commit is still the remote tip — nothing was overwritten.
    assertThat(remoteTip(remote, "feat")).isEqualTo(humanSha);
  }

  // ---- (2) fresh branch still pushes -------------------------------------------------------

  @Test
  void freshBranchTheRunCreatedStillPushes() throws Exception {
    Path remote = tmp.resolve("remote.git");
    Path ws = tmp.resolve("ws");
    Path repo = ws.resolve("run1").resolve("repos").resolve("app");
    Files.createDirectories(repo.getParent());
    initRemoteAndClone(remote, repo);

    // A brand-new branch this run created — no snapshot, so expectedRemoteSha is null.
    Files.writeString(repo.resolve("f.txt"), "run change\n");
    GitToolResult result =
        newGitTool(ws)
            .commitAndPushSubdir(
                "run1",
                "repos/app",
                remote.toString(),
                null,
                "executespec/run1abc",
                "run commit",
                null);

    assertThat(result.isOk()).as("fresh branch create must succeed").isTrue();
    assertThat(remoteHasBranch(remote, "executespec/run1abc")).isTrue();
  }

  // ---- (3) happy path: remote == cloned SHA still force-updates -----------------------------

  @Test
  void whenRemoteStillAtClonedShaTheLeasedPushForceUpdates() throws Exception {
    Path remote = tmp.resolve("remote.git");
    Path ws = tmp.resolve("ws");
    Path repo = ws.resolve("run1").resolve("repos").resolve("app");
    Files.createDirectories(repo.getParent());
    Fixture fx = initRemoteAndClone(remote, repo);

    // The run cloned feat2 at cloneSha and publishes it there; NO human touches it afterwards.
    git(repo, "checkout", "-b", "feat2");
    git(repo, "push", remote.toString(), "feat2");
    String clonedSha = fx.cloneSha();
    assertThat(remoteTip(remote, "feat2")).isEqualTo(clonedSha);

    // Run makes a change and publishes feat2 pinned to the cloned SHA (lease matches).
    git(repo, "checkout", "main");
    Files.writeString(repo.resolve("f.txt"), "run change\n");
    GitToolResult result =
        newGitTool(ws)
            .commitAndPushSubdir(
                "run1", "repos/app", remote.toString(), null, "feat2", "run commit", clonedSha);

    assertThat(result.isOk()).as("happy-path leased push must succeed").isTrue();
    assertThat(remoteTip(remote, "feat2"))
        .as("remote feat2 must have moved to the run's new commit")
        .isNotEqualTo(clonedSha);
  }
}
