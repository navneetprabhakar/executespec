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

    List<FileEntry> out = new ArrayList<>(all.size());
    for (String path : all) {
      Status s = statusByRepo.getOrDefault(path, Status.UNCHANGED);
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
