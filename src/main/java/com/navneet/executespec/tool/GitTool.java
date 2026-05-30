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
   * Stage and commit any pending changes on the current branch. No-ops silently if the working tree
   * is already clean (nothing to commit). Used by post-merge agents (e.g. ApiTestSuiteAgent) to
   * persist generated files into git so they are not accidentally picked up by a retried worker's
   * {@code git add .}.
   */
  public void commitIfDirty(String runId, String message) {
    Path workDir = getWorkspacePath(runId);
    try {
      ProcessBuilder statusPb =
          new ProcessBuilder("git", "status", "--porcelain")
              .directory(workDir.toFile())
              .redirectErrorStream(true);
      Process statusProc = statusPb.start();
      String statusOutput = new String(statusProc.getInputStream().readAllBytes());
      statusProc.waitFor();
      if (statusOutput.isBlank()) return;

      new ProcessBuilder("git", "add", "-A")
          .directory(workDir.toFile())
          .redirectErrorStream(true)
          .start()
          .waitFor();
      ProcessBuilder commitPb =
          new ProcessBuilder("git", "commit", "-m", message)
              .directory(workDir.toFile())
              .redirectErrorStream(true);
      Process commitProc = commitPb.start();
      commitProc.getInputStream().readAllBytes();
      commitProc.waitFor();
      log.debug("commitIfDirty: committed on run {} — {}", runId, message);
    } catch (Exception e) {
      log.warn("commitIfDirty failed for run {}: {}", runId, e.getMessage());
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
   */
  public GitToolResult pushToRemote(String runId, String repoUrl, String pat) {
    try {
      String authUrl = repoUrl.replace("https://", "https://oauth2:" + pat + "@");
      String wsPath = Path.of(workspaceRoot, runId).toAbsolutePath().normalize().toString();
      ProcessBuilder pb =
          new ProcessBuilder("git", "-C", wsPath, "push", authUrl, "main", "--force");
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
      log.warn("[GitTool] Push failed: {}", e.getMessage());
      return GitToolResult.err("Push failed: " + e.getMessage());
    }
  }

  /**
   * Creates a new branch from main and pushes it to the remote. Used for existing repos where we
   * don't want to push directly to the default branch.
   *
   * @param runId run identifier (used to locate workspace)
   * @param repoUrl HTTPS clone URL
   * @param pat GitHub PAT for authentication
   * @param branchName name for the new branch (e.g. "executespec/abc123")
   * @return GitToolResult.ok on success, GitToolResult.err on failure
   */
  public GitToolResult pushToBranch(String runId, String repoUrl, String pat, String branchName) {
    try {
      String authUrl = repoUrl.replace("https://", "https://oauth2:" + pat + "@");
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

      // Push the branch to remote
      ProcessBuilder push =
          new ProcessBuilder("git", "-C", wsPath, "push", authUrl, branchName, "--force");
      push.redirectErrorStream(true);
      Process p2 = push.start();
      String out2 = new String(p2.getInputStream().readAllBytes());
      int exit2 = p2.waitFor();

      // Switch back to main regardless of push result
      new ProcessBuilder("git", "-C", wsPath, "checkout", "main")
          .redirectErrorStream(true)
          .start()
          .waitFor();

      if (exit2 == 0) {
        log.info("[GitTool] Pushed branch {} to remote: {}", branchName, repoUrl);
        return GitToolResult.ok("Pushed branch " + branchName + " to " + repoUrl);
      } else {
        log.warn("[GitTool] Push branch failed (exit {}): {}", exit2, out2);
        return GitToolResult.err("Push branch failed: " + out2);
      }
    } catch (Exception e) {
      log.warn("[GitTool] Push branch failed: {}", e.getMessage());
      return GitToolResult.err("Push branch failed: " + e.getMessage());
    }
  }

  /**
   * Pilot v3.0 — for multi-repo runs. Each cloned subdirectory under {@code
   * workspace/<runId>/repos/<name>} is its own git repo. Stages + commits + creates a branch +
   * force-pushes. Returns OK with the branch name on success, OK with "CLEAN" if no changes, ERR
   * otherwise.
   */
  public GitToolResult commitAndPushSubdir(
      String runId,
      String subPath,
      String remoteUrl,
      String pat,
      String branchName,
      String commitMessage) {
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

      runQuietly(repoDir, "git", "-C", repoDir, "add", "-A");
      runQuietly(repoDir, "git", "-C", repoDir, "config", "user.email", "agent@executespec.local");
      runQuietly(repoDir, "git", "-C", repoDir, "config", "user.name", "ExecuteSpec Agent");

      ProcessBuilder commit =
          new ProcessBuilder("git", "-C", repoDir, "commit", "--allow-empty", "-m", commitMessage);
      commit.redirectErrorStream(true);
      Process pc = commit.start();
      String commitOut = new String(pc.getInputStream().readAllBytes());
      int commitExit = pc.waitFor();
      if (commitExit != 0) return GitToolResult.err("commit failed: " + commitOut);

      runQuietly(repoDir, "git", "-C", repoDir, "checkout", "-B", branchName);
      String authUrl = remoteUrl.replace("https://", "https://oauth2:" + pat + "@");
      ProcessBuilder push =
          new ProcessBuilder("git", "-C", repoDir, "push", authUrl, branchName, "--force");
      push.redirectErrorStream(true);
      Process pp = push.start();
      String pushOut = new String(pp.getInputStream().readAllBytes());
      int pushExit = pp.waitFor();
      if (pushExit != 0) return GitToolResult.err("push failed: " + pushOut);
      return GitToolResult.ok(branchName);
    } catch (Exception e) {
      log.warn("[GitTool] commitAndPushSubdir failed: {}", e.getMessage());
      return GitToolResult.err("subdir push failed: " + e.getMessage());
    }
  }

  /**
   * v2.5.7 — incremental push to an existing remote branch. Unlike {@link #commitAndPushSubdir}
   * this method does NOT force-push and does NOT create a new branch. The workspace must already be
   * on the target branch (cloned with {@code git clone -b <branch>}). Returns "CLEAN" if there's
   * nothing to commit.
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

      // Check for changes
      ProcessBuilder status = new ProcessBuilder("git", "-C", repoDir, "status", "--porcelain");
      status.redirectErrorStream(true);
      Process ps = status.start();
      String statusOut = new String(ps.getInputStream().readAllBytes());
      int statusExit = ps.waitFor();
      if (statusExit != 0) return GitToolResult.err("status failed: " + statusOut);
      if (statusOut.isBlank()) return GitToolResult.ok("CLEAN");

      runQuietly(repoDir, "git", "-C", repoDir, "add", "-A");
      runQuietly(repoDir, "git", "-C", repoDir, "config", "user.email", "agent@executespec.local");
      runQuietly(repoDir, "git", "-C", repoDir, "config", "user.name", "ExecuteSpec Agent");

      ProcessBuilder commit =
          new ProcessBuilder("git", "-C", repoDir, "commit", "-m", commitMessage);
      commit.redirectErrorStream(true);
      Process pc = commit.start();
      String commitOut = new String(pc.getInputStream().readAllBytes());
      int commitExit = pc.waitFor();
      if (commitExit != 0) return GitToolResult.err("commit failed: " + commitOut);

      // Push WITHOUT --force (fast-forward only → safe for existing PR branches)
      String authUrl = remoteUrl.replace("https://", "https://oauth2:" + pat + "@");
      ProcessBuilder push = new ProcessBuilder("git", "-C", repoDir, "push", authUrl, branchName);
      push.redirectErrorStream(true);
      Process pp = push.start();
      String pushOut = new String(pp.getInputStream().readAllBytes());
      int pushExit = pp.waitFor();
      if (pushExit != 0) return GitToolResult.err("push failed: " + pushOut);
      return GitToolResult.ok(branchName);
    } catch (Exception e) {
      log.warn("[GitTool] pushToExistingBranch failed: {}", e.getMessage());
      return GitToolResult.err("push to existing branch failed: " + e.getMessage());
    }
  }

  private static void runQuietly(String dir, String... cmd) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(new java.io.File(dir));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes();
    p.waitFor();
  }

  /**
   * Push workspace to a Bitbucket branch using Basic auth (username:appPassword). Mirrors {@link
   * #pushToBranch} but uses Bitbucket credential format.
   *
   * @param runId run whose workspace to push
   * @param cloneUrl HTTPS clone URL (e.g. https://bitbucket.org/workspace/repo.git)
   * @param username Bitbucket username
   * @param appPassword Bitbucket app password
   * @param branchName name for the new branch (e.g. "executespec/abc123")
   * @return GitToolResult.ok on success, GitToolResult.err on failure
   */
  public GitToolResult pushToBranchBitbucket(
      String runId, String cloneUrl, String username, String appPassword, String branchName) {
    try {
      String authUrl =
          cloneUrl.replace("https://", "https://" + username + ":" + appPassword + "@");
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

      // Push the branch to remote
      ProcessBuilder push =
          new ProcessBuilder("git", "-C", wsPath, "push", authUrl, branchName, "--force");
      push.redirectErrorStream(true);
      Process p2 = push.start();
      String out2 = new String(p2.getInputStream().readAllBytes());
      int exit2 = p2.waitFor();

      // Switch back to main regardless of push result
      new ProcessBuilder("git", "-C", wsPath, "checkout", "main")
          .redirectErrorStream(true)
          .start()
          .waitFor();

      if (exit2 == 0) {
        log.info("[GitTool] Pushed branch {} to Bitbucket: {}", branchName, cloneUrl);
        return GitToolResult.ok("Pushed branch " + branchName + " to " + cloneUrl);
      } else {
        log.warn("[GitTool] Bitbucket push failed (exit {}): {}", exit2, out2);
        return GitToolResult.err("Push branch failed: " + out2);
      }
    } catch (Exception e) {
      log.warn("[GitTool] Bitbucket push failed: {}", e.getMessage());
      return GitToolResult.err("Push branch failed: " + e.getMessage());
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
