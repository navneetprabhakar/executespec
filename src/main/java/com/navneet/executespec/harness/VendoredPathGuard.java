package com.navneet.executespec.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
                  "|",
                  VENDORED_DIRS.stream().map(java.util.regex.Pattern::quote).toList())
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
          "VendoredPathGuard: failed to augment .gitignore in {}: {}",
          repoDir,
          e.getMessage());
    }
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
        if (isVendored(path)) vendoredHits.add(rootSegmentOf(path));
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
          runGit(repoDir, List.of("rm", "--cached", "-r", "--ignore-unmatch", hit), 30);
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

  // ── helpers ──────────────────────────────────────────────────────────────

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
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true);
    pb.environment().keySet().retainAll(List.of("PATH", "HOME"));
    Process p = pb.start();
    List<String> lines = new ArrayList<>();
    try (var br =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(
                p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) lines.add(line);
    }
    boolean done = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      throw new IOException("git command timed out: " + cmd);
    }
    if (p.exitValue() != 0) {
      throw new IOException("git " + args + " exit=" + p.exitValue() + " — " + lines);
    }
    return lines;
  }
}
