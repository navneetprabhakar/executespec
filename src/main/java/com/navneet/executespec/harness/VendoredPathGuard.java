package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.navneet.executespec.testgen.BoundedProcess;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * 2026-05-02 — central guard against vendored / build-artefact paths leaking into customer commits.
 *
 * <p>Root cause this exists for: a worker added a Node service to a Python repo whose {@code
 * .gitignore} was Python-only. {@code npm install} populated {@code node_modules/}, then {@code git
 * add -A && git commit} captured 9605 vendored files (run b171fa52 — verified score plummeted
 * because the reviewer + smoke-build had to wade through 800k LoC of vendored noise).
 *
 * <p>Two enforcement points use this guard:
 *
 * <ol>
 *   <li>{@link WorkspaceInitService#cloneMultipleRepos} calls {@link #augmentGitignore} right after
 *       each clone, so the worker starts against a {@code .gitignore} that already covers
 *       node_modules/, venv/, dist/, etc., regardless of what the upstream repo had.
 *   <li>{@code GitCommitTool} calls {@link #purgeStagedVendored} between {@code git add -A} and
 *       {@code git commit} as a last-line defense for shell-driven commits the worker might run.
 * </ol>
 *
 * <p>The patterns kept here are also the source of truth for the runtime exclusions in {@code
 * AccuracyAssessmentService.inferKeyFilePaths} and {@code WorkspaceFileService.SKIP_DIRS} — the
 * regex form ({@link #VENDORED_REGEX}) is exposed for those callers to reuse so we don't drift
 * three independent allowlists.
 */
@Service
@Slf4j
public class VendoredPathGuard {

  /**
   * Path segments we never want committed. Order matters only for human readability. Each entry is
   * matched as a directory segment ({@code "/<entry>/"} or leading {@code "<entry>/"}) — never as a
   * substring, to avoid false positives like a customer file called {@code build.gradle.kts}.
   */
  public static final List<String> VENDORED_DIRS =
      List.of(
          "node_modules",
          "venv",
          ".venv",
          "__pycache__",
          "dist",
          "build",
          "target",
          ".next",
          ".gradle",
          "coverage",
          "bower_components",
          "vendor", // Go modules / PHP composer
          ".idea",
          ".vscode",
          ".pytest_cache",
          ".mypy_cache",
          ".tox",
          ".nyc_output");

  /**
   * Single canonical regex for runtime "is this a vendored path?" decisions. Matches if any segment
   * of the slash-delimited path equals one of {@link #VENDORED_DIRS}. Case-insensitive because some
   * tooling lower-cases paths inconsistently across platforms.
   */
  public static final java.util.regex.Pattern VENDORED_REGEX =
      java.util.regex.Pattern.compile(
          "(?i)(^|/)("
              + String.join(
                  "|", VENDORED_DIRS.stream().map(java.util.regex.Pattern::quote).toList())
              + ")(/|$)");

  /** Returns {@code true} when {@code workspaceRelativePath} sits inside a vendored directory. */
  public static boolean isVendored(String workspaceRelativePath) {
    if (workspaceRelativePath == null || workspaceRelativePath.isBlank()) return false;
    return VENDORED_REGEX.matcher(workspaceRelativePath).find();
  }

  /**
   * Append any missing {@link #VENDORED_DIRS} entries to {@code <repoDir>/.gitignore}. Idempotent —
   * existing entries are kept exactly as-is, only missing ones are appended under a clearly marked
   * {@code # >>> ExecuteSpec} block so the user can spot/revert. Stages and commits the change so
   * subsequent {@code git add -A} sees the updated ignore patterns.
   *
   * <p>No-op when none of the patterns are missing.
   */
  public void augmentGitignore(Path repoDir) {
    if (!Files.isDirectory(repoDir.resolve(".git"))) {
      log.debug("VendoredPathGuard: {} is not a git repo, skipping", repoDir);
      return;
    }
    try {
      Path gitignore = repoDir.resolve(".gitignore");
      Set<String> existing = readExistingPatterns(gitignore);
      List<String> missing = new ArrayList<>();
      for (String dir : VENDORED_DIRS) {
        // Match either "node_modules" or "node_modules/" — gitignore treats both as ignore-the-dir.
        if (!existing.contains(dir) && !existing.contains(dir + "/")) {
          missing.add(dir + "/");
        }
      }
      if (missing.isEmpty()) {
        log.debug("VendoredPathGuard: {} already covers all vendored dirs", repoDir);
        return;
      }

      StringBuilder appendix = new StringBuilder();
      if (Files.exists(gitignore)) {
        String body = Files.readString(gitignore);
        if (!body.endsWith("\n")) appendix.append('\n');
      }
      appendix.append(
          "\n# >>> ExecuteSpec — added missing vendored-path ignores at clone time"
              + " (see VendoredPathGuard)\n");
      for (String pattern : missing) appendix.append(pattern).append('\n');
      appendix.append("# <<< ExecuteSpec\n");

      Files.writeString(
          gitignore,
          appendix.toString(),
          java.nio.file.StandardOpenOption.CREATE,
          java.nio.file.StandardOpenOption.APPEND);

      // Commit the augmented .gitignore so it's part of the run's history (not just an
      // uncommitted local edit). If the repo identity isn't configured we set a local one so
      // the commit doesn't fail under CI / sandboxed environments.
      ensureLocalIdentity(repoDir);
      runGit(repoDir, List.of("add", ".gitignore"), 5);
      runGit(
          repoDir,
          List.of(
              "commit",
              "--allow-empty",
              "-m",
              "chore(executespec): augment .gitignore with missing vendored-path ignores"),
          10);

      log.info(
          "VendoredPathGuard: appended {} ignore pattern(s) to {}/.gitignore: {}",
          missing.size(),
          repoDir.getFileName(),
          missing);
    } catch (Exception e) {
      // Non-fatal — we tried to be helpful, but don't fail the run because we couldn't.
      log.warn(
          "VendoredPathGuard: failed to augment .gitignore in {}: {}", repoDir, e.getMessage());
    }
  }

  /**
   * Scrub vendored content from a repo's <i>committed</i> history-of-record by listing every
   * tracked path, identifying vendored ones, augmenting {@code .gitignore}, and creating a single
   * cleanup commit that runs {@code git rm --cached -r} for each affected root segment. The
   * worktree is left untouched (so {@code npm install} output stays usable). Designed for the
   * "clean up an existing run's workspace before re-running the reviewer" path — cleaner than
   * {@link #purgeStagedVendored} which only catches uncommitted staged files.
   *
   * <p>Idempotent: a second invocation finds nothing tracked + no-ops.
   *
   * <p>Returns a small report so callers can log/expose how much was scrubbed.
   */
  public ScrubResult scrubCommittedVendored(Path repoDir) {
    if (!Files.isDirectory(repoDir.resolve(".git"))) {
      return new ScrubResult(0, 0, java.util.Set.of(), false);
    }
    try {
      List<String> tracked = runGit(repoDir, List.of("ls-files"), 30);
      java.util.LinkedHashSet<String> vendoredRoots = new java.util.LinkedHashSet<>();
      int vendoredFileCount = 0;
      for (String path : tracked) {
        if (isVendored(path)) {
          // The exact vendored subtree prefix (e.g. "repos/api/node_modules"), relative to repoDir.
          // RR-649: a null/unsafe target is refused, not widened to the first path segment.
          String target = firstVendoredSegmentOf(path);
          if (isSafeVendoredRemovalTarget(target)) {
            vendoredFileCount++;
            vendoredRoots.add(target);
          } else {
            log.warn(
                "VendoredPathGuard.scrub: refusing to remove {} in {} — no unambiguous vendored"
                    + " subtree could be derived",
                path,
                repoDir.getFileName());
          }
        }
      }
      if (vendoredRoots.isEmpty()) {
        log.debug(
            "VendoredPathGuard.scrub: {} has no tracked vendored content (no-op)",
            repoDir.getFileName());
        return new ScrubResult(0, tracked.size(), java.util.Set.of(), true);
      }

      log.info(
          "VendoredPathGuard.scrub: {} has {} tracked vendored file(s) under {} root(s): {}",
          repoDir.getFileName(),
          vendoredFileCount,
          vendoredRoots.size(),
          vendoredRoots);

      // 1. Make sure .gitignore covers them so the next `git add -A` doesn't re-stage.
      augmentGitignore(repoDir);

      // 2. Remove from index without touching the worktree. --ignore-unmatch keeps us
      //    idempotent on retries / when a sub-path has already been cleaned by the
      //    augmentation commit.
      ensureLocalIdentity(repoDir);
      for (String segment : vendoredRoots) {
        try {
          runGit(repoDir, List.of("rm", "--cached", "-r", "--ignore-unmatch", "--", segment), 60);
        } catch (Exception inner) {
          log.warn(
              "git rm --cached failed for {}/{}: {}",
              repoDir.getFileName(),
              segment,
              inner.getMessage());
        }
      }
      // Re-stage .gitignore in case augmentGitignore's auto-commit captured it before the rm.
      try {
        runGit(repoDir, List.of("add", ".gitignore"), 5);
      } catch (Exception ignored) {
        // best-effort
      }
      // 3. One scrub commit so the cleanup is atomic in history.
      // RR-650 — the commit outcome is now part of the result contract. When it fails, the removals
      // remain only STAGED (not committed), so a later reset/checkout can restore the vendored
      // content or an unrelated commit can absorb the large staged deletion — the caller must not
      // be
      // told the scrub durably succeeded.
      boolean committed = false;
      try {
        runGit(
            repoDir,
            List.of(
                "commit",
                "--allow-empty",
                "-m",
                "chore(executespec): scrub committed vendored content ("
                    + vendoredFileCount
                    + " files across "
                    + vendoredRoots.size()
                    + " roots)"),
            30);
        committed = true;
      } catch (Exception e) {
        log.warn(
            "VendoredPathGuard.scrub: scrub commit failed in {}: {} — removals remain staged only",
            repoDir.getFileName(),
            e.getMessage());
      }
      return new ScrubResult(vendoredFileCount, tracked.size(), vendoredRoots, committed);
    } catch (Exception e) {
      log.warn("VendoredPathGuard.scrub failed for {}: {}", repoDir.getFileName(), e.getMessage());
      return new ScrubResult(0, 0, java.util.Set.of(), false);
    }
  }

  /**
   * Result of a {@link #scrubCommittedVendored} call.
   *
   * @param committed RR-650 — true only when the scrub commit landed. When false with {@code
   *     filesRemoved > 0}, the removals are staged but NOT durably committed (a partial state).
   */
  public record ScrubResult(
      int filesRemoved, int totalTrackedAtScan, java.util.Set<String> roots, boolean committed) {
    public boolean changed() {
      return filesRemoved > 0;
    }
  }

  /**
   * RR-649 (2026-07-21) — returns the exact prefix ending at the FIRST path segment that is itself
   * a vendored directory, or {@code null} when no segment matches.
   *
   * <p>For {@code frontend/node_modules/pkg/index.js} this returns {@code frontend/node_modules} —
   * NOT {@code frontend}. The previous {@link #rootSegmentOf} fallback here (and at the {@code
   * purgeStagedVendored} call site) meant a nested vendored directory resolved to the application
   * subtree, and the subsequent {@code git rm --cached -r} unstaged every tracked source file under
   * it. Returning {@code null} instead of guessing is deliberate: an ambiguous removal target must
   * be refused, never widened.
   *
   * <p>Matching is case-insensitive so it agrees with {@link #VENDORED_REGEX}. The prior {@code
   * VENDORED_DIRS.contains(p)} check was case-SENSITIVE while {@code isVendored} was not, so a path
   * like {@code Frontend/Node_Modules/x} passed the vendored test and then fell through to the
   * root-segment fallback — the same catastrophic widening, reachable from the scrub path too.
   */
  static String firstVendoredSegmentOf(String path) {
    if (path == null || path.isBlank()) return null;
    String[] parts = path.split("/");
    StringBuilder accum = new StringBuilder();
    for (String p : parts) {
      if (p.isEmpty()) continue;
      if (accum.length() > 0) accum.append('/');
      accum.append(p);
      for (String vendored : VENDORED_DIRS) {
        if (vendored.equalsIgnoreCase(p)) return accum.toString();
      }
    }
    return null;
  }

  /**
   * RR-649 — final sanity check before a removal target is handed to {@code git rm --cached -r}.
   * The target must be non-blank, relative, traversal-free, and its LAST segment must itself be a
   * vendored directory name. Anything else is refused rather than removed.
   */
  static boolean isSafeVendoredRemovalTarget(String target) {
    if (target == null || target.isBlank()) return false;
    if (target.startsWith("/") || target.startsWith("-")) return false;
    String[] parts = target.split("/");
    if (parts.length == 0) return false;
    for (String p : parts) {
      if (p.isEmpty() || ".".equals(p) || "..".equals(p)) return false;
    }
    String last = parts[parts.length - 1];
    for (String vendored : VENDORED_DIRS) {
      if (vendored.equalsIgnoreCase(last)) return true;
    }
    return false;
  }

  /**
   * After {@code git add -A}, scan the staged files for vendored paths. If any are present:
   *
   * <ol>
   *   <li>Append the missing patterns to {@code .gitignore} (idempotent).
   *   <li>Run {@code git rm --cached -r --ignore-unmatch <pattern>} for each so they leave the
   *       index without touching the worktree.
   *   <li>Re-stage {@code .gitignore} and return — caller continues with {@code git commit}.
   * </ol>
   *
   * <p>Returns the number of vendored paths purged from the index (0 = no-op). Best-effort: any
   * failure is logged and surfaces as 0.
   *
   * <p>Operates on the supplied {@code repoDir}, which is expected to be a single git checkout.
   * Multi-repo workspaces drive this per subdir.
   */
  public int purgeStagedVendored(Path repoDir) {
    if (!Files.isDirectory(repoDir.resolve(".git"))) {
      return 0;
    }
    try {
      List<String> staged = runGit(repoDir, List.of("diff", "--cached", "--name-only"), 10);
      Set<String> vendoredHits = new LinkedHashSet<>();
      for (String path : staged) {
        if (!isVendored(path)) continue;
        // RR-649: the exact vendored subtree, never the first path segment. A null/unsafe target
        // means we could not identify the vendored directory — leave the path staged rather than
        // deleting a guess.
        String target = firstVendoredSegmentOf(path);
        if (isSafeVendoredRemovalTarget(target)) {
          vendoredHits.add(target);
        } else {
          log.warn(
              "VendoredPathGuard: refusing to unstage {} in {} — no unambiguous vendored subtree"
                  + " could be derived",
              path,
              repoDir.getFileName());
        }
      }
      if (vendoredHits.isEmpty()) return 0;

      log.warn(
          "VendoredPathGuard: pre-commit guard caught {} vendored path(s) staged in {}: {}"
              + " — augmenting .gitignore and unstaging",
          vendoredHits.size(),
          repoDir.getFileName(),
          vendoredHits);

      // Augment ignore file BEFORE removing from index, so the next add doesn't re-stage them.
      augmentGitignore(repoDir);

      // Remove from index without touching the worktree. --ignore-unmatch keeps us idempotent
      // on retries / when the path was already cleaned by augmentGitignore's own commit.
      for (String hit : vendoredHits) {
        try {
          runGit(repoDir, List.of("rm", "--cached", "-r", "--ignore-unmatch", "--", hit), 30);
        } catch (Exception inner) {
          log.debug("git rm --cached failed for {}: {}", hit, inner.getMessage());
        }
      }
      // Re-stage .gitignore in case augmentGitignore's auto-commit didn't fire.
      try {
        runGit(repoDir, List.of("add", ".gitignore"), 5);
      } catch (Exception ignored) {
        // best-effort
      }
      return vendoredHits.size();
    } catch (Exception e) {
      log.debug("VendoredPathGuard.purgeStagedVendored failed for {}: {}", repoDir, e.getMessage());
      return 0;
    }
  }

  private Set<String> readExistingPatterns(Path gitignore) throws IOException {
    Set<String> out = new LinkedHashSet<>();
    if (!Files.exists(gitignore)) return out;
    for (String line : Files.readAllLines(gitignore)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      // Strip leading "/" — equivalent in gitignore semantics for our flat dir patterns.
      if (trimmed.startsWith("/")) trimmed = trimmed.substring(1);
      // Strip trailing "/" so we can compare with both forms.
      out.add(trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed);
      out.add(trimmed.endsWith("/") ? trimmed : trimmed + "/");
    }
    return out;
  }

  private String rootSegmentOf(String path) {
    int slash = path.indexOf('/');
    return slash > 0 ? path.substring(0, slash) : path;
  }

  private void ensureLocalIdentity(Path repoDir) {
    try {
      List<String> name = runGit(repoDir, List.of("config", "--get", "user.name"), 3);
      if (name.isEmpty() || name.get(0).isBlank()) {
        runGit(repoDir, List.of("config", "user.name", "ExecuteSpec Agent"), 3);
      }
    } catch (Exception ignored) {
      try {
        runGit(repoDir, List.of("config", "user.name", "ExecuteSpec Agent"), 3);
      } catch (Exception ignored2) {
        // give up
      }
    }
    try {
      List<String> email = runGit(repoDir, List.of("config", "--get", "user.email"), 3);
      if (email.isEmpty() || email.get(0).isBlank()) {
        runGit(repoDir, List.of("config", "user.email", "agent@executespec.local"), 3);
      }
    } catch (Exception ignored) {
      try {
        runGit(repoDir, List.of("config", "user.email", "agent@executespec.local"), 3);
      } catch (Exception ignored2) {
        // give up
      }
    }
  }

  private List<String> runGit(Path repoDir, List<String> args, int timeoutSeconds)
      throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(args.size() + 1);
    cmd.add("git");
    cmd.addAll(args);
    ProcessBuilder pb =
        new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true);
    pb.environment().keySet().retainAll(List.of("PATH", "HOME"));
    BoundedProcess.Outcome outcome = runBoundedGit(pb, timeoutSeconds, cmd);
    List<String> lines = outcome.output().lines().toList();
    if (outcome.exitCode() != 0) {
      throw new IOException("git " + args + " exit=" + outcome.exitCode() + " — " + lines);
    }
    return lines;
  }

  /**
   * RR-651: run a git child through {@link BoundedProcess} so the timed wait is reachable while the
   * child is still alive — reading its output to EOF on this thread first (the old shape) let a
   * hung git that kept its descriptor open block the pipeline thread past every deadline. A timeout
   * is surfaced as an {@link IOException} rather than a silent hang. Package-private for the
   * hung-child regression test.
   */
  static BoundedProcess.Outcome runBoundedGit(ProcessBuilder pb, int timeoutSeconds, Object label)
      throws IOException {
    BoundedProcess.Outcome outcome;
    try {
      outcome = BoundedProcess.run(pb, java.time.Duration.ofSeconds(timeoutSeconds));
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("git " + label + " failed: " + e.getMessage(), e);
    }
    if (!outcome.finished()) {
      throw new IOException("git command timed out: " + label);
    }
    return outcome;
  }
}
