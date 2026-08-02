package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.security.SafeWorkspacePath;

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
  private final VendoredPathGuard vendoredPathGuard;
  private final BrownfieldHttpContractGuard brownfieldHttpContractGuard;

  /**
   * Exclusions written into every greenfield run workspace. {@code .agent-context/} is the internal
   * evidence directory (full tool logs, run journal, file locks) and must never reach a customer
   * pull request (RR-1546).
   */
  static final String AGENT_WORKSPACE_GITIGNORE =
      "target/\n"
          + "build/\n"
          + "node_modules/\n"
          + ".gradle/\n"
          + "__pycache__/\n"
          + "*.class\n"
          + "*.pyc\n"
          + "*.log\n"
          + ".batch_output/\n"
          + ".agent-context/\n";

  /** Exclusion appended to a CLONED repository's {@code .git/info/exclude} (RR-1546). */
  static final String AGENT_CONTEXT_EXCLUDE_ENTRY = ".agent-context/";

  /**
   * Appends {@code .agent-context/} to a cloned repository's {@code .git/info/exclude}.
   *
   * <p>Brownfield clones use the CUSTOMER's {@code .gitignore}, which has no reason to mention an
   * ExecuteSpec-internal directory, and mutating that tracked file would show up as a spurious diff
   * in the pull request. {@code .git/info/exclude} is per-clone, untracked, and never pushed — the
   * correct place for a host-side exclusion. Best-effort: a failure here degrades to the previous
   * behaviour and must not fail the clone.
   */
  void excludeAgentContextInClone(Path repoRoot) {
    try {
      Path infoDir = repoRoot.resolve(".git").resolve("info");
      if (!Files.isDirectory(repoRoot.resolve(".git"))) return;
      Files.createDirectories(infoDir);
      Path exclude = infoDir.resolve("exclude");
      String existing = Files.exists(exclude) ? Files.readString(exclude) : "";
      if (existing.lines().anyMatch(l -> l.strip().equals(AGENT_CONTEXT_EXCLUDE_ENTRY))) return;
      String prefix = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
      Files.writeString(
          exclude,
          existing
              + prefix
              + "# ExecuteSpec internal execution evidence — never publish (RR-1546)\n"
              + AGENT_CONTEXT_EXCLUDE_ENTRY
              + "\n");
    } catch (IOException e) {
      log.warn(
          "Could not add .agent-context exclusion to clone at {}: {}", repoRoot, e.getMessage());
    }
  }

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

      // Standard build-artifact exclusions.
      //
      // `.agent-context/` (RR-1546) holds INTERNAL execution evidence: full un-budgeted shell /
      // build / test output spilled by ToolOutput.persistFull, the rolling run journal, and file
      // locks. GitCommitTool and GitTool stage with `git add -A`, so without this entry every one
      // of those files was committed and pushed into the customer's pull request — the class
      // javadoc's claim that it "is not pushed on product runs" had nothing enforcing it.
      Files.writeString(workspace.resolve(".gitignore"), AGENT_WORKSPACE_GITIGNORE);

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

  /** Clone an existing repository with ephemeral env-backed authentication. */
  public void cloneFromRepo(String runId, String repoUrl, String pat) throws Exception {
    cloneFromRepo(runId, repoUrl, null, pat);
  }

  /** Provider-neutral clone overload (e.g. Bitbucket app-password username + secret). */
  public void cloneFromRepo(String runId, String repoUrl, String username, String secret)
      throws Exception {
    cloneRepository(runId, repoUrl, username, secret, true);
  }

  /** Clone-only seam for read-only source inspection; it deliberately creates no run evidence. */
  public void cloneReadOnlyRepository(
      String workspaceId, String repoUrl, String username, String secret) throws Exception {
    cloneRepository(workspaceId, repoUrl, username, secret, false);
  }

  private void cloneRepository(
      String runId, String repoUrl, String username, String secret, boolean captureBaseline)
      throws Exception {
    Path workspace = runWorkspacePath(runId);
    if (Files.isDirectory(workspace.resolve(".git"))) {
      if (captureBaseline) brownfieldHttpContractGuard.captureBaselineIfAbsent(runId, workspace);
      log.debug("Workspace for run {} already cloned: {}", runId, workspace);
      return;
    }
    Files.createDirectories(workspace.getParent());

    log.info("Cloning {} into workspace for run {}", repoUrl, runId);
    // Credential-gate: authentication stays in the child environment, never argv or origin URL.
    runGitStrictAuthWithUsername(
        workspace.getParent(), username, secret, "clone", repoUrl, workspace.toString());

    // Override commit identity for agent commits
    runGit(workspace, "git", "config", "user.email", "agent@executespec.local");
    runGit(workspace, "git", "config", "user.name", "ExecuteSpec Agent");
    // 2026-05-02 — same vendored-path guard as cloneMultipleRepos. The legacy single-repo
    // path lands the clone at the workspace root rather than a repos/<name>/ subdir, but the
    // class of bug is identical (committed node_modules/ etc.).
    vendoredPathGuard.installLocalExcludes(workspace);
    // RR-1546 — keep ExecuteSpec's internal .agent-context/ evidence out of the customer's tree
    // without mutating their tracked .gitignore.
    excludeAgentContextInClone(workspace);
    if (captureBaseline) brownfieldHttpContractGuard.captureBaselineIfAbsent(runId, workspace);
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

    // 1. Get the list of tracked files from the source workspace
    ProcessBuilder pb =
        new ProcessBuilder("git", "ls-files").directory(source.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    String trackedFilesOutput = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    // RR-153 (P1 data integrity) — whether tracked-file enumeration actually SUCCEEDED gates the
    // copy. The old code fell back to copying the ENTIRE source directory (minus .git/) when
    // ls-files failed or returned empty, so a corrupted/non-repository source silently carried
    // untracked spill files, secrets, build output and tool artifacts into the new run and onward
    // to
    // publication. Fail closed: on an enumeration failure copy NOTHING (start from a clean,
    // git-init'd workspace) rather than copying unfiltered content.
    boolean enumerationOk = (exit == 0);
    if (!enumerationOk) {
      log.error(
          "git ls-files failed for source run {} (exit {}); starting the target from an EMPTY"
              + " workspace rather than copying unfiltered content",
          sourceRunId,
          exit);
    }

    List<String> trackedFiles =
        enumerationOk ? trackedFilesOutput.lines().filter(l -> !l.isBlank()).toList() : List.of();

    Files.createDirectories(target);
    runGit(target, "git", "init", "-b", "main");
    runGit(target, "git", "config", "user.email", "agent@executespec.local");
    runGit(target, "git", "config", "user.name", "ExecuteSpec Agent");

    // 3. Copy each tracked file, preserving directory structure. RR-153 — there is deliberately NO
    // "copy everything" fallback: an empty tracked set (a genuinely empty source, or a failed
    // enumeration handled above) yields an empty initialised workspace, never an unfiltered copy.
    if (trackedFiles.isEmpty()) {
      log.info(
          "No tracked files copied from source run {} — target starts from an empty initialised"
              + " workspace",
          sourceRunId);
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

    // 4. Commit everything in the new workspace
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
    brownfieldHttpContractGuard.captureBaselineIfAbsent(targetRunId, target);
  }

  /** Fail-closed, Git-tracked-only initialization for iteration workspaces. */
  public void copyFromRunRequired(String sourceRunId, String targetRunId) throws Exception {
    new RequiredIterationWorkspaceCopier(props, restoreService, snapshotRepo)
        .copy(sourceRunId, targetRunId);
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
    long distinctTargets =
        repos.stream().map(r -> repoDirectoryName(r.fullName())).distinct().count();
    if (distinctTargets != repos.size()) {
      throw new IllegalArgumentException(
          "selected repositories contain colliding checkout directory names");
    }
    Path workspace = runWorkspacePath(runId);
    Path reposRoot = workspace.resolve("repos");
    Files.createDirectories(reposRoot);

    for (RepoToClone r : repos) {
      String dirName = repoDirectoryName(r.fullName());
      Path target = reposRoot.resolve(dirName);
      if (Files.isDirectory(target.resolve(".git"))) {
        // A crash may have left a complete clone before its baseline row was persisted. Reconcile
        // the immutable baseline before treating the clone as admitted.
        persistCloneSnapshot(runId, r, target);
        log.debug("Repo {} already cloned for run {}", r.fullName(), runId);
        continue;
      }
      String httpsUrl = r.cloneUrl() != null ? r.cloneUrl() : "https://github.com/" + r.fullName();
      String cloneBranch = (r.branch() == null || r.branch().isBlank()) ? null : r.branch();
      if (cloneBranch != null) {
        log.info(
            "Cloning {} (branch={}) into {} for run {}", r.fullName(), cloneBranch, target, runId);
        runGitStrictAuth(reposRoot, pat, "clone", "-b", cloneBranch, httpsUrl, target.toString());
      } else {
        log.info("Cloning {} into {} for run {}", r.fullName(), target, runId);
        runGitStrictAuth(reposRoot, pat, "clone", httpsUrl, target.toString());
      }
      persistCloneSnapshot(runId, r, target);
      // Install non-versioned vendored-path excludes BEFORE the worker starts running. This closes
      // the run-b171fa52 class without changing customer history or widening a file-scoped PR.
      vendoredPathGuard.installLocalExcludes(target);
      excludeAgentContextInClone(target); // RR-1546
    }
    brownfieldHttpContractGuard.captureBaselineIfAbsent(runId, workspace);
  }

  /**
   * Deterministic post-clone admission for brownfield runs. The outer seed repository is never
   * considered evidence; every requested repository must have Git metadata and at least one real
   * non-hidden file. This check runs before classifier/planner dispatch.
   */
  public WorkspaceAdmission verifyMultipleRepos(String runId, List<RepoToClone> repos) {
    if (repos == null || repos.isEmpty()) {
      return new WorkspaceAdmission(false, 0, "no repositories were selected");
    }
    Path reposRoot = runWorkspacePath(runId).resolve("repos");
    long totalFiles = 0;
    for (RepoToClone repo : repos) {
      String dirName = repoDirectoryName(repo.fullName());
      Path target = reposRoot.resolve(dirName);
      if (!Files.isDirectory(target.resolve(".git"))) {
        return new WorkspaceAdmission(false, totalFiles, "clone missing for " + repo.fullName());
      }
      try (var files = Files.walk(target)) {
        long repoFiles =
            files
                .filter(Files::isRegularFile)
                .filter(path -> !target.relativize(path).startsWith(".git"))
                .filter(
                    path -> {
                      Path relative = target.relativize(path);
                      for (Path segment : relative) {
                        if (segment.toString().startsWith(".")) return false;
                      }
                      return true;
                    })
                .count();
        if (repoFiles == 0) {
          return new WorkspaceAdmission(
              false, totalFiles, "repository has no source files: " + repo.fullName());
        }
        totalFiles += repoFiles;
      } catch (IOException e) {
        return new WorkspaceAdmission(
            false, totalFiles, "could not inventory " + repo.fullName() + ": " + e.getMessage());
      }
    }
    return new WorkspaceAdmission(true, totalFiles, null);
  }

  public record WorkspaceAdmission(boolean admitted, long fileCount, String reason) {}

  static String repoDirectoryName(String fullName) {
    if (fullName == null || fullName.isBlank()) {
      throw new IllegalArgumentException("repository full name is required");
    }
    int slash = fullName.lastIndexOf('/');
    String name = slash >= 0 ? fullName.substring(slash + 1) : fullName;
    // RR-152 — the checkout directory is a single contained segment beneath repos/. A repository
    // full name ending in a reserved/traversal segment (e.g. "owner/..") would otherwise resolve
    // the clone target outside the run workspace. Reject it before it becomes a directory name.
    SafeWorkspacePath.requireSafeRelativePosixPath(name);
    return name;
  }

  /**
   * Captures {@code git rev-parse HEAD} in the newly cloned repo and writes a {@link
   * com.navneet.executespec.state.entity.RunRepoSnapshot} row. Baseline persistence is part of
   * brownfield admission: without the exact branch/SHA the run cannot later prove what changed.
   */
  private void persistCloneSnapshot(String runId, RepoToClone r, Path target) {
    try {
      boolean alreadyPersisted =
          snapshotRepo.findByRunId(runId).stream()
              .anyMatch(snapshot -> r.fullName().equals(snapshot.getRepoFullName()));
      if (alreadyPersisted) return;
      String sha = captureHeadSha(target);
      if (sha == null || sha.isBlank()) {
        throw new IllegalStateException("could not capture baseline SHA for " + r.fullName());
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
      throw new IllegalStateException(
          "could not persist repository baseline for " + r.fullName(), e);
    }
  }

  /**
   * RR-641 — pin an already-cloned workspace to an exact commit and prove it. {@link
   * #cloneFromRepo} lands the repository's current default HEAD, so a semantic index that labels
   * its chunks with a previously recorded {@code lastSyncedSha} would silently publish newer code
   * under the older SHA once the branch advances between metadata sync and clone. Checking out the
   * exact commit (detached) and verifying {@code HEAD} byte-for-byte closes that gap.
   *
   * <p>A full clone contains all branch history, so an ancestor commit checks out without a network
   * fetch. A commit that is no longer reachable (force-push / rebase) fails to check out, the HEAD
   * verification fails, and this returns {@code false} so the caller can stale/fail the index
   * rather than index a tree that does not match the recorded revision.
   *
   * @return {@code true} only when the resulting {@code HEAD} equals {@code commitSha}
   */
  public boolean checkoutExactCommit(String runId, String commitSha) {
    if (commitSha == null || commitSha.isBlank()) return false;
    Path workspace = runWorkspacePath(runId);
    try {
      runGit(workspace, "git", "checkout", "--detach", commitSha);
      String head = captureHeadSha(workspace);
      boolean matched = head != null && commitSha.equalsIgnoreCase(head.trim());
      if (!matched) {
        log.warn(
            "RR-641 — workspace {} could not be pinned to {} (HEAD={})", runId, commitSha, head);
      }
      return matched;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("RR-641 — checkoutExactCommit interrupted for {} @ {}", runId, commitSha);
      return false;
    } catch (IOException e) {
      log.warn(
          "RR-641 — checkoutExactCommit failed for {} @ {}: {}", runId, commitSha, e.getMessage());
      return false;
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
    // RR-152 (P1 filesystem integrity) — the run id is the ONLY user/caller-derived segment
    // appended
    // to the configured workspace root. Enforce the containment invariant at the edge (single safe
    // relative POSIX segment: no traversal, no dot/empty/absolute/encoded separator) so a
    // non-canonical or legacy run identity cannot turn ordinary read/write/clone into traversal
    // outside the workspace. Normal UUID run ids pass unchanged.
    SafeWorkspacePath.requireSafeRelativePosixPath(runId);
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

  /**
   * Strict git execution with environment-carried authentication (credential-gate 2026-07-17). The
   * token never enters argv or the persisted remote URL; see {@link
   * com.navneet.executespec.repo.GitProcessAuth}.
   */
  private void runGitStrictAuth(Path dir, String pat, String... gitArgs)
      throws IOException, InterruptedException {
    runGitStrictAuthWithUsername(dir, null, pat, gitArgs);
  }

  private void runGitStrictAuthWithUsername(
      Path dir, String username, String secret, String... gitArgs)
      throws IOException, InterruptedException {
    ProcessBuilder pb =
        com.navneet.executespec.repo.GitProcessAuth.git(
            secret, username, java.util.List.of(gitArgs));
    pb.directory(dir.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    int exit = p.waitFor();
    if (exit != 0) {
      throw new RuntimeException(
          "git command [git " + String.join(" ", gitArgs) + "] exited with " + exit + ": " + out);
    }
  }
}
