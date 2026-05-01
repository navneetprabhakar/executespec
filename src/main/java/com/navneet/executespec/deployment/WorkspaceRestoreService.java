package com.navneet.executespec.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ensures a run's workspace is available on the local filesystem.
 *
 * <p><b>Fast path:</b> if the workspace directory already exists, returns immediately.
 *
 * <p><b>Restore path:</b> if the workspace was archived to GCS (after a VM restart or normal
 * archival), downloads the zip, extracts it, and re-initialises a git repository so downstream
 * consumers (apply-suggestion, preview, chat, code viewer) can operate as normal.
 *
 * <p>Thread-safe: concurrent requests for the same runId block until the first download completes
 * (per-runId lock via {@link ConcurrentHashMap}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceRestoreService {

  private final AgentProperties agentProps;
  private final StateStore stateStore;
  private final GcsWorkspaceStore gcsStore;

  /** Per-runId lock to prevent concurrent downloads of the same workspace. */
  private final ConcurrentHashMap<String, Object> restoreLocks = new ConcurrentHashMap<>();

  /**
   * Returns the local workspace path for the given run, restoring from GCS if necessary.
   *
   * @param runId the pipeline run ID
   * @return absolute path to the workspace directory
   * @throws IllegalStateException if workspace cannot be found locally or in GCS
   */
  public Path ensureWorkspaceAvailable(String runId) {
    Path workspace = Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();

    // Fast path: already exists locally
    if (Files.isDirectory(workspace)) {
      return workspace;
    }

    // Check if GCS has it
    PipelineRun run = stateStore.findRun(runId).orElse(null);
    if (run == null || run.getGcsWorkspaceKey() == null) {
      throw new IllegalStateException(
          "Workspace not found for run "
              + runId
              + ". It may still be initialising or has been permanently cleaned up.");
    }

    // Thread-safety: per-runId lock prevents concurrent downloads
    Object lock = restoreLocks.computeIfAbsent(runId, k -> new Object());
    synchronized (lock) {
      // Double-check after acquiring lock (another thread may have restored it)
      if (Files.isDirectory(workspace)) {
        restoreLocks.remove(runId);
        return workspace;
      }

      try {
        restoreFromGcs(runId, run.getGcsWorkspaceKey(), workspace);
      } finally {
        restoreLocks.remove(runId);
      }
    }

    return workspace;
  }

  // ── Internal ──────────────────────────────────────────────────────────────

  private void restoreFromGcs(String runId, String gcsKey, Path workspace) {
    log.info("[WorkspaceRestore] Restoring workspace for run {} from GCS: {}", runId, gcsKey);

    try {
      Path zipPath = gcsStore.downloadToTemp(gcsKey, runId);
      try {
        Files.createDirectories(workspace);
        extractZip(zipPath, workspace);
        initGitRepo(workspace);
        log.info(
            "[WorkspaceRestore] Restored workspace for run {} ({} files)",
            runId,
            countFiles(workspace));
      } finally {
        Files.deleteIfExists(zipPath);
      }
    } catch (IOException e) {
      // Clean up partial extraction
      deleteDir(workspace);
      throw new RuntimeException(
          "Failed to restore workspace from GCS for run " + runId + ": " + e.getMessage(), e);
    }
  }

  private void extractZip(Path zipPath, Path targetDir) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null) {
        Path entryPath = targetDir.resolve(entry.getName()).normalize();
        // Security: prevent zip-slip attack
        if (!entryPath.startsWith(targetDir)) {
          throw new IOException("Zip entry escapes target directory: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(entryPath);
        } else {
          Files.createDirectories(entryPath.getParent());
          Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
        }
        zis.closeEntry();
      }
    }
  }

  /**
   * Re-initialises a git repository in the restored workspace. The archived zip excludes .git, so
   * we create a fresh repo with all files committed. This is required for apply-suggestion (which
   * creates branches off main).
   */
  private void initGitRepo(Path workspace) {
    try {
      runGit(workspace, "git", "init", "-b", "main");
      runGit(workspace, "git", "config", "user.email", "agent@executespec.local");
      runGit(workspace, "git", "config", "user.name", "ExecuteSpec Agent");
      runGit(workspace, "git", "add", "-A");
      runGit(workspace, "git", "commit", "--allow-empty", "-m", "restored from GCS archive");
    } catch (Exception e) {
      log.warn("[WorkspaceRestore] Git init failed (non-fatal): {}", e.getMessage());
    }
  }

  private void runGit(Path dir, String... cmd) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
    Process p = pb.start();
    p.getInputStream().readAllBytes(); // drain stdout/stderr
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IOException("Command failed (exit " + exit + "): " + String.join(" ", cmd));
    }
  }

  private long countFiles(Path dir) {
    try (var stream = Files.walk(dir)) {
      return stream.filter(Files::isRegularFile).count();
    } catch (IOException e) {
      return -1;
    }
  }

  private void deleteDir(Path dir) {
    if (!Files.exists(dir)) return;
    try (var stream = Files.walk(dir)) {
      stream
          .sorted(Comparator.reverseOrder())
          .map(Path::toFile)
          .forEach(
              f -> {
                if (!f.delete()) {
                  log.warn("[WorkspaceRestore] Could not delete: {}", f);
                }
              });
    } catch (IOException e) {
      log.warn("[WorkspaceRestore] Partial cleanup for {}: {}", dir, e.getMessage());
    }
  }
}
