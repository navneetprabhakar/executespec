package com.navneet.executespec.run.sync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

  /** Wall-clock ceiling for a single git child. A fetch against a dead remote must not hang. */
  private static final long GIT_TIMEOUT_SECONDS = 120L;

  private final WorkspaceInitService workspaceInit;
  private final com.navneet.executespec.state.repository.RunRepoSnapshotRepository snapshotRepo;

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
    return branch != null && BRANCH.matcher(branch).matches();
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
      String sha = snap.getCommitSha();
      if (sha == null || !COMMIT_SHA.matcher(sha).matches()) {
        // A snapshot that does not hold a full object id is not a reset target. Resetting to an
        // arbitrary string would let a malformed row reach git as an option or a revision range.
        details.add(shortName + " FAILED: snapshot commit is not a 40-character object id");
        log.warn(
            "RunSync(original): run={} repo={} rejected malformed snapshot sha", runId, shortName);
        continue;
      }
      try {
        // RR-1554: the fetch must succeed before the reset. Previously a failed fetch still fell
        // through to `git reset --hard <sha>`, hard-resetting the workspace — discarding state —
        // while the response reported failure.
        runGit(repoRoot, credential, "fetch", "origin");
        runGit(repoRoot, credential, "reset", "--hard", sha, "--");
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
        runGit(repoRoot, credential, "fetch", "origin", "--", branch);
        runGit(repoRoot, credential, "reset", "--hard", "origin/" + branch, "--");
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
    GitCredential cred = credential == null ? GitCredential.NONE : credential;
    ProcessBuilder pb =
        com.navneet.executespec.repo.GitProcessAuth.git(
            cred.present() ? cred.secret() : null, cred.username(), List.of(gitArgs));
    pb.directory(dir.toFile());
    pb.redirectErrorStream(true);
    ExecResult result = runBounded(pb);
    if (result.exitCode() != 0) {
      // Never echo argv: GitProcessAuth keeps the credential out of it, but the reconstructed
      // command adds nothing a caller can act on beyond the git error itself.
      throw new RuntimeException("git " + gitArgs[0] + " failed: " + result.output());
    }
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
