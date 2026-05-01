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
