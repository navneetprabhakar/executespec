package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.WorkspaceRestoreService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages per-run workspace lifecycle.
 *
 * <p>Each pipeline run gets its own isolated directory: {@code <agent.workspace>/<runId>/}
 *
 * <p>This directory contains a fresh git repo so runs can never contaminate each other's code,
 * history, or branches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceInitService {

  private final AgentProperties props;
  private final WorkspaceRestoreService restoreService;
  private final com.navneet.executespec.state.repository.RunRepoSnapshotRepository snapshotRepo;

  /**
   * Creates and initialises a fresh, isolated workspace for {@code runId}.
   *
   * <ul>
   *   <li>Creates {@code <workspace>/<runId>/} if it does not exist.
   *   <li>Runs {@code git init -b main} inside it.
   *   <li>Writes a sensible {@code .gitignore} and makes an initial commit so workers can branch
   *       off {@code main} immediately.
   * </ul>
   *
   * <p>Idempotent: if the directory and git repo already exist this method returns quickly without
   * modifying anything.
   *
   * @param runId the pipeline run ID (full UUID)
   */
  public void ensureRunWorkspace(String runId) {
    Path workspace = runWorkspacePath(runId);
    try {
      if (Files.isDirectory(workspace.resolve(".git"))) {
        // Already initialised — nothing to do
        log.debug("Workspace for run {} already exists: {}", runId, workspace);
        return;
      }

      Files.createDirectories(workspace);
      log.info("Created workspace for run {}: {}", runId, workspace);

      runGit(workspace, "git", "init", "-b", "main");

      // Configure identity so JGit commits work in all environments
      runGit(workspace, "git", "config", "user.email", "agent@executespec.local");
      runGit(workspace, "git", "config", "user.name", "ExecuteSpec Agent");

      // Standard build-artifact exclusions
      Files.writeString(
          workspace.resolve(".gitignore"),
          "target/\n"
              + "build/\n"
              + "node_modules/\n"
              + ".gradle/\n"
              + "__pycache__/\n"
              + "*.class\n"
              + "*.pyc\n"
              + "*.log\n"
              + ".batch_output/\n");

      // Every worker MUST update README.md (hard requirement in worker.txt step 7).
      // Parallel workers all modify README.md independently from the same base, causing
      // guaranteed 3-way merge conflicts when dep branches are merged sequentially.
      // merge=union appends both sides instead of emitting conflict markers, so every
      // dep-merge succeeds cleanly without losing documentation from either branch.
      Files.writeString(
          workspace.resolve(".gitattributes"),
          "# Parallel worker agents all update these documentation files independently.\n"
              + "# union merge appends both sides instead of producing conflict markers.\n"
              + "README.md merge=union\n"
              + "CHANGELOG.md merge=union\n");

      runGit(workspace, "git", "add", ".gitignore", ".gitattributes");
      runGit(workspace, "git", "commit", "--allow-empty", "-m", "init agent workspace");

      log.info("Workspace initialised for run {}", runId);

    } catch (Exception e) {
      log.error("Failed to initialise workspace for run {}: {}", runId, e.getMessage(), e);
      throw new RuntimeException(
          "Workspace init failed for run " + runId + ": " + e.getMessage(), e);
    }
  }

  /**
   * Clones an existing GitHub repository into the run workspace. Used when {@code
   * repoMode=EXISTING} so the workspace starts from real code.
   *
   * <p>The PAT is injected into the HTTPS URL as {@code oauth2:{pat}@} so no credential helper
   * configuration is required.
   *
   * <p>Idempotent: no-ops if {@code .git/} already exists in the workspace.
   *
   * @param runId the pipeline run ID
   * @param repoUrl HTTPS clone URL, e.g. {@code https://github.com/owner/repo}
   * @param pat personal access token (may be null for public repos)
   */
  public void cloneFromRepo(String runId, String repoUrl, String pat) throws Exception {
    Path workspace = runWorkspacePath(runId);
    if (Files.isDirectory(workspace.resolve(".git"))) {
      log.debug("Workspace for run {} already cloned: {}", runId, workspace);
      return;
    }
    Files.createDirectories(workspace.getParent());

    // Inject PAT into HTTPS URL for authentication
    String authUrl =
        (pat != null && !pat.isBlank())
            ? repoUrl.replace("https://", "https://oauth2:" + pat + "@")
            : repoUrl;

    log.info("Cloning {} into workspace for run {}", repoUrl, runId);
    // git clone <authUrl> <workspace-path>  — creates the directory
    runGitStrict(workspace.getParent(), "git", "clone", authUrl, workspace.toString());

    // Override commit identity for agent commits
    runGit(workspace, "git", "config", "user.email", "agent@executespec.local");
    runGit(workspace, "git", "config", "user.name", "ExecuteSpec Agent");
    log.info("Cloned workspace ready for run {}", runId);
  }

  /**
   * Copies all git-tracked files from a completed source run's workspace into a fresh workspace for
   * the target run. Used when the user starts a "conversation iteration" from a finished run: the
   * new run begins with the exact same codebase so agents can apply incremental changes rather than
   * building from scratch.
   *
   * <p>Steps:
   *
   * <ol>
   *   <li>Run {@code git ls-files} in the source workspace to get only tracked files.
   *   <li>Create the target directory, {@code git init -b main} inside it.
   *   <li>Copy each tracked file, preserving directory structure.
   *   <li>Make an initial commit so agents can immediately branch off {@code main}.
   * </ol>
   *
   * <p>Idempotent: no-ops if the target workspace already has a {@code .git} directory.
   *
   * @param sourceRunId ID of the completed run to copy from
   * @param targetRunId ID of the new run whose workspace will receive the copy
   */
  public void copyFromRun(String sourceRunId, String targetRunId) throws Exception {
    Path resolvedSource = runWorkspacePath(sourceRunId);
    Path target = runWorkspacePath(targetRunId);

    if (Files.isDirectory(target.resolve(".git"))) {
      log.debug("Target workspace for run {} already exists: {}", targetRunId, target);
      return;
    }

    if (!Files.isDirectory(resolvedSource)) {
      // Try restoring source workspace from GCS before falling back to empty
      try {
        resolvedSource = restoreService.ensureWorkspaceAvailable(sourceRunId);
        log.info("Restored source workspace for run {} from GCS", sourceRunId);
      } catch (Exception e) {
        log.warn(
            "Source workspace for run {} not found locally or in GCS ({}); falling back to empty"
                + " workspace",
            sourceRunId,
            e.getMessage());
        ensureRunWorkspace(targetRunId);
        return;
      }
    }

    // Effectively final for use in inner classes
    final Path source = resolvedSource;

    // ── 1. Get the list of tracked files from the source workspace ──
    ProcessBuilder pb =
        new ProcessBuilder("git", "ls-files").directory(source.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    String trackedFilesOutput = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      log.warn(
          "git ls-files failed for source run {} (exit {}); copying all files instead",
          sourceRunId,
          exit);
    }

    List<String> trackedFiles = trackedFilesOutput.lines().filter(l -> !l.isBlank()).toList();

    // ── 2. Initialise target workspace ────────────────────────────────
    Files.createDirectories(target);
    runGit(target, "git", "init", "-b", "main");
    runGit(target, "git", "config", "user.email", "agent@executespec.local");
    runGit(target, "git", "config", "user.name", "ExecuteSpec Agent");

    // ── 3. Copy each tracked file, preserving directory structure ─────
    if (trackedFiles.isEmpty()) {
      // Fallback: copy everything that is not under .git/
      log.info(
          "No tracked files found in source run {} — performing full directory copy", sourceRunId);
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              return dir.equals(source.resolve(".git"))
                  ? FileVisitResult.SKIP_SUBTREE
                  : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Path rel = source.relativize(file);
              Path dst = target.resolve(rel);
              Files.createDirectories(dst.getParent());
              Files.copy(file, dst, StandardCopyOption.REPLACE_EXISTING);
              return FileVisitResult.CONTINUE;
            }
          });
    } else {
      for (String relPath : trackedFiles) {
        Path srcFile = source.resolve(relPath);
        if (!Files.exists(srcFile)) continue;
        Path dstFile = target.resolve(relPath);
        Files.createDirectories(dstFile.getParent());
        Files.copy(srcFile, dstFile, StandardCopyOption.REPLACE_EXISTING);
      }
    }

    // Copy .gitignore separately (it may not appear in ls-files if it was in the initial commit)
    Path srcGitignore = source.resolve(".gitignore");
    if (Files.exists(srcGitignore) && !Files.exists(target.resolve(".gitignore"))) {
      Files.copy(srcGitignore, target.resolve(".gitignore"));
    }

    // ── 4. Commit everything in the new workspace ─────────────────────
    runGit(target, "git", "add", "-A");
    runGit(
        target,
        "git",
        "commit",
        "--allow-empty",
        "-m",
        "init: copied codebase from run " + sourceRunId.substring(0, 8));

    log.info(
        "Workspace for run {} initialised from source run {} ({} files copied)",
        targetRunId,
        sourceRunId,
        trackedFiles.size());
  }

  /**
   * Pilot v3.0 — clones a set of project-linked repos into per-repo subdirectories under the run
   * workspace. Each repo lands at {@code <workspace>/<runId>/repos/<repoName>/} where {@code
   * repoName} is the segment after the last {@code /} of {@link RepoToClone#fullName()}.
   *
   * <p>Idempotent per-repo: skips clones whose target already contains a {@code .git}. Call {@link
   * #ensureRunWorkspace(String)} first to make sure the outer workspace git repo exists.
   */
  public void cloneMultipleRepos(String runId, List<RepoToClone> repos, String pat)
      throws Exception {
    if (repos == null || repos.isEmpty()) return;
    Path workspace = runWorkspacePath(runId);
    Path reposRoot = workspace.resolve("repos");
    Files.createDirectories(reposRoot);

    for (RepoToClone r : repos) {
      String dirName =
          r.fullName().contains("/")
              ? r.fullName().substring(r.fullName().indexOf('/') + 1)
              : r.fullName();
      Path target = reposRoot.resolve(dirName);
      if (Files.isDirectory(target.resolve(".git"))) {
        log.debug("Repo {} already cloned for run {}", r.fullName(), runId);
        continue;
      }
      String httpsUrl = r.cloneUrl() != null ? r.cloneUrl() : "https://github.com/" + r.fullName();
      String authUrl =
          (pat != null && !pat.isBlank())
              ? httpsUrl.replace("https://", "https://oauth2:" + pat + "@")
              : httpsUrl;
      String cloneBranch = (r.branch() == null || r.branch().isBlank()) ? null : r.branch();
      if (cloneBranch != null) {
        log.info(
            "Cloning {} (branch={}) into {} for run {}", r.fullName(), cloneBranch, target, runId);
        runGitStrict(reposRoot, "git", "clone", "-b", cloneBranch, authUrl, target.toString());
      } else {
        log.info("Cloning {} into {} for run {}", r.fullName(), target, runId);
        runGitStrict(reposRoot, "git", "clone", authUrl, target.toString());
      }
      persistCloneSnapshot(runId, r, target);
    }
  }

  /**
   * Captures {@code git rev-parse HEAD} in the newly cloned repo and writes a {@link
   * com.navneet.executespec.state.entity.RunRepoSnapshot} row. Non-fatal on failure — snapshot is
   * only used by the reset-to-original flow; a missing row means that flow won't be available for
   * this repo but the run proceeds.
   */
  private void persistCloneSnapshot(String runId, RepoToClone r, Path target) {
    try {
      String sha = captureHeadSha(target);
      if (sha == null || sha.isBlank()) {
        log.warn("Snapshot skipped for run {} repo {} — empty SHA", runId, r.fullName());
        return;
      }
      String branch = r.branch() == null || r.branch().isBlank() ? "main" : r.branch();
      com.navneet.executespec.state.entity.RunRepoSnapshot snap =
          com.navneet.executespec.state.entity.RunRepoSnapshot.builder()
              .runId(runId)
              .repoId(r.repoId())
              .repoFullName(r.fullName())
              .branch(branch)
              .commitSha(sha)
              .build();
      snapshotRepo.save(snap);
      log.debug("Snapshot saved run={} repo={} branch={} sha={}", runId, r.fullName(), branch, sha);
    } catch (Exception e) {
      log.warn(
          "persistCloneSnapshot failed run={} repo={}: {}", runId, r.fullName(), e.getMessage());
    }
  }

  private String captureHeadSha(Path repoDir) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD").directory(repoDir.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes()).trim();
    int exit = p.waitFor();
    if (exit != 0) {
      log.warn("git rev-parse HEAD exit {} in {}: {}", exit, repoDir, out);
      return null;
    }
    return out;
  }

  /**
   * Minimal record describing a repo to clone for {@link #cloneMultipleRepos}.
   *
   * @param fullName e.g. "owner/repo"
   * @param cloneUrl HTTPS clone URL (null → derived from fullName)
   * @param repoId optional — persisted into {@code run_repo_snapshot.repo_id} when present, so the
   *     "reset to original" path can match back to the caller's {@code ProjectRepo}. Null is valid
   *     (snapshot still captures fullName + sha).
   * @param branch optional — the branch being cloned (used only as the {@code
   *     run_repo_snapshot.branch} value for display). When null we fall back to {@code "main"}.
   */
  public record RepoToClone(String fullName, String cloneUrl, UUID repoId, String branch) {
    public RepoToClone(String fullName, String cloneUrl) {
      this(fullName, cloneUrl, null, null);
    }
  }

  /** Returns the absolute, normalised path to the per-run workspace directory. */
  public Path runWorkspacePath(String runId) {
    return Path.of(props.getWorkspace(), runId).toAbsolutePath().normalize();
  }

  private void runGit(Path dir, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      log.debug("git command [{}] exit {}: {}", String.join(" ", cmd), exit, out);
    }
  }

  /** Like {@link #runGit} but throws a RuntimeException on non-zero exit code (for clone). */
  private void runGitStrict(Path dir, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      // Mask any embedded PAT in the logged command before throwing
      String cmdStr = String.join(" ", cmd).replaceAll("oauth2:[^@]+@", "oauth2:***@");
      throw new RuntimeException("git command [" + cmdStr + "] exited with " + exit + ": " + out);
    }
  }
}
