package com.navneet.executespec.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Supplier;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class GitTool {

  /** Redis lock TTL — long enough to cover the slowest merge; released on completion. */
  private static final Duration GIT_LOCK_TTL = Duration.ofMinutes(2);

  /** Prefix for workspace-level git operation locks. */
  private static final String GIT_LOCK_PREFIX = "git:lock:workspace:";

  @Value("${agent.workspace}")
  private String workspaceRoot;

  private final StringRedisTemplate redis;

  public GitTool(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /**
   * Opens the git repo for this task's working directory. If a worktree override is active (set by
   * WorkspaceContext), opens that path; otherwise falls back to the shared workspace root.
   */
  private Git openRepo(String runId) throws IOException {
    String override = WorkspaceContext.getWorkDir(runId);
    if (override != null) return Git.open(Path.of(override).toFile());
    return Git.open(Path.of(workspaceRoot, runId).toFile());
  }

  // Worktree lifecycle (called by WorkerAgent, not exposed as @Tool)

  /**
   * Creates a git worktree for a task so it gets its own isolated working directory. The worktree
   * is branched from 'main' and dependency branches are merged in before the agentic loop starts.
   *
   * @return absolute path to the worktree directory, or the repo root on failure
   */
  public String createWorktree(
      String runId, String taskId, String branchName, java.util.List<String> depBranches) {
    Path repoRoot = Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
    Path worktreeDir = repoRoot.resolve("worktrees").resolve(taskId);
    try {
      // On retry: reset worktree to clean state before reuse
      if (java.nio.file.Files.exists(worktreeDir.resolve(".git"))) {
        log.info("[GitTool] Worktree '{}' already exists — resetting for retry", taskId);
        // Reset tracked files to branch HEAD
        new ProcessBuilder("git", "checkout", ".")
            .directory(worktreeDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor();
        // Remove untracked files and directories
        new ProcessBuilder("git", "clean", "-fd")
            .directory(worktreeDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor();
        // Re-merge dependency branches (may have been updated by retried deps)
        if (depBranches != null && !depBranches.isEmpty()) {
          for (String dep : depBranches) {
            ProcessBuilder mpb =
                new ProcessBuilder("git", "merge", "--no-edit", "-X", "theirs", dep);
            mpb.directory(worktreeDir.toFile());
            mpb.redirectErrorStream(true);
            Process mp = mpb.start();
            String mout = new String(mp.getInputStream().readAllBytes());
            int mexit = mp.waitFor();
            if (mexit != 0) {
              log.warn(
                  "[GitTool] Retry re-merge of '{}' failed in worktree {}: {}",
                  dep,
                  taskId,
                  mout.trim());
              new ProcessBuilder("git", "merge", "--abort")
                  .directory(worktreeDir.toFile())
                  .redirectErrorStream(true)
                  .start()
                  .waitFor();
            }
          }
        }
        return worktreeDir.toString();
      }
      java.nio.file.Files.createDirectories(repoRoot.resolve("worktrees"));

      boolean branchExists;
      try (Git git = Git.open(repoRoot.toFile())) {
        branchExists = git.getRepository().findRef(branchName) != null;
      }

      java.util.List<String> cmd =
          new java.util.ArrayList<>(
              java.util.List.of("git", "worktree", "add", worktreeDir.toString()));
      if (!branchExists) {
        // Create a new branch starting from main's HEAD
        cmd.add("-b");
        cmd.add(branchName);
        cmd.add("main");
      } else {
        // Checkout existing branch — no commit-ish argument allowed here
        cmd.add(branchName);
      }

      ProcessBuilder pb = new ProcessBuilder(cmd);
      pb.directory(repoRoot.toFile());
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();
      if (exit != 0) {
        log.error("[GitTool] git worktree add failed for '{}': {}", taskId, out);
        return repoRoot.toString(); // fallback
      }
      log.info("[GitTool] Created worktree '{}' at {}", taskId, worktreeDir);

      // Merge dep branches using shell git so that:
      //   1. .gitattributes merge=union is honoured for README.md / CHANGELOG.md
      //      (JGit ignores custom merge drivers, so README conflicts would still occur)
      //   2. -X theirs auto-resolves any remaining code conflicts by preferring the
      //      incoming dep branch — safe because the worktree has no local changes at
      //      this point (ours == main base), so "theirs" = dep's real content.
      if (depBranches != null && !depBranches.isEmpty()) {
        for (String dep : depBranches) {
          ProcessBuilder mpb = new ProcessBuilder("git", "merge", "--no-edit", "-X", "theirs", dep);
          mpb.directory(worktreeDir.toFile());
          mpb.redirectErrorStream(true);
          Process mp = mpb.start();
          String mout = new String(mp.getInputStream().readAllBytes());
          int mexit = mp.waitFor();
          if (mexit == 0) {
            log.info("[GitTool] Worktree {}: merged dep '{}' (shell git -X theirs)", taskId, dep);
          } else {
            log.error(
                "[GitTool] Worktree {}: CRITICAL — dep merge of '{}' failed (exit {}): {}",
                taskId,
                dep,
                mexit,
                mout.trim());
            // Abort to restore clean state
            new ProcessBuilder("git", "merge", "--abort")
                .directory(worktreeDir.toFile())
                .redirectErrorStream(true)
                .start()
                .waitFor();
            throw new RuntimeException(
                "Dependency branch merge failed for task '"
                    + taskId
                    + "': could not merge '"
                    + dep
                    + "'. Output: "
                    + mout.trim()
                    + ". The dependency task may not have committed its files correctly.");
          }
        }
      }

      // Symlink node_modules from main workspace to worktree (gitignored, not in git history).
      // Without this, frontend workers can't run `npm run build` because deps aren't installed.
      symlinkNodeModules(repoRoot, worktreeDir, taskId);

      return worktreeDir.toString();
    } catch (Exception e) {
      log.error("[GitTool] createWorktree failed for '{}': {}", taskId, e.getMessage(), e);
      return repoRoot.toString();
    }
  }

  /**
   * Symlinks node_modules directories from the main workspace into the worktree. Handles both
   * root-level (Node/React) and frontend/ subdirectory (full-stack) layouts.
   */
  private void symlinkNodeModules(Path mainWorkspace, Path worktreeDir, String taskId) {
    // Root-level node_modules (pure Node/React projects)
    symlinkIfPresent(
        mainWorkspace.resolve("node_modules"), worktreeDir.resolve("node_modules"), taskId);
    // frontend/ subdirectory (full-stack projects)
    if (java.nio.file.Files.isDirectory(worktreeDir.resolve("frontend"))) {
      symlinkIfPresent(
          mainWorkspace.resolve("frontend").resolve("node_modules"),
          worktreeDir.resolve("frontend").resolve("node_modules"),
          taskId);
    }
  }

  private void symlinkIfPresent(Path source, Path target, String taskId) {
    try {
      if (java.nio.file.Files.isDirectory(source) && !java.nio.file.Files.exists(target)) {
        java.nio.file.Files.createSymbolicLink(target, source);
        log.info("[GitTool] Symlinked {} → {} for task {}", target, source, taskId);
      }
    } catch (Exception e) {
      log.warn("[GitTool] Failed to symlink node_modules for task {}: {}", taskId, e.getMessage());
    }
  }

  /**
   * Removes ALL worktrees for a run and prunes stale worktree metadata. Called after all workers
   * complete (before the reviewer starts) to ensure no leftover worktree directories from
   * failed/partial tasks or failed merges. Non-fatal — logs warnings on individual failures.
   */
  public void pruneAllWorktrees(String runId) {
    Path repoRoot = Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
    Path worktreesDir = repoRoot.resolve("worktrees");
    if (!java.nio.file.Files.isDirectory(worktreesDir)) return;

    try (var dirs = java.nio.file.Files.list(worktreesDir)) {
      dirs.filter(java.nio.file.Files::isDirectory)
          .forEach(
              dir -> {
                String taskId = dir.getFileName().toString();
                try {
                  ProcessBuilder pb =
                      new ProcessBuilder("git", "worktree", "remove", "--force", dir.toString());
                  pb.directory(repoRoot.toFile());
                  pb.redirectErrorStream(true);
                  Process p = pb.start();
                  String out = new String(p.getInputStream().readAllBytes());
                  int exit = p.waitFor();
                  if (exit == 0) {
                    log.info("[GitTool] Pruned worktree '{}'", taskId);
                  } else {
                    log.warn(
                        "[GitTool] worktree remove exited {} for '{}': {}",
                        exit,
                        taskId,
                        out.trim());
                  }
                } catch (Exception e) {
                  log.warn(
                      "[GitTool] pruneAllWorktrees failed for '{}': {}", taskId, e.getMessage());
                }
              });
    } catch (Exception e) {
      log.warn("[GitTool] Failed to list worktrees for run {}: {}", runId, e.getMessage());
    }

    // Prune stale worktree metadata from .git/worktrees
    try {
      ProcessBuilder pb = new ProcessBuilder("git", "worktree", "prune");
      pb.directory(repoRoot.toFile());
      pb.redirectErrorStream(true);
      pb.start().waitFor();
    } catch (Exception e) {
      log.debug("[GitTool] git worktree prune failed for run {}: {}", runId, e.getMessage());
    }
  }

  /** Prunes the git worktree for a task after its branch is merged. Non-fatal. */
  public void removeWorktree(String runId, String taskId) {
    Path repoRoot = Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
    Path worktreeDir = repoRoot.resolve("worktrees").resolve(taskId);
    try {
      ProcessBuilder pb =
          new ProcessBuilder("git", "worktree", "remove", "--force", worktreeDir.toString());
      pb.directory(repoRoot.toFile());
      pb.redirectErrorStream(true);
      Process p = pb.start();
      int exit = p.waitFor();
      if (exit == 0) log.info("[GitTool] Removed worktree '{}'", taskId);
      else log.warn("[GitTool] worktree remove exited {} for '{}'", exit, taskId);
    } catch (Exception e) {
      log.warn("[GitTool] removeWorktree failed for '{}': {}", taskId, e.getMessage());
    }
  }

  @Tool(description = "Creates and checks out a new git branch in the workspace.")
  public String createBranch(
      @ToolParam(description = "runId", required = true) String runId,
      @ToolParam(description = "Branch name to create", required = true) String branchName) {
    return withWorkspaceLock(
        runId,
        () -> {
          try (Git git = openRepo(runId)) {
            // If branch already exists (e.g. task retry after recovery), just check it out
            // rather than failing with RefAlreadyExistsException.
            boolean exists = git.getRepository().findRef(branchName) != null;
            if (exists) {
              git.checkout().setName(branchName).call();
              log.info(
                  "[GitTool] Branch '{}' already exists — checked out (task retry)", branchName);
              return "Checked out existing branch: " + branchName + " (retry)";
            }
            git.checkout().setCreateBranch(true).setName(branchName).call();
            return "Created and checked out branch: " + branchName;
          } catch (RefAlreadyExistsException e) {
            // findRef() missed the branch (race condition or JGit ref-DB quirk after thread
            // interrupt). Fall back to a plain checkout instead of returning an error.
            log.warn(
                "[GitTool] RefAlreadyExistsException for '{}' — findRef missed it; checking out"
                    + " directly",
                branchName);
            try (Git git2 = openRepo(runId)) {
              git2.checkout().setName(branchName).call();
              log.info(
                  "[GitTool] Branch '{}' checked out via fallback path (task retry)", branchName);
              return "Checked out existing branch: " + branchName + " (retry-fallback)";
            } catch (Exception ce) {
              return "Branch creation failed: " + ce.getMessage();
            }
          } catch (Exception e) {
            return "Branch creation failed: " + e.getMessage();
          }
        });
  }

  @Tool(description = "Stage and commit all current changes in the workspace.")
  public String commit(
      @ToolParam(description = "runId", required = true) String runId,
      @ToolParam(description = "Commit message (format: feat(task_id): desc)", required = true)
          String message) {
    // Use shell git for linked worktrees — JGit's Git.open() on a linked
    // worktree resolves getWorkTree() to the MAIN repo root, so
    // addFilepattern(".") stages files from the wrong directory, creating
    // empty commits.
    Path workDir = getWorkspacePath(runId);
    return shellCommit(workDir, message);
  }

  @Tool(description = "Show git working tree status.")
  public String status(@ToolParam(description = "runId", required = true) String runId) {
    Path workDir = getWorkspacePath(runId);
    return shellStatus(workDir);
  }

  @Tool(description = "Show git diff of changes since last commit.")
  public String diff(@ToolParam(description = "runId", required = true) String runId) {
    Path workDir = getWorkspacePath(runId);
    return shellDiff(workDir);
  }

  // Worker-safe: merge a dependency branch into the current branch

  /**
   * Merges {@code branchName} into the currently-checked-out branch. Used by WorkerAgent to pull in
   * dependency tasks' committed work before the dependent task starts its agentic loop. Non-fatal:
   * returns an error string rather than throwing, so the worker can still proceed.
   */
  public String mergeBranchIntoCurrent(String runId, String branchName) {
    try (Git git = openRepo(runId)) {
      var ref = git.getRepository().findRef(branchName);
      if (ref == null) {
        log.warn("[GitTool] Cannot merge dependency '{}' — branch not found", branchName);
        return "Branch not found: " + branchName + " (dependency may not have committed yet)";
      }
      MergeResult result =
          git.merge()
              .include(ref)
              .setFastForward(MergeCommand.FastForwardMode.FF)
              .setCommit(true)
              .setMessage("chore: merge dependency " + branchName)
              .call();
      if (result.getMergeStatus().isSuccessful()) {
        log.info("[GitTool] Merged dependency '{}' into current branch", branchName);
        return "Merged dependency: " + branchName;
      } else {
        log.warn(
            "[GitTool] Conflict merging dependency '{}': {}", branchName, result.getMergeStatus());
        return "Merge conflict on " + branchName + ": " + result.getMergeStatus();
      }
    } catch (Exception e) {
      log.warn("[GitTool] Failed to merge dependency branch '{}': {}", branchName, e.getMessage());
      return "Merge failed for " + branchName + ": " + e.getMessage();
    }
  }

  public String mergeBranchToMain(String runId, String branchName) {
    return withWorkspaceLock(
        runId,
        () -> {
          try (Git git = openRepo(runId)) {
            git.checkout().setName("main").call();
            MergeResult result =
                git.merge()
                    .include(git.getRepository().findRef(branchName))
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                    .setCommit(true)
                    .setMessage("chore: merge " + branchName)
                    .call();
            if (result.getMergeStatus().isSuccessful()) {
              return "Merged " + branchName + " -> main";
            } else {
              return "ERROR: Merge conflict on " + branchName + ": " + result.getMergeStatus();
            }
          } catch (Exception e) {
            return "ERROR: Merge failed: " + e.getMessage();
          }
        });
  }

  /**
   * Attempts to rebase branchName onto main. If conflicts occur, it aborts the rebase and returns
   * false. Uses shell git because JGit rebase can be flaky with worktrees.
   */
  public boolean rebaseBranchOnMain(String runId, String branchName) {
    Path wsPath = getWorkspacePath(runId);
    try {
      // Check out the branch
      ProcessBuilder cb =
          new ProcessBuilder("git", "checkout", branchName)
              .directory(wsPath.toFile())
              .redirectErrorStream(true);
      cb.start().waitFor();

      // Try to rebase
      ProcessBuilder rb =
          new ProcessBuilder("git", "rebase", "main")
              .directory(wsPath.toFile())
              .redirectErrorStream(true);
      Process rp = rb.start();
      String out = new String(rp.getInputStream().readAllBytes());
      int exit = rp.waitFor();

      if (exit == 0) {
        log.info("[GitTool] Successfully rebased {} onto main", branchName);
        return true;
      } else {
        log.warn("[GitTool] Rebase conflict for {}. Aborting rebase. Output: {}", branchName, out);
        // Abort
        ProcessBuilder ab =
            new ProcessBuilder("git", "rebase", "--abort")
                .directory(wsPath.toFile())
                .redirectErrorStream(true);
        ab.start().waitFor();
        return false;
      }
    } catch (Exception e) {
      log.error("[GitTool] Rebase failed for {}: {}", branchName, e.getMessage());
      try {
        new ProcessBuilder("git", "rebase", "--abort")
            .directory(wsPath.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor();
      } catch (Exception ignore) {
      }
      return false;
    }
  }

  /**
   * RR-1283 — outcome of a scoped commit. {@code COMMITTED} carries the resulting SHA; {@code
   * NOTHING_TO_COMMIT} means the named paths were already identical to HEAD; {@code FAILED} carries
   * a reason. Callers must not report an artefact as persisted on anything but {@code COMMITTED} or
   * {@code NOTHING_TO_COMMIT}.
   */
  public record CommitOutcome(Status status, String sha, String detail) {

    public enum Status {
      COMMITTED,
      NOTHING_TO_COMMIT,
      FAILED
    }

    public boolean persisted() {
      return status == Status.COMMITTED || status == Status.NOTHING_TO_COMMIT;
    }

    static CommitOutcome committed(String sha) {
      return new CommitOutcome(Status.COMMITTED, sha, "committed");
    }

    static CommitOutcome nothingToCommit() {
      return new CommitOutcome(Status.NOTHING_TO_COMMIT, null, "no change in the named paths");
    }

    static CommitOutcome failed(String detail) {
      return new CommitOutcome(Status.FAILED, null, detail);
    }
  }

  /** Serializes index/commit operations per run workspace — the index is a single shared file. */
  private static final java.util.concurrent.ConcurrentMap<String, Object> WORKSPACE_GIT_LOCKS =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * RR-1283 — stage and commit ONLY the named workspace-relative paths.
   *
   * <p>The previous {@code commitIfDirty} ran {@code git add -A} over the entire run workspace,
   * which is shared with concurrently running post-merge agents. An "update openapi.yaml" commit
   * could therefore sweep in another agent's half-written context files, rejected model output, or
   * unrelated customer files — with the content decided by timing. Staging is now restricted to an
   * explicit path list, and the commit itself carries the same pathspec so anything another thread
   * left in the index is excluded too.
   *
   * @param relativePaths workspace-relative paths; must be non-empty, relative, and traversal-free
   */
  public CommitOutcome commitPaths(
      String runId, String message, java.util.List<String> relativePaths) {
    if (relativePaths == null || relativePaths.isEmpty()) {
      return CommitOutcome.failed("no paths supplied");
    }
    Path workDir = getWorkspacePath(runId);
    Path root = workDir.toAbsolutePath().normalize();
    for (String relative : relativePaths) {
      if (relative == null || relative.isBlank()) return CommitOutcome.failed("blank path");
      Path resolved = root.resolve(relative).normalize();
      if (!resolved.startsWith(root)) return CommitOutcome.failed("path escapes the workspace");
      if (!java.nio.file.Files.exists(resolved)) {
        return CommitOutcome.failed("path does not exist: " + relative);
      }
    }

    synchronized (WORKSPACE_GIT_LOCKS.computeIfAbsent(runId, k -> new Object())) {
      try {
        java.util.List<String> add =
            new java.util.ArrayList<>(java.util.List.of("git", "add", "--"));
        add.addAll(relativePaths);
        Process addProc =
            new ProcessBuilder(add).directory(workDir.toFile()).redirectErrorStream(true).start();
        String addOut = new String(addProc.getInputStream().readAllBytes());
        if (addProc.waitFor() != 0) return CommitOutcome.failed("git add failed: " + addOut);

        // Nothing staged for these paths → the file already matches HEAD.
        java.util.List<String> diff =
            new java.util.ArrayList<>(
                java.util.List.of("git", "diff", "--cached", "--quiet", "--"));
        diff.addAll(relativePaths);
        Process diffProc =
            new ProcessBuilder(diff).directory(workDir.toFile()).redirectErrorStream(true).start();
        diffProc.getInputStream().readAllBytes();
        if (diffProc.waitFor() == 0) return CommitOutcome.nothingToCommit();

        // Pathspec on commit: only the named paths are committed even if a concurrent agent left
        // unrelated entries staged in the shared index.
        java.util.List<String> commit =
            new java.util.ArrayList<>(java.util.List.of("git", "commit", "-m", message, "--"));
        commit.addAll(relativePaths);
        Process commitProc =
            new ProcessBuilder(commit)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String commitOut = new String(commitProc.getInputStream().readAllBytes());
        if (commitProc.waitFor() != 0)
          return CommitOutcome.failed("git commit failed: " + commitOut);

        Process revParse =
            new ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String sha = new String(revParse.getInputStream().readAllBytes()).trim();
        if (revParse.waitFor() != 0 || sha.isBlank()) {
          return CommitOutcome.failed("commit succeeded but HEAD could not be read");
        }
        log.debug("commitPaths: run {} committed {} as {}", runId, relativePaths, sha);
        return CommitOutcome.committed(sha);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return CommitOutcome.failed("interrupted");
      } catch (Exception e) {
        log.warn("commitPaths failed for run {}: {}", runId, e.getMessage());
        return CommitOutcome.failed("commit error: " + e.getMessage());
      }
    }
  }

  /**
   * Returns the diff between the merge-base of {@code baseBranch} and {@code featureBranch},
   * showing only what the feature branch actually added.
   *
   * <p>This handles the <b>progressive merge</b> scenario correctly: after each worker task branch
   * is merged into main with {@code --no-ff}, a naive {@code diff(main, branch)} returns empty
   * because main's tree already contains the branch's changes. Using the merge-base as the baseline
   * shows only the changes the branch introduced, which is what the reviewer needs to see.
   */
  public String getFullDiff(String runId, String baseBranch, String featureBranch) {
    try (Git git = openRepo(runId)) {
      // Find the common ancestor (the commit from which the branch was created).
      // After progressive merge, merge-base(main, branch) = the main commit the
      // branch was forked from, NOT main's current tip.
      ObjectId baseId = git.getRepository().resolve(baseBranch);
      ObjectId featureId = git.getRepository().resolve(featureBranch);
      if (baseId == null || featureId == null) {
        return "ERROR: Could not resolve refs — base=" + baseBranch + " feature=" + featureBranch;
      }

      String diffBase = baseBranch; // default: use baseBranch directly
      try (org.eclipse.jgit.revwalk.RevWalk walk =
          new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
        walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
        walk.markStart(walk.parseCommit(baseId));
        walk.markStart(walk.parseCommit(featureId));
        RevCommit mergeBase = walk.next();
        if (mergeBase != null) {
          diffBase = mergeBase.getName();
          log.debug(
              "[GitTool] getFullDiff: merge-base={} for {}..{}",
              mergeBase.abbreviate(7).name(),
              baseBranch,
              featureBranch);
        }
      }

      ByteArrayOutputStream out = new ByteArrayOutputStream();
      git.diff()
          .setOldTree(getTreeIterator(git, diffBase))
          .setNewTree(getTreeIterator(git, featureBranch))
          .setOutputStream(out)
          .call();
      return out.toString();
    } catch (Exception e) {
      return "ERROR: " + e.getMessage();
    }
  }

  /**
   * Tags the run's workspace repo with "agent-run/{short-id}" after a successful merge. Useful as a
   * lightweight marker; non-fatal on failure.
   */
  public void tagRun(String runId) {
    String shortId = runId.length() >= 8 ? runId.substring(0, 8) : runId;
    String tagName = "agent-run/" + shortId;
    try (Git git = openRepo(runId)) {
      git.tag().setName(tagName).setMessage("Agent pipeline run " + runId).call();
      log.info("[GitTool] Tagged run as '{}'", tagName);
    } catch (Exception e) {
      log.warn("[GitTool] Could not tag run '{}': {}", tagName, e.getMessage());
    }
  }

  /**
   * Aborts an in-progress conflicted merge, restoring the repo to a clean state. Must be called
   * after {@link #mergeBranchToMain} returns an "ERROR" result before attempting the next merge.
   * Non-fatal — logs a warning if the abort fails.
   */
  public void abortMerge(String runId) {
    String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();
    try {
      ProcessBuilder pb = new ProcessBuilder("git", "-C", wsPath, "merge", "--abort");
      pb.redirectErrorStream(true);
      Process p = pb.start();
      p.waitFor();
      log.info("[GitTool] Aborted conflicted merge for run {}", runId);
    } catch (Exception e) {
      log.warn("[GitTool] Could not abort merge for run {}: {}", runId, e.getMessage());
    }
  }

  /**
   * Pushes the main branch in the run's workspace to a remote GitHub repository. Non-fatal —
   * returns GitToolResult.err() on failure.
   *
   * <p>RR-900 — this used to pass {@code --force}, so any caller reaching it overwrote the remote
   * default branch and discarded whatever was there. The only legitimate caller is publication into
   * a repository ExecuteSpec just created (and which is therefore empty), where a fast-forward push
   * always succeeds. The force flag is gone: a rejected non-fast-forward now means the remote had
   * content we were about to destroy, and refusing is the correct outcome.
   */
  public GitToolResult pushToRemote(String runId, String repoUrl, String pat) {
    try {
      String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();
      ProcessBuilder pb =
          com.navneet.executespec.repo.GitProcessAuth.git(
              pat, java.util.List.of("-C", wsPath, "push", repoUrl, "main"));
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String output = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();
      if (exit == 0) {
        log.info("[GitTool] Pushed main to remote: {}", repoUrl);
        return GitToolResult.ok("Pushed to " + repoUrl);
      } else {
        log.warn("[GitTool] Push failed (exit {}): {}", exit, output);
        return GitToolResult.err("Push failed: " + output);
      }
    } catch (Exception e) {
      log.warn("[GitTool] Push failed: {}", describeFailure(e));
      return GitToolResult.err("Push failed: " + describeFailure(e));
    }
  }

  /**
   * RR-1668 — build the {@code git push} argument tail for a feature-branch push that must not
   * unconditionally force.
   *
   * <p>When {@code expectedRemoteSha} is a non-blank commit id (the SHA this run cloned the target
   * branch from), the push uses an EXPLICIT {@code --force-with-lease=<branch>:<sha>}. The explicit
   * expected value is mandatory: a bare {@code --force-with-lease} compares against the local
   * remote-tracking ref, which any intervening fetch (many flows fetch) can advance so the lease
   * matches whatever was just fetched and protects nothing. The explicit form pins the exact commit
   * the remote branch is expected to still be at, so a human commit pushed after the clone makes
   * the push abort ({@code stale info}) instead of being silently overwritten.
   *
   * <p>When {@code expectedRemoteSha} is null/blank the run did NOT clone this branch — it is a
   * fresh {@code executespec/<runId>} branch created off a different base (or a re-run's {@code
   * branchNamingPattern} output). There is no cloned SHA to lease against, and pinning an unrelated
   * SHA would reject even the legitimate create. So a plain, non-forced push is used: it creates
   * the branch when the remote does not have it and fast-forwards the run's own branch, but a
   * divergent remote (a re-run over a branch a human advanced) is rejected as non-fast-forward
   * rather than clobbered. Either way, no {@code --force}.
   */
  private static java.util.List<String> pushRefArgs(
      String dir, String remote, String branch, String expectedRemoteSha) {
    if (expectedRemoteSha != null && !expectedRemoteSha.isBlank()) {
      return java.util.List.of(
          "-C",
          dir,
          "push",
          remote,
          branch,
          "--force-with-lease=" + branch + ":" + expectedRemoteSha.trim());
    }
    return java.util.List.of("-C", dir, "push", remote, branch);
  }

  /**
   * RR-1668 — classify a rejected push so the failure reads as a deliberate safety refusal, not a
   * transient error, and is never retried with {@code --force}. A {@code --force-with-lease}
   * rejection surfaces as {@code stale info}; a plain non-forced push to a divergent remote
   * surfaces as {@code non-fast-forward} / {@code fetch first}.
   */
  /**
   * Never null: a message-less exception (e.g. an NPE) reports its type instead of the literal
   * string "null", which previously surfaced to customers as "subdir push failed: null".
   */
  private static String describeFailure(Exception e) {
    return e.getMessage() != null && !e.getMessage().isBlank()
        ? e.getMessage()
        : e.getClass().getSimpleName();
  }

  private static String describePushFailure(
      String branch, String expectedRemoteSha, String output) {
    String lower = output == null ? "" : output.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("stale info") || lower.contains("force-with-lease")) {
      return "Push refused (branch "
          + branch
          + "): the remote branch advanced past the commit this run started from ("
          + (expectedRemoteSha == null ? "unknown" : expectedRemoteSha.trim())
          + ") — someone else pushed to it. Refusing to overwrite; not force-pushing. "
          + output;
    }
    if (lower.contains("non-fast-forward") || lower.contains("fetch first")) {
      return "Push refused (branch "
          + branch
          + "): the remote branch already exists with commits this run did not build on. Refusing"
          + " to overwrite; not force-pushing. "
          + output;
    }
    return output;
  }

  /**
   * Creates a new branch from main and pushes it to the remote. Used for existing repos where we
   * don't want to push directly to the default branch.
   *
   * @param runId run identifier (used to locate workspace)
   * @param repoUrl HTTPS clone URL
   * @param pat GitHub PAT for authentication
   * @param branchName name for the new branch (e.g. "executespec/abc123")
   * @param expectedRemoteSha RR-1668 — the SHA this run cloned {@code branchName} from, or {@code
   *     null} when the run did not clone that branch. Non-null ⇒ {@code
   *     --force-with-lease=<branch>:<sha>}; null ⇒ a plain non-forced push (create / fast-forward
   *     only). Never an unconditional {@code --force}.
   * @return GitToolResult.ok on success, GitToolResult.err on failure
   */
  public GitToolResult pushToBranch(
      String runId, String repoUrl, String pat, String branchName, String expectedRemoteSha) {
    try {
      String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();

      // Create the branch from current HEAD (main)
      ProcessBuilder createBranch =
          new ProcessBuilder("git", "-C", wsPath, "checkout", "-b", branchName);
      createBranch.redirectErrorStream(true);
      Process p1 = createBranch.start();
      String out1 = new String(p1.getInputStream().readAllBytes());
      int exit1 = p1.waitFor();
      if (exit1 != 0) {
        log.warn("[GitTool] Branch creation failed (exit {}): {}", exit1, out1);
        return GitToolResult.err("Branch creation failed: " + out1);
      }

      // Push the branch to remote — RR-1668: force-with-lease pinned to the cloned SHA, or a plain
      // non-forced push for a fresh branch. Never an unconditional --force.
      ProcessBuilder push =
          com.navneet.executespec.repo.GitProcessAuth.git(
              pat, pushRefArgs(wsPath, repoUrl, branchName, expectedRemoteSha));
      push.redirectErrorStream(true);
      Process p2 = push.start();
      String out2 = new String(p2.getInputStream().readAllBytes());
      int exit2 = p2.waitFor();

      // Switch back to main regardless of push result. If this FAILS the workspace is left on the
      // feature branch — a later reviewer diff / getDiffSinceInit / another repo's push in a
      // multi-repo loop would then measure or publish from the wrong branch. It is a cleanup step,
      // not the push outcome, so log loudly rather than failing the (successful) push.
      checkoutMainOrWarn(wsPath, branchName);

      if (exit2 == 0) {
        log.info("[GitTool] Pushed branch {} to remote: {}", branchName, repoUrl);
        return GitToolResult.ok("Pushed branch " + branchName + " to " + repoUrl);
      } else {
        String detail = describePushFailure(branchName, expectedRemoteSha, out2);
        log.warn("[GitTool] Push branch failed (exit {}): {}", exit2, detail);
        return GitToolResult.err("Push branch failed: " + detail);
      }
    } catch (Exception e) {
      log.warn("[GitTool] Push branch failed: {}", describeFailure(e));
      return GitToolResult.err("Push branch failed: " + describeFailure(e));
    }
  }

  /**
   * Pilot v3.0 — for multi-repo runs. Each cloned subdirectory under {@code
   * workspace/<runId>/repos/<name>} is its own git repo. Stages + commits + creates a branch, then
   * publishes it. Returns OK with the branch name on success, OK with "CLEAN" if no changes, ERR
   * otherwise.
   *
   * <p>RR-1668 — {@code expectedRemoteSha} is the SHA this run cloned {@code branchName} from, or
   * {@code null} when the run did not clone that branch. Non-null ⇒ {@code
   * --force-with-lease=<branch>:<sha>} (a human commit pushed after the clone aborts the push);
   * null ⇒ a plain non-forced push (create / fast-forward only). Never an unconditional {@code
   * --force}.
   */
  public GitToolResult commitAndPushSubdir(
      String runId,
      String subPath,
      String remoteUrl,
      String pat,
      String branchName,
      String commitMessage,
      String expectedRemoteSha) {
    try {
      String repoDir =
          Path.of(workspaceRoot, runId, subPath).toAbsolutePath().normalize().toString();

      ProcessBuilder status = new ProcessBuilder("git", "-C", repoDir, "status", "--porcelain");
      status.redirectErrorStream(true);
      Process ps = status.start();
      String statusOut = new String(ps.getInputStream().readAllBytes());
      int statusExit = ps.waitFor();
      if (statusExit != 0) return GitToolResult.err("status failed: " + statusOut);
      if (statusOut.isBlank()) return GitToolResult.ok("CLEAN");

      // Stage everything, and CHECK the result. A swallowed `git add -A` failure (index lock,
      // permission, dubious-ownership fatal) combined with `commit --allow-empty` below published
      // an
      // EMPTY commit — a PR/branch with zero file content — and returned ok(branchName). The status
      // check above already proved the worktree is dirty, so `--allow-empty` never served the
      // legitimate path; it only masked a staging failure. Check the add exit and drop
      // --allow-empty
      // so a staging failure surfaces as an error instead of a silent empty publish.
      ProcessBuilder add = new ProcessBuilder("git", "-C", repoDir, "add", "-A");
      add.redirectErrorStream(true);
      Process pa = add.start();
      String addOut = new String(pa.getInputStream().readAllBytes());
      int addExit = pa.waitFor();
      if (addExit != 0) return GitToolResult.err("git add failed: " + addOut);
      runQuietly(repoDir, "git", "-C", repoDir, "config", "user.email", "agent@executespec.local");
      runQuietly(repoDir, "git", "-C", repoDir, "config", "user.name", "ExecuteSpec Agent");

      ProcessBuilder commit =
          new ProcessBuilder("git", "-C", repoDir, "commit", "-m", commitMessage);
      commit.redirectErrorStream(true);
      Process pc = commit.start();
      String commitOut = new String(pc.getInputStream().readAllBytes());
      int commitExit = pc.waitFor();
      if (commitExit != 0) {
        // A dirty `status --porcelain` can still stage no blob change — git content normalization
        // (autocrlf / `* text=auto`), an unchanged submodule pointer, or a mode-only change with
        // core.fileMode=false. That is genuinely "nothing to publish", NOT a failure: with
        // --allow-empty removed, treat it like the up-front CLEAN case instead of failing the push
        // (which would block the PR) or publishing an empty commit.
        if (commitOut != null && commitOut.contains("nothing to commit")) {
          return GitToolResult.ok("CLEAN");
        }
        return GitToolResult.err("commit failed: " + commitOut);
      }

      runQuietly(repoDir, "git", "-C", repoDir, "checkout", "-B", branchName);
      ProcessBuilder push =
          com.navneet.executespec.repo.GitProcessAuth.git(
              pat, pushRefArgs(repoDir, remoteUrl, branchName, expectedRemoteSha));
      push.redirectErrorStream(true);
      Process pp = push.start();
      String pushOut = new String(pp.getInputStream().readAllBytes());
      int pushExit = pp.waitFor();
      if (pushExit != 0)
        return GitToolResult.err(
            "push failed: " + describePushFailure(branchName, expectedRemoteSha, pushOut));
      return GitToolResult.ok(branchName);
    } catch (Exception e) {
      log.warn("[GitTool] commitAndPushSubdir failed", e);
      return GitToolResult.err("subdir push failed: " + describeFailure(e));
    }
  }

  /**
   * v2.5.7 — incremental push to an existing remote branch. Unlike {@link #commitAndPushSubdir}
   * this method does NOT force-push and does NOT create a new branch. The workspace must already be
   * on the target branch (cloned with {@code git clone -b <branch>}). Returns {@code
   * ALREADY_CURRENT} only when the clean local HEAD already equals the remote, and {@code
   * PUSHED_EXISTING_COMMIT} when a clean retry publishes a previously committed local change.
   */
  public GitToolResult pushToExistingBranch(
      String runId,
      String subPath,
      String remoteUrl,
      String pat,
      String branchName,
      String commitMessage) {
    try {
      String repoDir =
          Path.of(workspaceRoot, runId, subPath).toAbsolutePath().normalize().toString();

      ProcessBuilder currentBranch =
          new ProcessBuilder("git", "-C", repoDir, "branch", "--show-current");
      currentBranch.redirectErrorStream(true);
      Process branchProcess = currentBranch.start();
      String branchOut = new String(branchProcess.getInputStream().readAllBytes()).trim();
      int branchExit = branchProcess.waitFor();
      if (branchExit != 0 || !branchName.equals(branchOut)) {
        return GitToolResult.err(
            "existing branch mismatch: expected "
                + branchName
                + " but workspace is on "
                + (branchOut.isBlank() ? "a detached or unknown branch" : branchOut));
      }

      // Check for changes. A clean worktree may still contain a locally committed change from a
      // previous non-fast-forward attempt. Compare local and remote heads before deciding whether
      // it is truly already current; a clean but ahead local HEAD must still be pushed.
      ProcessBuilder status = new ProcessBuilder("git", "-C", repoDir, "status", "--porcelain");
      status.redirectErrorStream(true);
      Process ps = status.start();
      String statusOut = new String(ps.getInputStream().readAllBytes());
      int statusExit = ps.waitFor();
      if (statusExit != 0) return GitToolResult.err("status failed: " + statusOut);
      boolean worktreeClean = statusOut.isBlank();
      if (!worktreeClean) {
        runQuietly(repoDir, "git", "-C", repoDir, "add", "-A");
        runQuietly(
            repoDir, "git", "-C", repoDir, "config", "user.email", "agent@executespec.local");
        runQuietly(repoDir, "git", "-C", repoDir, "config", "user.name", "ExecuteSpec Agent");

        ProcessBuilder commit =
            new ProcessBuilder("git", "-C", repoDir, "commit", "-m", commitMessage);
        commit.redirectErrorStream(true);
        Process pc = commit.start();
        String commitOut = new String(pc.getInputStream().readAllBytes());
        int commitExit = pc.waitFor();
        if (commitExit != 0) return GitToolResult.err("commit failed: " + commitOut);
      } else {
        String localHead = gitRevision(repoDir, "HEAD");
        String remoteHead = remoteRevision(repoDir, remoteUrl, pat, branchName);
        if (localHead == null || remoteHead == null) {
          return GitToolResult.err("existing branch head could not be verified before publication");
        }
        if (localHead.equalsIgnoreCase(remoteHead)) {
          return GitToolResult.ok("ALREADY_CURRENT");
        }
      }

      // Push WITHOUT --force (fast-forward only → safe for existing PR branches)
      ProcessBuilder push =
          com.navneet.executespec.repo.GitProcessAuth.git(
              pat,
              java.util.List.of("-C", repoDir, "push", remoteUrl, "HEAD:refs/heads/" + branchName));
      push.redirectErrorStream(true);
      Process pp = push.start();
      String pushOut = new String(pp.getInputStream().readAllBytes());
      int pushExit = pp.waitFor();
      if (pushExit != 0) return GitToolResult.err("push failed: " + pushOut);
      return GitToolResult.ok(worktreeClean ? "PUSHED_EXISTING_COMMIT" : branchName);
    } catch (Exception e) {
      log.warn("[GitTool] pushToExistingBranch failed: {}", describeFailure(e));
      return GitToolResult.err("push to existing branch failed: " + describeFailure(e));
    }
  }

  private static String gitRevision(String repoDir, String revision) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("git", "-C", repoDir, "rev-parse", revision);
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    return process.waitFor() == 0 && output.matches("[0-9a-fA-F]{40}") ? output : null;
  }

  private static String remoteRevision(
      String repoDir, String remoteUrl, String pat, String branchName) throws Exception {
    ProcessBuilder pb =
        com.navneet.executespec.repo.GitProcessAuth.git(
            pat,
            java.util.List.of("-C", repoDir, "ls-remote", remoteUrl, "refs/heads/" + branchName));
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes()).trim();
    if (process.waitFor() != 0 || output.isBlank()) return null;
    String sha = output.split("\\s+", 2)[0];
    return sha.matches("[0-9a-fA-F]{40}") ? sha : null;
  }

  private static void runQuietly(String dir, String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(new java.io.File(dir));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    p.waitFor();
  }

  /**
   * Post-push cleanup: return the workspace to {@code main}. A non-zero exit here leaves the tree
   * on the just-pushed feature branch, which silently corrupts a later diff / another repo's push
   * in a multi-repo loop, so the failure is logged loudly rather than swallowed. It is not the push
   * outcome, so it does not change the caller's success/err result.
   */
  private static void checkoutMainOrWarn(String wsPath, String branchName) {
    try {
      Process p =
          new ProcessBuilder("git", "-C", wsPath, "checkout", "main")
              .redirectErrorStream(true)
              .start();
      String out = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();
      if (exit != 0) {
        log.warn(
            "[GitTool] could not checkout main after pushing {} (exit={}) — workspace left on the"
                + " feature branch: {}",
            branchName,
            exit,
            out.strip());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("[GitTool] checkout main after push interrupted for {}", branchName, e);
    } catch (Exception e) {
      log.warn("[GitTool] checkout main after push failed for {}", branchName, e);
    }
  }

  /**
   * Push workspace to a Bitbucket branch using a scoped API token. The static Git username is the
   * Bitbucket-supported {@code x-bitbucket-api-token-auth}; the legacy username parameter remains
   * for call compatibility and REST operations.
   *
   * @param runId run whose workspace to push
   * @param cloneUrl HTTPS clone URL (e.g. https://bitbucket.org/workspace/repo.git)
   * @param username Bitbucket username
   * @param appPassword Bitbucket app password
   * @param branchName name for the new branch (e.g. "executespec/abc123")
   * @param expectedRemoteSha RR-1668 — the SHA this run cloned {@code branchName} from, or {@code
   *     null} when the run did not clone that branch. Non-null ⇒ {@code
   *     --force-with-lease=<branch>:<sha>}; null ⇒ a plain non-forced push. Never an unconditional
   *     {@code --force}.
   * @return GitToolResult.ok on success, GitToolResult.err on failure
   */
  public GitToolResult pushToBranchBitbucket(
      String runId,
      String cloneUrl,
      String username,
      String appPassword,
      String branchName,
      String expectedRemoteSha) {
    try {
      String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();

      // Create the branch from current HEAD (main)
      ProcessBuilder createBranch =
          new ProcessBuilder("git", "-C", wsPath, "checkout", "-b", branchName);
      createBranch.redirectErrorStream(true);
      Process p1 = createBranch.start();
      String out1 = new String(p1.getInputStream().readAllBytes());
      int exit1 = p1.waitFor();
      if (exit1 != 0) {
        log.warn("[GitTool] Bitbucket branch creation failed (exit {}): {}", exit1, out1);
        return GitToolResult.err("Branch creation failed: " + out1);
      }

      // Push the branch to remote — RR-1668: force-with-lease pinned to the cloned SHA, or a plain
      // non-forced push for a fresh branch. Never an unconditional --force.
      ProcessBuilder push =
          com.navneet.executespec.repo.GitProcessAuth.git(
              appPassword,
              "x-bitbucket-api-token-auth",
              pushRefArgs(wsPath, cloneUrl, branchName, expectedRemoteSha));
      push.redirectErrorStream(true);
      Process p2 = push.start();
      String out2 = new String(p2.getInputStream().readAllBytes());
      int exit2 = p2.waitFor();

      // Switch back to main regardless of push result. A silent failure leaves the workspace on the
      // feature branch, corrupting a later diff / another repo's push. Cleanup step — log, don't
      // fail the successful push.
      checkoutMainOrWarn(wsPath, branchName);

      if (exit2 == 0) {
        log.info("[GitTool] Pushed branch {} to Bitbucket: {}", branchName, cloneUrl);
        return GitToolResult.ok("Pushed branch " + branchName + " to " + cloneUrl);
      } else {
        String detail = describePushFailure(branchName, expectedRemoteSha, out2);
        log.warn("[GitTool] Bitbucket push failed (exit {}): {}", exit2, detail);
        return GitToolResult.err("Push branch failed: " + detail);
      }
    } catch (Exception e) {
      log.warn("[GitTool] Bitbucket push failed: {}", describeFailure(e));
      return GitToolResult.err("Push branch failed: " + describeFailure(e));
    }
  }

  /**
   * Merges a branch into main WITHOUT committing, so the caller can inspect and resolve conflicts
   * before finalizing.
   *
   * @return the JGit MergeResult (caller checks getMergeStatus())
   */
  public MergeResult mergeBranchNoCommit(String runId, String branchName) {
    if (branchName == null || branchName.isBlank()) {
      log.warn("mergeBranchNoCommit called with null/blank branch for run {}", runId);
      return null;
    }
    try (Git git = openRepo(runId)) {
      git.checkout().setName("main").call();
      org.eclipse.jgit.lib.Ref ref = git.getRepository().findRef(branchName);
      if (ref == null) {
        log.warn("Branch '{}' not found in repo for run {} — cannot merge", branchName, runId);
        return null;
      }
      return git.merge()
          .include(ref)
          .setFastForward(MergeCommand.FastForwardMode.NO_FF)
          .setCommit(false)
          .setMessage("merge: " + branchName)
          .call();
    } catch (Exception e) {
      log.error("mergeBranchNoCommit failed for {}: {}", branchName, e.getMessage());
      return null;
    }
  }

  /** Commits the current staged changes (e.g., after conflict resolution). */
  public void commitMerge(String runId, String message) {
    try (Git git = openRepo(runId)) {
      git.commit().setMessage(message).call();
    } catch (Exception e) {
      log.error("commitMerge failed for run {}: {}", runId, e.getMessage());
    }
  }

  /** Stages a specific file in the run's workspace repo. */
  public void stageFile(String runId, String filePath) {
    try (Git git = openRepo(runId)) {
      git.add().addFilepattern(filePath).call();
    } catch (Exception e) {
      log.error("stageFile failed for {}: {}", filePath, e.getMessage());
    }
  }

  /**
   * Reads a file's content from a specific git ref (branch name, commit hash, "HEAD").
   *
   * @return file content as string, or null if the file doesn't exist at that ref
   */
  public String readFileFromRef(String runId, String ref, String filePath) {
    try (Git git = openRepo(runId)) {
      ObjectId refId = git.getRepository().resolve(ref);
      if (refId == null) return null;

      org.eclipse.jgit.revwalk.RevWalk revWalk =
          new org.eclipse.jgit.revwalk.RevWalk(git.getRepository());
      RevCommit commit = revWalk.parseCommit(refId);
      org.eclipse.jgit.treewalk.TreeWalk treeWalk =
          org.eclipse.jgit.treewalk.TreeWalk.forPath(
              git.getRepository(), filePath, commit.getTree());
      if (treeWalk == null) return null;

      ObjectId blobId = treeWalk.getObjectId(0);
      byte[] bytes = git.getRepository().open(blobId).getBytes();
      revWalk.dispose();
      return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception e) {
      log.debug("readFileFromRef({}, {}, {}): {}", runId, ref, filePath, e.getMessage());
      return null;
    }
  }

  /**
   * Finds the merge base (common ancestor) commit between two branches.
   *
   * @return the merge-base commit hash, or null if not found
   */
  public String getMergeBase(String runId, String branch1, String branch2) {
    try (Git git = openRepo(runId)) {
      ObjectId id1 = git.getRepository().resolve(branch1);
      ObjectId id2 = git.getRepository().resolve(branch2);
      if (id1 == null || id2 == null) return null;

      org.eclipse.jgit.revwalk.RevWalk walk =
          new org.eclipse.jgit.revwalk.RevWalk(git.getRepository());
      walk.setRevFilter(org.eclipse.jgit.revwalk.filter.RevFilter.MERGE_BASE);
      walk.markStart(walk.parseCommit(id1));
      walk.markStart(walk.parseCommit(id2));
      RevCommit mergeBase = walk.next();
      walk.dispose();
      return mergeBase != null ? mergeBase.getName() : null;
    } catch (Exception e) {
      log.debug("getMergeBase({}, {}, {}): {}", runId, branch1, branch2, e.getMessage());
      return null;
    }
  }

  /** Returns the workspace path for a run. */
  public Path getWorkspacePath(String runId) {
    String override = WorkspaceContext.getWorkDir(runId);
    if (override != null) return Path.of(override);
    return Path.of(workspaceRoot, runId);
  }

  /**
   * Returns the diff between the initial workspace commit and the current HEAD on main. After
   * progressive merge every task branch is already merged into main, so per-branch diffs are empty.
   * This method shows the reviewer <em>everything</em> the workers produced.
   */
  public String getDiffSinceInit(String runId) {
    Path repoRoot = Path.of(workspaceRoot, runId).toAbsolutePath().normalize();
    try {
      // Find the initial commit (root commit with no parents)
      ProcessBuilder revPb =
          new ProcessBuilder("git", "rev-list", "--max-parents=0", "HEAD")
              .directory(repoRoot.toFile())
              .redirectErrorStream(true);
      Process revProc = revPb.start();
      String initCommit = new String(revProc.getInputStream().readAllBytes()).trim();
      revProc.waitFor();
      if (initCommit.isBlank()) {
        return "ERROR: Could not find initial commit";
      }
      // If multiple root commits, take the first one
      initCommit = initCommit.lines().findFirst().orElse(initCommit);

      ProcessBuilder diffPb =
          new ProcessBuilder("git", "diff", initCommit + "..HEAD")
              .directory(repoRoot.toFile())
              .redirectErrorStream(true);
      Process diffProc = diffPb.start();
      String diff = new String(diffProc.getInputStream().readAllBytes());
      diffProc.waitFor();
      return diff.isBlank() ? "(no changes since init)" : diff;
    } catch (Exception e) {
      log.error("[GitTool] getDiffSinceInit failed for run {}: {}", runId, e.getMessage());
      return "ERROR: " + e.getMessage();
    }
  }

  // Shell git helpers (worktree-safe)
  // JGit's Git.open() on a linked worktree (.git is a FILE, not a directory)
  // resolves Repository.getWorkTree() to the MAIN repo root instead of the
  // linked worktree directory. This causes addFilepattern("."), status(), and
  // diff() to operate on the wrong working tree. Shell git handles linked
  // worktrees natively.

  private String shellCommit(Path dir, String message) {
    try {
      new ProcessBuilder("git", "add", "-A")
          .directory(dir.toFile())
          .redirectErrorStream(true)
          .start()
          .waitFor();
      ProcessBuilder pb =
          new ProcessBuilder("git", "commit", "-m", message)
              .directory(dir.toFile())
              .redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();
      if (exit == 0) {
        String first = out.lines().findFirst().orElse("");
        return "Committed: " + first;
      }
      // exit 1 with "nothing to commit" is not an error
      if (out.contains("nothing to commit")) {
        return "Nothing to commit — working tree clean";
      }
      return "Commit failed (exit " + exit + "): " + out;
    } catch (Exception e) {
      return "Commit failed: " + e.getMessage();
    }
  }

  private String shellStatus(Path dir) {
    try {
      ProcessBuilder pb =
          new ProcessBuilder("git", "status", "--short")
              .directory(dir.toFile())
              .redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      return out.isBlank() ? "Clean working tree" : out;
    } catch (Exception e) {
      return "Status failed: " + e.getMessage();
    }
  }

  private String shellDiff(Path dir) {
    try {
      ProcessBuilder pb =
          new ProcessBuilder("git", "diff").directory(dir.toFile()).redirectErrorStream(true);
      Process p = pb.start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      if (out.isBlank()) {
        // Also check staged changes
        ProcessBuilder pb2 =
            new ProcessBuilder("git", "diff", "--cached")
                .directory(dir.toFile())
                .redirectErrorStream(true);
        Process p2 = pb2.start();
        String staged = new String(p2.getInputStream().readAllBytes());
        p2.waitFor();
        return staged.isBlank() ? "(no changes)" : staged;
      }
      return out;
    } catch (Exception e) {
      return "Diff failed: " + e.getMessage();
    }
  }

  private AbstractTreeIterator getTreeIterator(Git git, String ref) throws Exception {
    ObjectId id = git.getRepository().resolve(ref + "^{tree}");
    CanonicalTreeParser p = new CanonicalTreeParser();
    try (ObjectReader reader = git.getRepository().newObjectReader()) {
      p.reset(reader, id);
    }
    return p;
  }

  /**
   * Acquires a Redis-backed mutex for the given run's workspace, executes {@code work}, then
   * releases the lock. Prevents concurrent git mutations (branch create, merge-to-main) from racing
   * on the same workspace during retry races or multi-instance deployments.
   *
   * <p>Fail-open: if Redis is unavailable, the operation runs without a lock and a warning is
   * emitted. This avoids blocking git ops on Redis outages.
   *
   * @param runId workspace identifier (one lock per run workspace)
   * @param work the git operation to execute while holding the lock
   * @return result of {@code work}
   */
  private String withWorkspaceLock(String runId, Supplier<String> work) {
    String lockKey = GIT_LOCK_PREFIX + runId;
    String lockValue = Thread.currentThread().getName();
    boolean locked = false;
    try {
      // SET NX EX — atomic acquire
      Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, lockValue, GIT_LOCK_TTL);
      locked = Boolean.TRUE.equals(acquired);
      if (!locked) {
        // Another thread holds the lock; wait briefly and retry once
        log.debug("[GitTool] Lock contention on workspace {} — waiting 500ms", runId);
        Thread.sleep(500);
        acquired = redis.opsForValue().setIfAbsent(lockKey, lockValue, GIT_LOCK_TTL);
        locked = Boolean.TRUE.equals(acquired);
        if (!locked) {
          log.warn(
              "[GitTool] Could not acquire workspace lock for run {} after retry — proceeding"
                  + " without lock",
              runId);
        }
      }
    } catch (Exception e) {
      log.warn(
          "[GitTool] Redis lock acquisition failed for run {} — proceeding without lock: {}",
          runId,
          e.getMessage());
    }

    try {
      return work.get();
    } finally {
      if (locked) {
        try {
          // Only release if we still own the lock (value check prevents releasing a lock
          // that expired and was re-acquired by another thread)
          String current = redis.opsForValue().get(lockKey);
          if (lockValue.equals(current)) {
            redis.delete(lockKey);
          }
        } catch (Exception e) {
          log.debug(
              "[GitTool] Failed to release workspace lock for run {}: {}", runId, e.getMessage());
        }
      }
    }
  }
}
