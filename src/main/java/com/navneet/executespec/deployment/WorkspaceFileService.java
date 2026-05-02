package com.navneet.executespec.deployment;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepoSnapshot;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Provides read-only access to the files produced by a pipeline run.
 *
 * <p>Each run has its own workspace at {@code <agent.workspace>/<runId>/}, so files are read
 * directly from the filesystem.
 *
 * <p><b>GitHub fallback:</b> if the local workspace has been deleted but the run has a {@code
 * repoUrl}, files are served from GitHub via the Contents/Trees API.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFileService {

  private final AgentProperties agentProps;
  private final StateStore stateStore;
  private final GitHubRepoService gitHubRepoService;
  private final WorkspaceRestoreService restoreService;
  private final RunRepoSnapshotRepository runRepoSnapshotRepository;

  private static final long MAX_FILE_SIZE = 1024 * 1024; // 1 MB

  private static final List<String> SKIP_DIRS =
      List.of(
          ".git",
          ".agent-context",
          "target",
          "build",
          "node_modules",
          ".gradle",
          ".idea",
          ".vscode",
          "worktrees");
  private static final List<String> SKIP_EXTENSIONS =
      List.of(
          ".class", ".jar", ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg", ".gif", ".ico", ".ttf",
          ".woff", ".woff2", ".eot", ".svg");

  /**
   * Returns a sorted list of relative file paths for the given run's workspace. Falls back to
   * GitHub if local workspace is unavailable.
   *
   * @throws IOException on filesystem errors
   */
  public List<String> listFiles(String runId) throws IOException {
    Path root = workspaceRoot(runId);
    if (Files.isDirectory(root)) {
      return listLocalFiles(root);
    }

    // Try restoring from GCS before falling back to GitHub
    try {
      root = restoreService.ensureWorkspaceAvailable(runId);
      return listLocalFiles(root);
    } catch (Exception e) {
      log.debug("[WorkspaceFileService] GCS restore failed for run {}: {}", runId, e.getMessage());
    }

    // Fallback: serve from GitHub if repo URL exists
    return listFromGitHub(runId);
  }

  /**
   * Reads the content of a single file within the run's workspace. Falls back to GitHub if local
   * workspace is unavailable.
   *
   * @param runId the pipeline run ID
   * @param relativePath path relative to the workspace root
   * @throws SecurityException on path traversal attempts
   * @throws NoSuchFileException if the file does not exist
   * @throws IOException on read errors
   */
  public String readFile(String runId, String relativePath) throws IOException {
    if (relativePath.contains("..")) {
      throw new SecurityException("Path traversal not allowed: " + relativePath);
    }

    Path root = workspaceRoot(runId);
    if (Files.isDirectory(root)) {
      return readLocalFile(root, relativePath);
    }

    // Try restoring from GCS before falling back to GitHub
    try {
      root = restoreService.ensureWorkspaceAvailable(runId);
      return readLocalFile(root, relativePath);
    } catch (Exception e) {
      log.debug("[WorkspaceFileService] GCS restore failed for run {}: {}", runId, e.getMessage());
    }

    // Fallback: serve from GitHub if repo URL exists
    return readFromGitHub(runId, relativePath);
  }

  // ── Local filesystem operations ──────────────────────────────────────────

  private List<String> listLocalFiles(Path root) throws IOException {
    List<String> paths = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName().toString();
            if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            String name = file.getFileName().toString();
            if (!skipByExtension(name)) {
              paths.add(root.relativize(file).toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });

    paths.sort(String::compareTo);
    return paths;
  }

  private String readLocalFile(Path root, String relativePath) throws IOException {
    Path file = root.resolve(relativePath).normalize();

    if (!file.startsWith(root)) {
      throw new SecurityException("Path escapes workspace: " + relativePath);
    }
    if (!Files.exists(file) || !Files.isRegularFile(file)) {
      throw new NoSuchFileException(relativePath);
    }

    long size = Files.size(file);
    if (size > MAX_FILE_SIZE) {
      return "// File too large to display inline ("
          + (size / 1024)
          + " KB). Use Download to access the full workspace.";
    }
    return Files.readString(file);
  }

  // ── GitHub fallback operations ───────────────────────────────────────────

  private List<String> listFromGitHub(String runId) {
    PipelineRun run = stateStore.findRun(runId).orElse(null);
    if (run == null || run.getRepoUrl() == null || run.getRepoUrl().isBlank()) {
      return List.of();
    }

    String pat = resolveGitHubPat(run);
    log.info(
        "[WorkspaceFileService] Local workspace missing for run {} — listing from GitHub: {}",
        runId,
        run.getRepoUrl());
    return gitHubRepoService.listRepoFiles(run.getRepoUrl(), pat);
  }

  private String readFromGitHub(String runId, String relativePath) throws IOException {
    PipelineRun run = stateStore.findRun(runId).orElse(null);
    if (run == null || run.getRepoUrl() == null || run.getRepoUrl().isBlank()) {
      throw new NoSuchFileException(relativePath);
    }

    String pat = resolveGitHubPat(run);
    log.debug("[WorkspaceFileService] Reading {} from GitHub for run {}", relativePath, runId);
    String content = gitHubRepoService.readRepoFile(run.getRepoUrl(), relativePath, pat);

    // If the GitHub response starts with "// " it's an error message from the service
    if (content.startsWith("// File not found")
        || content.startsWith("// No GitHub PAT")
        || content.startsWith("// Invalid repo URL")) {
      throw new NoSuchFileException(relativePath);
    }
    return content;
  }

  /**
   * Resolves GitHub PAT — prefers per-run PAT, falls back to null (GitHubRepoService handles system
   * fallback).
   */
  private String resolveGitHubPat(PipelineRun run) {
    return (run.getGithubPat() != null && !run.getGithubPat().isBlank())
        ? run.getGithubPat()
        : null;
  }

  // ── File-status (V169-companion 2026-05-02) ──────────────────────────────

  /**
   * Per-file change status surfaced on the run-detail file tree so the user can see at a glance
   * what the worker has added vs modified vs left untouched. {@link Status#UNCHANGED} files
   * already existed on the cloned source and the run hasn't touched them; the rest are deltas.
   */
  public enum Status {
    /** New file the worker created (untracked + staged-add both surface as ADDED). */
    ADDED,
    /** File existed on the cloned base but the worker rewrote part of it. */
    MODIFIED,
    /** File existed on the cloned base, untouched by the worker. */
    UNCHANGED
  }

  public record FileEntry(String path, Status status) {}

  /**
   * 2026-05-02 — base + head content for a single workspace file, so the run-detail UI can render
   * a Monaco {@code DiffEditor} (only the diff is visible, not the full file). {@code base} is the
   * snapshot the run was cloned against ({@link RunRepoSnapshot#commitSha}); {@code head} is the
   * current FS content. ADDED files have empty {@code base}; deleted files have empty {@code head}
   * (rare on this pipeline). Outside any {@code repos/<name>/} subdir there is no base — the
   * worker is the only writer for those (e.g. {@code .agent-context/project.json}).
   */
  public record FileDiff(String path, String base, String head, Status status) {}

  /**
   * Read base + head content for a single file. Pulls the cloned base SHA from
   * {@link RunRepoSnapshot} (V100), then runs {@code git show <sha>:<rel>} per repo subdir. Falls
   * back to {@code git merge-base HEAD origin/HEAD} when no snapshot row exists (older runs
   * pre-V100). Best-effort: every git failure degrades to {@code base=""} so the UI still gets a
   * one-sided "added" diff instead of an opaque error.
   */
  public FileDiff readFileDiff(String runId, String relativePath) throws IOException {
    if (relativePath.contains("..")) {
      throw new SecurityException("Path traversal not allowed: " + relativePath);
    }

    Path root = workspaceRoot(runId);
    if (!Files.isDirectory(root)) {
      try {
        root = restoreService.ensureWorkspaceAvailable(runId);
      } catch (Exception e) {
        // No local checkout — diff isn't meaningful; serve head-only via the GitHub fallback.
        String head;
        try {
          head = readFromGitHub(runId, relativePath);
        } catch (NoSuchFileException nsf) {
          throw nsf;
        }
        return new FileDiff(relativePath, "", head, Status.ADDED);
      }
    }

    // Files outside any repos/<name>/ subdir — no base in any repo, treat as ADDED.
    if (!relativePath.startsWith("repos/")) {
      String head = readLocalFile(root, relativePath);
      return new FileDiff(relativePath, "", head, Status.ADDED);
    }

    String[] parts = relativePath.split("/", 3);
    if (parts.length < 3) {
      throw new NoSuchFileException(relativePath);
    }
    String repoName = parts[1];
    String repoRelativePath = parts[2];
    Path repoDir = root.resolve("repos").resolve(repoName);
    if (!Files.isDirectory(repoDir)) {
      throw new NoSuchFileException(relativePath);
    }

    // Resolve base SHA: prefer the persisted snapshot, fall back to merge-base.
    String baseSha = lookupBaseSha(runId, repoName, repoDir);

    String head;
    boolean headExists;
    try {
      head = readLocalFile(root, relativePath);
      headExists = true;
    } catch (NoSuchFileException e) {
      head = "";
      headExists = false;
    }

    String base = "";
    boolean baseExists = false;
    if (baseSha != null) {
      try {
        base = runGitShow(repoDir, baseSha, repoRelativePath);
        baseExists = base != null;
        if (!baseExists) base = "";
      } catch (Exception e) {
        log.debug("git show failed for {}#{} @ {}: {}", repoName, repoRelativePath, baseSha, e.getMessage());
      }
    }

    Status status;
    if (!baseExists && headExists) status = Status.ADDED;
    else if (baseExists && !headExists) status = Status.MODIFIED; // worker deletion — coarse
    else if (baseExists && head.equals(base)) status = Status.UNCHANGED;
    else status = Status.MODIFIED;

    return new FileDiff(relativePath, base, head, status);
  }

  /**
   * Look up the cloned base SHA for one repo subdir of a run. Tries the persisted
   * {@link RunRepoSnapshot} first (V100, set by {@code WorkspaceInitService.cloneMultipleRepos}).
   * Falls back to {@code git merge-base HEAD origin/HEAD} for runs that pre-date V100 or where
   * the snapshot row is missing for any reason. Returns {@code null} when neither path resolves.
   */
  private String lookupBaseSha(String runId, String repoName, Path repoDir) {
    try {
      List<RunRepoSnapshot> snaps = runRepoSnapshotRepository.findByRunId(runId);
      for (RunRepoSnapshot s : snaps) {
        // repo_full_name is "owner/repo"; the workspace subdir uses just "repo".
        String full = s.getRepoFullName();
        if (full == null) continue;
        String shortName = full.contains("/") ? full.substring(full.lastIndexOf('/') + 1) : full;
        if (shortName.equals(repoName)) {
          return s.getCommitSha();
        }
      }
    } catch (Exception e) {
      log.debug("snapshot lookup failed for {}/{}: {}", runId, repoName, e.getMessage());
    }
    // Fallback — origin/HEAD points at the cloned tip.
    try {
      List<String> out = runGit(repoDir, List.of("merge-base", "HEAD", "origin/HEAD"), 5);
      if (!out.isEmpty()) return out.get(0).trim();
    } catch (Exception e) {
      log.debug("merge-base fallback failed for {}: {}", repoName, e.getMessage());
    }
    return null;
  }

  /**
   * {@code git show <sha>:<path>} — returns the file content at that commit, or {@code null} when
   * the file did not exist there (so the caller can classify ADDED). Output is captured as UTF-8.
   */
  private String runGitShow(Path repoDir, String sha, String path) throws IOException, InterruptedException {
    ProcessBuilder pb =
        new ProcessBuilder("git", "show", sha + ":" + path)
            .directory(repoDir.toFile())
            .redirectErrorStream(false);
    Process p = pb.start();
    StringBuilder sb = new StringBuilder();
    try (var br =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      char[] buf = new char[4096];
      int n;
      while ((n = br.read(buf)) > 0) sb.append(buf, 0, n);
    }
    boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      throw new IOException("git show timed out");
    }
    if (p.exitValue() != 0) {
      // Most common reason: file didn't exist at that commit. Caller classifies as ADDED.
      return null;
    }
    return sb.toString();
  }

  private List<String> runGit(Path repoDir, List<String> args, int timeoutSeconds)
      throws IOException, InterruptedException {
    List<String> cmd = new ArrayList<>(args.size() + 1);
    cmd.add("git");
    cmd.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(repoDir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    List<String> lines = new ArrayList<>();
    try (var br =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) lines.add(line);
    }
    boolean done = p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      throw new IOException("git command timed out: " + cmd);
    }
    if (p.exitValue() != 0) {
      throw new IOException("git exit=" + p.exitValue() + " for " + cmd);
    }
    return lines;
  }

  /**
   * Same listing as {@link #listFiles(String)} but each entry also carries a git-derived
   * {@link Status}. Multi-repo aware: each {@code repos/<name>/} subdir is its own git checkout
   * so we run {@code git status --porcelain} per repo and union the results, prefixing every
   * relative path with {@code repos/<name>/} so they line up with what the file tree
   * already shows.
   *
   * <p>Files outside any {@code repos/<name>/} subdir (e.g. workspace-root scaffolding,
   * {@code .agent-context/}) are reported as {@link Status#ADDED} when present, since the worker
   * is the only writer for those paths.
   */
  public List<FileEntry> listFilesWithStatus(String runId) throws IOException {
    Path root = workspaceRoot(runId);
    if (!Files.isDirectory(root)) {
      // GCS / GitHub fallback — no per-file status available; return everything as UNCHANGED so
      // the UI degrades gracefully instead of showing every file as ADDED.
      return listFiles(runId).stream().map(p -> new FileEntry(p, Status.UNCHANGED)).toList();
    }

    List<String> all = listLocalFiles(root);
    java.util.Map<String, Status> statusByRepo = collectGitStatusForAllRepos(root);
    // 2026-05-02 — porcelain only shows uncommitted changes; once the worker calls gitCommit
    // every changed file flips back to UNCHANGED and the A/M chips on the file tree go blank.
    // Layer in committed-since-clone deltas via `git diff --name-status <baseSha>..HEAD` so
    // file-tree status remains accurate end-to-end.
    java.util.Map<String, Status> committedByRepo = collectCommittedDeltasForAllRepos(runId, root);

    List<FileEntry> out = new ArrayList<>(all.size());
    for (String path : all) {
      Status s = statusByRepo.get(path);
      if (s == null || s == Status.UNCHANGED) {
        s = committedByRepo.getOrDefault(path, Status.UNCHANGED);
      }
      // Files outside repos/<name>/ that aren't tracked anywhere → treat as ADDED (the
      // worker created them; e.g. .agent-context/project.json).
      if (s == Status.UNCHANGED && !path.startsWith("repos/") && !path.contains("/.git/")) {
        s = Status.ADDED;
      }
      out.add(new FileEntry(path, s));
    }
    return out;
  }

  /**
   * Per-repo {@code git diff --name-status <baseSha>..HEAD} merged into the same workspace-relative
   * map shape as {@link #collectGitStatusForAllRepos}. Picks up files that the worker added or
   * modified <i>and committed</i>, which {@code git status --porcelain} alone misses. Falls back
   * silently per repo when the base SHA can't be resolved.
   */
  private java.util.Map<String, Status> collectCommittedDeltasForAllRepos(String runId, Path workspaceRoot) {
    java.util.Map<String, Status> merged = new java.util.HashMap<>();
    Path reposDir = workspaceRoot.resolve("repos");
    if (!Files.isDirectory(reposDir)) return merged;

    try (var stream = Files.list(reposDir)) {
      stream
          .filter(Files::isDirectory)
          .forEach(
              repoDir -> {
                String repoName = repoDir.getFileName().toString();
                String baseSha = lookupBaseSha(runId, repoName, repoDir);
                if (baseSha == null) return;
                String prefix = "repos/" + repoName + "/";
                try {
                  for (var line :
                      runGit(repoDir, List.of("diff", "--name-status", baseSha + "..HEAD"), 15)) {
                    if (line.isBlank()) continue;
                    char code = line.charAt(0);
                    int tab = line.indexOf('\t');
                    if (tab < 0) continue;
                    String rest = line.substring(tab + 1).trim();
                    // Renames: "R100\told\tnew" — take the "new" path.
                    if (code == 'R' || code == 'C') {
                      int sep = rest.indexOf('\t');
                      if (sep > 0) rest = rest.substring(sep + 1).trim();
                    }
                    Status s;
                    if (code == 'A') s = Status.ADDED;
                    else if (code == 'D') continue; // deletion — head doesn't exist, skip
                    else if (code == 'R' || code == 'C') s = Status.ADDED; // rename target
                    else s = Status.MODIFIED;
                    merged.put(prefix + rest, s);
                  }
                } catch (Exception e) {
                  log.debug(
                      "git diff --name-status failed in {} ({}): {}",
                      repoDir,
                      repoName,
                      e.getMessage());
                }
              });
    } catch (IOException ignored) {
      // listing failure is non-fatal — UI gets UNCHANGED everywhere
    }
    return merged;
  }

  /**
   * Walk every {@code repos/<name>/} subdir, run {@code git status --porcelain}, and merge the
   * results into a path → status map keyed on the workspace-relative path (so the keys line up
   * with what {@link #listLocalFiles} produces). Best-effort — git failures log + the repo's
   * files fall through to UNCHANGED.
   */
  private java.util.Map<String, Status> collectGitStatusForAllRepos(Path workspaceRoot) {
    java.util.Map<String, Status> merged = new java.util.HashMap<>();
    Path reposDir = workspaceRoot.resolve("repos");
    if (!Files.isDirectory(reposDir)) return merged;

    try (var stream = Files.list(reposDir)) {
      stream
          .filter(Files::isDirectory)
          .forEach(
              repoDir -> {
                String repoName = repoDir.getFileName().toString();
                String prefix = "repos/" + repoName + "/";
                try {
                  for (var line : runGitStatusPorcelain(repoDir)) {
                    // Porcelain v1: 2-char status, space, path. e.g. " M src/foo.ts",
                    // "?? src/bar.ts", "A  src/baz.ts". Renames look like "R  old -> new".
                    if (line.length() < 3) continue;
                    String code = line.substring(0, 2);
                    String rest = line.substring(3).trim();
                    String path = rest.contains(" -> ") ? rest.substring(rest.indexOf(" -> ") + 4) : rest;
                    // Quoted paths (when special chars present) — strip surrounding quotes.
                    if (path.startsWith("\"") && path.endsWith("\"")) {
                      path = path.substring(1, path.length() - 1);
                    }
                    Status s = classifyPorcelainCode(code);
                    if (s != Status.UNCHANGED) {
                      merged.put(prefix + path, s);
                    }
                  }
                } catch (Exception e) {
                  log.debug(
                      "git status failed in {} ({}); leaving repo as UNCHANGED: {}",
                      repoDir,
                      repoName,
                      e.getMessage());
                }
              });
    } catch (IOException ignored) {
      // listing failure is non-fatal — UI gets UNCHANGED everywhere
    }
    return merged;
  }

  private List<String> runGitStatusPorcelain(Path repoDir) throws IOException, InterruptedException {
    ProcessBuilder pb =
        new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=all")
            .directory(repoDir.toFile())
            .redirectErrorStream(true);
    Process p = pb.start();
    List<String> lines = new ArrayList<>();
    try (var br =
        new java.io.BufferedReader(
            new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) lines.add(line);
    }
    boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
    if (!done) {
      p.destroyForcibly();
      throw new IOException("git status timed out");
    }
    if (p.exitValue() != 0) {
      throw new IOException("git status exit=" + p.exitValue());
    }
    return lines;
  }

  /** Map porcelain v1 2-char code → our coarse Status enum. */
  private static Status classifyPorcelainCode(String code) {
    if (code == null || code.length() < 2) return Status.UNCHANGED;
    char index = code.charAt(0);
    char wt = code.charAt(1);
    // Untracked → ADDED.
    if (index == '?' && wt == '?') return Status.ADDED;
    // Index has a new file (A_) or worktree has a new file (_A) → ADDED.
    if (index == 'A' || wt == 'A') return Status.ADDED;
    // Anything else with an M / R / D / T marker is a modification of an existing file.
    if (index == 'M' || wt == 'M' || index == 'R' || wt == 'R' || index == 'T' || wt == 'T') {
      return Status.MODIFIED;
    }
    return Status.UNCHANGED;
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  /** Per-run workspace root: {@code <agent.workspace>/<runId>/} */
  private Path workspaceRoot(String runId) {
    return Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
  }

  private boolean skipByExtension(String name) {
    for (String ext : SKIP_EXTENSIONS) {
      if (name.endsWith(ext)) return true;
    }
    return false;
  }
}
