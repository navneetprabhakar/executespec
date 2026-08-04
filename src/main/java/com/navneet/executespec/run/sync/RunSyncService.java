package com.navneet.executespec.run.sync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.navneet.executespec.deployment.WorkspaceArchiveUnavailableException;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.PipelineRunRepository;

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
 *
 * <p><b>Credentials (RR-1554).</b> Every git child runs through {@link
 * com.navneet.executespec.repo.GitProcessAuth} so the caller's PAT / app password rides the child
 * environment. Before this, {@code /sync} and {@code /sync/original} built a bare {@link
 * ProcessBuilder} with no credential material, so after the 2026-07-17 credential gate removed
 * tokens from persisted remotes both endpoints could not authenticate against any private
 * repository.
 *
 * <p><b>Argument safety (RR-1555).</b> Git parses options anywhere before {@code --}, so a branch
 * beginning with {@code -} would be consumed as a git option rather than a refspec. Branch names
 * are shape-validated and commit SHAs must be 40-hex; every git child is additionally bounded by a
 * wall-clock timeout and force-destroyed on expiry so a stalled remote cannot pin a request thread
 * forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunSyncService {

  /**
   * Conservative git ref shape. Deliberately narrower than {@code git check-ref-format}: it forbids
   * a leading {@code -} (the option-injection vector), whitespace, and every shell/refspec
   * metacharacter, while still admitting the ordinary {@code feature/foo-1.2_bar} namespace.
   */
  private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,199}");

  private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-fA-F]{40}");

  private static final Pattern SCM_FULL_NAME = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");

  /** Wall-clock ceiling for a single git child. A fetch against a dead remote must not hang. */
  private static final long GIT_TIMEOUT_SECONDS = 120L;

  private final WorkspaceInitService workspaceInit;
  private final com.navneet.executespec.state.repository.RunRepoSnapshotRepository snapshotRepo;
  private final ProjectRepoRepository projectRepoRepository;
  private final RunWorkspaceAuthorityService workspaceAuthorities;
  private final WorkspaceRestoreService workspaceRestore;
  private final PipelineRunRepository pipelineRunRepository;

  public record SyncOutcome(int reposSynced, List<String> details) {}

  /** Credential material for the git children of one sync, resolved from the calling user. */
  public record GitCredential(String username, String secret) {

    public static final GitCredential NONE = new GitCredential(null, null);

    /** GitHub PAT convention — {@code GitProcessAuth} defaults the username to {@code oauth2}. */
    public static GitCredential github(String pat) {
      return pat == null || pat.isBlank() ? NONE : new GitCredential(null, pat);
    }

    /** Bitbucket scoped API token; the static Git username is Bitbucket's documented one. */
    public static GitCredential bitbucket(String appPassword) {
      return appPassword == null || appPassword.isBlank()
          ? NONE
          : new GitCredential("x-bitbucket-api-token-auth", appPassword);
    }

    boolean present() {
      return secret != null && !secret.isBlank();
    }
  }

  /** True when {@code branch} is a safe refspec token (never an option, never a metacharacter). */
  public static boolean isValidBranch(String branch) {
    if (branch == null || !BRANCH.matcher(branch).matches()) return false;
    if (branch.contains("..")
        || branch.contains("//")
        || branch.endsWith("/")
        || branch.endsWith(".")
        || branch.contains("@{")
        || branch.equals("@")) return false;
    for (String component : branch.split("/", -1)) {
      if (component.isBlank() || component.startsWith(".") || component.endsWith(".lock")) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reset each repo in a run workspace back to the exact commit SHA it was cloned from at run
   * start. Relies on {@code run_repo_snapshot} rows written by {@link
   * WorkspaceInitService#cloneMultipleRepos}. Pre-v2.5.2 runs have no snapshots and can't be
   * reset-to-original — caller gets an empty {@code details} list, {@code reposSynced=0}.
   *
   * <p>Safe only on terminal runs — API layer enforces that invariant.
   */
  public SyncOutcome syncToOriginalCommit(String runId, GitCredential credential) {
    return withWorkspaceMutationAuthority(
        runId, authority -> syncToOriginalCommitOwned(runId, credential, authority));
  }

  private SyncOutcome syncToOriginalCommitOwned(
      String runId, GitCredential credential, RunWorkspaceAuthorityService.Lease authority) {
    Path runRoot = availableWorkspace(runId);
    List<RunRepoSnapshot> snaps = snapshotRepo.findByRunId(runId);
    if (snaps.isEmpty()) {
      return new SyncOutcome(0, List.of("no snapshot — run predates v2.5.2"));
    }
    ensureRepositoryMetadata(
        runRoot,
        snaps.stream()
            .filter(s -> s.getCommitSha() != null && COMMIT_SHA.matcher(s.getCommitSha()).matches())
            .toList());
    List<String> details = new ArrayList<>();
    int synced = 0;
    for (RunRepoSnapshot snap : snaps) {
      String shortName = shortName(snap.getRepoFullName());
      String sha = snap.getCommitSha();
      if (sha == null || !COMMIT_SHA.matcher(sha).matches()) {
        // A snapshot that does not hold a full object id is not a reset target. Resetting to an
        // arbitrary string would let a malformed row reach git as an option or a revision range.
        details.add(shortName + " FAILED: snapshot commit is not a 40-character object id");
        log.warn(
            "RunSync(original): run={} repo={} rejected malformed snapshot sha", runId, shortName);
        continue;
      }
      Path repoRoot = repositoryRoot(runRoot, snap);
      if (!Files.isDirectory(repoRoot.resolve(".git"))) {
        details.add(shortName + " MISSING (no admitted .git found)");
        continue;
      }
      try {
        // RR-1554: the fetch must succeed before the reset. Previously a failed fetch still fell
        // through to `git reset --hard <sha>`, hard-resetting the workspace — discarding state —
        // while the response reported failure.
        authority.assertOwned();
        runGit(repoRoot, credential, "fetch", "origin");
        authority.assertOwned();
        runGit(repoRoot, credential, "reset", "--hard", sha, "--");
        runGit(repoRoot, GitCredential.NONE, "clean", "-fd", "-e", ".agent-context/");
        synced++;
        details.add(shortName + " <- " + sha.substring(0, 7) + " (original)");
        log.info("RunSync(original): run={} repo={} reset to {}", runId, shortName, sha);
      } catch (Exception e) {
        details.add(shortName + " FAILED: " + e.getMessage());
        log.warn("RunSync(original): run={} repo={} failed: {}", runId, shortName, e.getMessage());
      }
    }
    return new SyncOutcome(synced, details);
  }

  public SyncOutcome sync(String runId, String branchOverride, GitCredential credential) {
    if (branchOverride != null && !branchOverride.isBlank() && !isValidBranch(branchOverride)) {
      // Defence in depth — the controller rejects this with 400 before we are called.
      throw new IllegalArgumentException("Invalid branch name");
    }
    return withWorkspaceMutationAuthority(
        runId, authority -> syncOwned(runId, branchOverride, credential, authority));
  }

  private SyncOutcome syncOwned(
      String runId,
      String branchOverride,
      GitCredential credential,
      RunWorkspaceAuthorityService.Lease authority) {
    Path runRoot = availableWorkspace(runId);
    List<RunRepoSnapshot> snapshots = snapshotRepo.findByRunId(runId);
    if (snapshots.isEmpty()) {
      return new SyncOutcome(0, List.of("no admitted repository snapshot"));
    }
    ensureRepositoryMetadata(runRoot, snapshots);
    List<String> details = new ArrayList<>();
    int synced = 0;
    for (RunRepoSnapshot snapshot : snapshots) {
      Path repoRoot = repositoryRoot(runRoot, snapshot);
      String name = repoRoot.equals(runRoot) ? "(root)" : repoRoot.getFileName().toString();
      String branch =
          (branchOverride != null && !branchOverride.isBlank())
              ? branchOverride
              : isValidBranch(snapshot.getBranch())
                  ? snapshot.getBranch()
                  : detectDefaultBranch(repoRoot);
      try {
        authority.assertOwned();
        runGit(repoRoot, credential, "fetch", "origin", "--", branch);
        authority.assertOwned();
        runGit(repoRoot, credential, "reset", "--hard", "origin/" + branch, "--");
        runGit(repoRoot, GitCredential.NONE, "clean", "-fd", "-e", ".agent-context/");
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

  private SyncOutcome withWorkspaceMutationAuthority(
      String runId,
      java.util.function.Function<RunWorkspaceAuthorityService.Lease, SyncOutcome> operation) {
    try (RunWorkspaceAuthorityService.Lease authority =
        workspaceAuthorities.acquire(runId, RunWorkspaceAuthorityService.Authority.PUBLICATION)) {
      authority.assertOwned();
      return operation.apply(authority);
    }
  }

  private Path availableWorkspace(String runId) {
    Path local = workspaceInit.runWorkspacePath(runId);
    if (Files.isDirectory(local)) return local;
    try {
      Path restored = workspaceRestore.ensureWorkspaceAvailable(runId);
      if (restored != null && Files.isDirectory(restored)) return restored;
    } catch (WorkspaceArchiveUnavailableException unavailable) {
      throw new RunWorkspaceUnavailableException(runId, unavailable);
    } catch (RuntimeException restoreFailure) {
      throw new RunWorkspaceRestoreFailedException(runId, restoreFailure);
    }
    throw new RunWorkspaceUnavailableException(runId, null);
  }

  /**
   * GCS archives intentionally exclude {@code .git}. Rebuild only repository metadata from the
   * authoritative run snapshots and credential-free project clone coordinates before a sync. The
   * subsequent authenticated fetch/reset remains the operation that chooses source bytes.
   */
  private void ensureRepositoryMetadata(Path runRoot, List<RunRepoSnapshot> snapshots) {
    if (snapshots == null || snapshots.isEmpty()) return;
    for (RunRepoSnapshot snapshot : snapshots) {
      Path repoRoot = repositoryRoot(runRoot, snapshot);
      try {
        Files.createDirectories(repoRoot);
        if (!Files.isDirectory(repoRoot.resolve(".git"))) {
          runGit(repoRoot, GitCredential.NONE, "init", "-b", "main");
        }
        String expectedRemote = cloneUrl(snapshot);
        ExecResult current =
            runGitResult(repoRoot, GitCredential.NONE, "remote", "get-url", "origin");
        if (current.exitCode() != 0) {
          runGit(repoRoot, GitCredential.NONE, "remote", "add", "origin", expectedRemote);
        } else if (!sameRemote(current.output().trim(), expectedRemote)) {
          throw new IllegalStateException("origin does not match the authoritative repository");
        }
      } catch (RunWorkspaceRestoreFailedException failure) {
        throw failure;
      } catch (Exception failure) {
        throw new RunWorkspaceRestoreFailedException(snapshot.getRunId(), failure);
      }
    }
  }

  private Path repositoryRoot(Path runRoot, RunRepoSnapshot snapshot) {
    String name = shortName(snapshot.getRepoFullName());
    Path nested = runRoot.resolve("repos").resolve(name);
    return snapshot.getRepoId() == null ? runRoot : nested;
  }

  String cloneUrl(RunRepoSnapshot snapshot) {
    if (snapshot.getRepoId() == null) {
      String original =
          pipelineRunRepository
              .findById(snapshot.getRunId())
              .map(com.navneet.executespec.state.entity.PipelineRun::getRepoUrl)
              .filter(value -> value != null && !value.isBlank())
              .orElseThrow(
                  () ->
                      new RunWorkspaceRestoreFailedException(
                          snapshot.getRunId(),
                          new IllegalStateException(
                              "standalone repository clone coordinate is unavailable")));
      return safeCloneRemote(snapshot.getRunId(), original);
    }
    ProjectRepo repo =
        projectRepoRepository
            .findById(snapshot.getRepoId())
            .orElseThrow(
                () ->
                    new RunWorkspaceRestoreFailedException(
                        snapshot.getRunId(),
                        new IllegalStateException("project repository coordinate is unavailable")));
    if (ProjectRepo.SourceType.LOCAL.name().equalsIgnoreCase(repo.getSourceType())) {
      throw new RunWorkspaceRestoreFailedException(
          snapshot.getRunId(), new IllegalStateException("local source has no SCM remote"));
    }
    if (repo.getScmCloneUrl() != null && !repo.getScmCloneUrl().isBlank()) {
      return safeCloneRemote(snapshot.getRunId(), repo.getScmCloneUrl());
    }
    String provider = repo.getScmProvider();
    if ("GITHUB".equalsIgnoreCase(provider)) {
      return "https://github.com/" + safeFullName(snapshot.getRepoFullName()) + ".git";
    }
    if ("BITBUCKET".equalsIgnoreCase(provider)) {
      return "https://bitbucket.org/" + safeFullName(snapshot.getRepoFullName()) + ".git";
    }
    throw new RunWorkspaceRestoreFailedException(
        snapshot.getRunId(),
        new IllegalStateException("project repository clone coordinate is unavailable"));
  }

  private static String safeCloneRemote(String runId, String cloneUrl) {
    try {
      return credentialFreeRemote(cloneUrl);
    } catch (IllegalArgumentException unsafeCoordinate) {
      throw new RunWorkspaceRestoreFailedException(runId, unsafeCoordinate);
    }
  }

  private static String credentialFreeRemote(String cloneUrl) {
    String trimmed = cloneUrl.trim();
    try {
      java.net.URI uri = java.net.URI.create(trimmed);
      if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException("repository clone URL contains unsafe URI components");
      }
    } catch (IllegalArgumentException malformed) {
      if (trimmed.contains("://")) throw malformed;
      // Local/file paths are useful for deterministic tests and carry no remote credential.
    }
    return trimmed;
  }

  private static String safeFullName(String fullName) {
    if (fullName == null || !SCM_FULL_NAME.matcher(fullName).matches()) {
      throw new IllegalArgumentException("repository full name is invalid");
    }
    return fullName;
  }

  private static String shortName(String fullName) {
    String safe = safeFullName(fullName);
    return safe.substring(safe.indexOf('/') + 1);
  }

  private static boolean sameRemote(String current, String expected) {
    return normalizeRemote(current).equals(normalizeRemote(expected));
  }

  private static String normalizeRemote(String value) {
    String normalized = value == null ? "" : value.trim();
    while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized.endsWith(".git")
        ? normalized.substring(0, normalized.length() - ".git".length())
        : normalized;
  }

  private String detectDefaultBranch(Path repoRoot) {
    try {
      // Resolve symbolic ref of origin/HEAD: outputs "refs/remotes/origin/<branch>"
      ProcessBuilder pb =
          new ProcessBuilder("git", "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
              .directory(repoRoot.toFile())
              .redirectErrorStream(true);
      String out = runBounded(pb).output().trim();
      if (out.startsWith("origin/")) {
        String candidate = out.substring("origin/".length());
        // The remote controls this value; validate it exactly like a user-supplied branch.
        if (isValidBranch(candidate)) return candidate;
      }
    } catch (Exception ignore) {
    }
    return "main";
  }

  private void runGit(Path dir, GitCredential credential, String... gitArgs) throws Exception {
    ExecResult result = runGitResult(dir, credential, gitArgs);
    if (result.exitCode() != 0) {
      // Never echo argv: GitProcessAuth keeps the credential out of it, but the reconstructed
      // command adds nothing a caller can act on beyond the git error itself.
      throw new RuntimeException("git " + gitArgs[0] + " failed: " + result.output());
    }
  }

  private ExecResult runGitResult(Path dir, GitCredential credential, String... gitArgs)
      throws Exception {
    GitCredential cred = credential == null ? GitCredential.NONE : credential;
    ProcessBuilder pb =
        // RR-1669: this caller selects the repository via ProcessBuilder.directory rather than a
        // -C argument, so the directory must be passed explicitly or the authenticated fetch
        // cannot be proven un-rewritten and is refused.
        com.navneet.executespec.repo.GitProcessAuth.git(
            cred.present() ? cred.secret() : null, cred.username(), dir, List.of(gitArgs));
    pb.directory(dir.toFile());
    pb.redirectErrorStream(true);
    return runBounded(pb);
  }

  private record ExecResult(int exitCode, String output) {}

  /**
   * Runs a child with a hard wall-clock bound. On expiry the child (and its descendants) are
   * force-destroyed and the caller sees a timeout, rather than the request thread blocking on
   * {@code waitFor()} indefinitely.
   */
  private static ExecResult runBounded(ProcessBuilder pb) throws Exception {
    Process p = pb.start();
    try {
      String out = new String(p.getInputStream().readAllBytes());
      if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
        throw new RuntimeException("git timed out after " + GIT_TIMEOUT_SECONDS + "s");
      }
      return new ExecResult(p.exitValue(), out);
    } finally {
      if (p.isAlive()) {
        p.descendants().forEach(ProcessHandle::destroyForcibly);
        p.destroyForcibly();
      }
    }
  }
}
