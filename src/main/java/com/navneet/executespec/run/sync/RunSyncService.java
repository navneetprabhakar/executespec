package com.navneet.executespec.run.sync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.harness.WorkspaceInitService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Refreshes a finished run's workspace from a chosen branch on origin. Used by the dashboard "Sync
 * from branch" button after a PR has been merged externally so the user can re-run review / tests /
 * preview against the post-merge code without restarting the whole pipeline.
 *
 * <p>Only safe on terminal runs — the API gates this; the service trusts the caller. Uses {@code
 * git fetch} + {@code git reset --hard} so uncommitted local edits are intentionally wiped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunSyncService {

  private final WorkspaceInitService workspaceInit;
  private final com.navneet.executespec.state.repository.RunRepoSnapshotRepository snapshotRepo;

  public record SyncOutcome(int reposSynced, List<String> details) {}

  /**
   * Reset each repo in a run workspace back to the exact commit SHA it was cloned from at run
   * start. Relies on {@code run_repo_snapshot} rows written by {@link
   * WorkspaceInitService#cloneMultipleRepos}. Pre-v2.5.2 runs have no snapshots and can't be
   * reset-to-original — caller gets an empty {@code details} list, {@code reposSynced=0}.
   *
   * <p>Safe only on terminal runs — API layer enforces that invariant.
   */
  public SyncOutcome syncToOriginalCommit(String runId) {
    Path runRoot = workspaceInit.runWorkspacePath(runId);
    if (!Files.isDirectory(runRoot)) {
      throw new IllegalStateException("Run workspace not found: " + runRoot);
    }
    List<com.navneet.executespec.state.entity.RunRepoSnapshot> snaps =
        snapshotRepo.findByRunId(runId);
    if (snaps.isEmpty()) {
      return new SyncOutcome(0, List.of("no snapshot — run predates v2.5.2"));
    }
    List<String> details = new ArrayList<>();
    int synced = 0;
    // Multi-repo workspaces have repos/<name>/ subdirectories; single-repo
    // runs use the root. Snapshot.repoFullName's short name maps to the
    // subdirectory name used by cloneMultipleRepos.
    for (var snap : snaps) {
      String shortName =
          snap.getRepoFullName().contains("/")
              ? snap.getRepoFullName().substring(snap.getRepoFullName().indexOf('/') + 1)
              : snap.getRepoFullName();
      Path repoRoot = runRoot.resolve("repos").resolve(shortName);
      if (!Files.isDirectory(repoRoot.resolve(".git"))) {
        // Fall back to the run root for legacy single-repo layouts.
        if (Files.isDirectory(runRoot.resolve(".git"))) repoRoot = runRoot;
        else {
          details.add(shortName + " MISSING (no .git found)");
          continue;
        }
      }
      try {
        runGit(repoRoot, "git", "fetch", "origin");
        runGit(repoRoot, "git", "reset", "--hard", snap.getCommitSha());
        synced++;
        String sha7 =
            snap.getCommitSha().length() > 7
                ? snap.getCommitSha().substring(0, 7)
                : snap.getCommitSha();
        details.add(shortName + " <- " + sha7 + " (original)");
        log.info(
            "RunSync(original): run={} repo={} reset to {}", runId, shortName, snap.getCommitSha());
      } catch (Exception e) {
        details.add(shortName + " FAILED: " + e.getMessage());
        log.warn("RunSync(original): run={} repo={} failed: {}", runId, shortName, e.getMessage());
      }
    }
    return new SyncOutcome(synced, details);
  }

  public SyncOutcome sync(String runId, String branchOverride) {
    Path runRoot = workspaceInit.runWorkspacePath(runId);
    if (!Files.isDirectory(runRoot)) {
      throw new IllegalStateException("Run workspace not found: " + runRoot);
    }
    List<Path> repoRoots = enumerateRepoRoots(runRoot);
    List<String> details = new ArrayList<>();
    int synced = 0;
    for (Path repoRoot : repoRoots) {
      String name = repoRoot.equals(runRoot) ? "(root)" : repoRoot.getFileName().toString();
      String branch =
          (branchOverride != null && !branchOverride.isBlank())
              ? branchOverride
              : detectDefaultBranch(repoRoot);
      try {
        runGit(repoRoot, "git", "fetch", "origin", branch);
        runGit(repoRoot, "git", "reset", "--hard", "origin/" + branch);
        synced++;
        details.add(name + " <- origin/" + branch);
        log.info("RunSync: run={} repo={} synced to origin/{}", runId, name, branch);
      } catch (Exception e) {
        details.add(name + " FAILED: " + e.getMessage());
        log.warn("RunSync: run={} repo={} failed: {}", runId, name, e.getMessage());
      }
    }
    return new SyncOutcome(synced, details);
  }

  /**
   * Multi-repo workspaces have {@code repos/<name>/} subdirectories; single-repo runs use the
   * workspace root. Returns the right set of git roots to refresh.
   */
  private List<Path> enumerateRepoRoots(Path runRoot) {
    Path multiBase = runRoot.resolve("repos");
    if (Files.isDirectory(multiBase)) {
      try (var stream = Files.list(multiBase)) {
        List<Path> out = new ArrayList<>();
        stream
            .filter(Files::isDirectory)
            .filter(p -> Files.isDirectory(p.resolve(".git")))
            .forEach(out::add);
        if (!out.isEmpty()) return out;
      } catch (Exception ignore) {
      }
    }
    if (Files.isDirectory(runRoot.resolve(".git"))) return List.of(runRoot);
    return List.of();
  }

  private String detectDefaultBranch(Path repoRoot) {
    try {
      // Resolve symbolic ref of origin/HEAD: outputs "refs/remotes/origin/<branch>"
      ProcessBuilder pb =
          new ProcessBuilder("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
              .directory(repoRoot.toFile())
              .redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes()).trim();
      p.waitFor();
      if (out.startsWith("origin/")) return out.substring("origin/".length());
    } catch (Exception ignore) {
    }
    return "main";
  }

  private void runGit(Path dir, String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      throw new RuntimeException("git " + String.join(" ", cmd) + " failed: " + out);
    }
  }
}
