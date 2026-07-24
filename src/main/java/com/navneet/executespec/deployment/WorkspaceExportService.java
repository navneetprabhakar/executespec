package com.navneet.executespec.deployment;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.deployment.preview.DevDockerfileGenerator;
import com.navneet.executespec.deployment.preview.PreviewProjectType;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Creates a downloadable zip of a run's isolated workspace directory ({@code
 * <agent.workspace>/<runId>/}).
 *
 * <p>Since every run has its own folder there is no need for git tags or {@code git archive} — we
 * just zip the directory directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceExportService {

  private final AgentProperties agentProps;
  private final StateStore stateStore;
  private final GcsWorkspaceStore gcsStore;
  private final DevDockerfileGenerator dockerfileGen;
  private final WorkspaceRestoreService restoreService;
  private final ArtifactPublicationGuard publicationGuard;

  /**
   * Excluded patterns — internal agent machinery, build artifacts, and VCS metadata that don't
   * belong in a code handoff.
   */
  private static final List<String> EXCLUDE_PATTERNS =
      List.of(
          ".git",
          ".agent-context",
          "target",
          "build",
          "node_modules",
          "__pycache__",
          ".pytest_cache",
          ".mypy_cache",
          ".ruff_cache",
          ".tox",
          ".gradle",
          ".idea",
          ".vscode",
          "*.class",
          "*.jar",
          "*.pyc",
          "*.pyo");

  /**
   * Exact filenames that are stripped from ZIP exports for security. Prevents accidentally
   * including credentials written by agents.
   */
  private static final Set<String> SECRET_EXACT_NAMES =
      Set.of(
          ".env",
          "credentials.json",
          "service-account.json",
          "keyfile.json",
          "secrets.yml",
          "secrets.yaml");

  /**
   * RR-165 — finite ceilings on an export so a large generated repository cannot exhaust JVM heap
   * or host disk during an authenticated download. Enforced against the uncompressed bytes copied
   * into the ZIP and the number of members admitted; exceeding either fails cleanly.
   */
  static final long MAX_EXPORT_TOTAL_BYTES = 2L * 1024 * 1024 * 1024; // 2 GiB

  static final int MAX_EXPORT_FILE_COUNT = 200_000;

  /**
   * Thrown when an export exceeds {@link #MAX_EXPORT_TOTAL_BYTES} or {@link
   * #MAX_EXPORT_FILE_COUNT}.
   */
  public static final class ExportTooLargeException extends IOException {
    ExportTooLargeException(String message) {
      super(message);
    }
  }

  /**
   * Mutable running total for a single export walk. Copies each file into the ZIP under the total
   * byte / file-count ceilings (RR-165), throwing {@link ExportTooLargeException} before any
   * over-limit content is buffered or written. Limits are constructor-supplied so tests can drive
   * tiny ceilings.
   */
  static final class ExportBudget {
    private final long maxBytes;
    private final int maxFiles;
    private long totalBytes;
    private int fileCount;

    ExportBudget(long maxBytes, int maxFiles) {
      this.maxBytes = maxBytes;
      this.maxFiles = maxFiles;
    }

    void copyInto(Path file, ZipOutputStream zos) throws IOException {
      if (++fileCount > maxFiles) {
        throw new ExportTooLargeException("export exceeds " + maxFiles + " files");
      }
      byte[] chunk = new byte[8192];
      try (java.io.InputStream in = Files.newInputStream(file)) {
        int n;
        while ((n = in.read(chunk)) != -1) {
          totalBytes += n;
          if (totalBytes > maxBytes) {
            throw new ExportTooLargeException("export exceeds " + maxBytes + " bytes");
          }
          zos.write(chunk, 0, n);
        }
      }
    }
  }

  /**
   * Returns true if the given filename matches a known secret pattern. Covers: .env.* variants and
   * files ending in .pem or .key.
   */
  private static boolean isSecretFile(String name) {
    if (SECRET_EXACT_NAMES.contains(name)) return true;
    if (name.startsWith(".env.")) return true; // .env.local, .env.production, etc.
    if (name.endsWith(".pem")) return true;
    if (name.endsWith(".key")) return true;
    return false;
  }

  /**
   * Builds a zip of the run's workspace and returns the path to the temp file. Caller is
   * responsible for streaming and cleanup.
   *
   * <p>If the local workspace directory is absent (run was archived to GCS), the zip is downloaded
   * from GCS and returned as a temp file — same return type, transparent to the caller.
   *
   * @param runId the pipeline run ID
   * @throws IllegalStateException if the workspace is not found locally or in GCS
   * @throws IOException on filesystem errors
   */
  public Path exportWorkspace(String runId) throws IOException {
    Path workspacePath = workspaceRoot(runId);
    if (!Files.isDirectory(workspacePath)) {
      // Workspace not on local disk — check GCS archive
      return stateStore
          .findRun(runId)
          .filter(run -> run.getGcsWorkspaceKey() != null)
          .map(
              run -> {
                try {
                  log.info(
                      "[WorkspaceExportService] Serving run {} from GCS archive: {}",
                      runId,
                      run.getGcsWorkspaceKey());
                  Path archive = gcsStore.downloadToTemp(run.getGcsWorkspaceKey(), runId);
                  try {
                    publicationGuard.assertZipSafe(archive);
                    return archive;
                  } catch (IOException unsafe) {
                    try {
                      Files.deleteIfExists(archive);
                    } catch (IOException cleanupFailure) {
                      unsafe.addSuppressed(cleanupFailure);
                    }
                    throw new RuntimeException(unsafe.getMessage(), unsafe);
                  }
                } catch (IOException e) {
                  throw new RuntimeException(
                      "Failed to download workspace from GCS: " + e.getMessage(), e);
                }
              })
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Workspace not found for run "
                          + runId
                          + ". It may still be initialising or has been cleaned up."));
    }

    String zipName = "workspace-" + runId.substring(0, Math.min(8, runId.length()));
    Path zipPath = Files.createTempFile(zipName, ".zip");

    ExportBudget budget = new ExportBudget(MAX_EXPORT_TOTAL_BYTES, MAX_EXPORT_FILE_COUNT);
    try (ZipOutputStream zos =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {

      Files.walkFileTree(
          workspacePath,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String relative = workspacePath.relativize(dir).toString();
              if (shouldExclude(relative)) return FileVisitResult.SKIP_SUBTREE;
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              String relative = workspacePath.relativize(file).toString();
              if (shouldExclude(relative)) return FileVisitResult.CONTINUE;
              if (Files.isSymbolicLink(file)) {
                log.info("[WorkspaceExportService] Skipping symlink from ZIP: {}", relative);
                return FileVisitResult.CONTINUE;
              }
              if (isSecretFile(file.getFileName().toString())) {
                log.info("[WorkspaceExportService] Skipping secret file from ZIP: {}", relative);
                return FileVisitResult.CONTINUE;
              }
              publicationGuard.assertFileSafe(workspacePath, file);

              ZipEntry entry = new ZipEntry(relative);
              entry.setTime(attrs.lastModifiedTime().toMillis());
              zos.putNextEntry(entry);
              budget.copyInto(file, zos); // RR-165: bounded copy
              zos.closeEntry();
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // RR-165: never leave a partial/over-limit temp artifact behind.
      Files.deleteIfExists(zipPath);
      throw e;
    }

    log.info(
        "Exported workspace for run {} → {} ({})",
        runId.substring(0, Math.min(8, runId.length())),
        zipPath,
        humanReadableSize(Files.size(zipPath)));

    return zipPath;
  }

  /**
   * Export workspace as a clean repo-ready zip with .gitignore and README. Called from
   * RunController GET /api/runs/{runId}/download?format=repo
   *
   * <p>RR-165: streams into a temp file under a total-byte / file-count quota instead of
   * materializing the whole archive in JVM heap. The caller streams the returned path and is
   * responsible for cleanup (the controller registers it for deletion on exit).
   */
  public Path exportAsRepoZip(String runId) throws IOException {
    Path resolved = workspaceRoot(runId);
    if (!Files.isDirectory(resolved)) {
      // Try restoring from GCS before giving up
      resolved = restoreService.ensureWorkspaceAvailable(runId);
    }
    final Path workspacePath = resolved;

    String zipName = "repo-" + runId.substring(0, Math.min(8, runId.length()));
    Path zipPath = Files.createTempFile(zipName, ".zip");

    ExportBudget budget = new ExportBudget(MAX_EXPORT_TOTAL_BYTES, MAX_EXPORT_FILE_COUNT);
    try (ZipOutputStream zos =
        new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipPath)))) {
      // Ensure .gitignore exists
      Path gitignorePath = workspacePath.resolve(".gitignore");
      if (!Files.exists(gitignorePath)) {
        String gitignoreContent = generateGitignore(workspacePath);
        zos.putNextEntry(new ZipEntry(".gitignore"));
        zos.write(gitignoreContent.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      // Ensure README.md exists
      Path readmePath = workspacePath.resolve("README.md");
      if (!Files.exists(readmePath)) {
        PipelineRun run = stateStore.findRun(runId).orElse(null);
        String readmeContent = generateReadme(run, runId);
        zos.putNextEntry(new ZipEntry("README.md"));
        zos.write(readmeContent.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
      }

      // Ensure Dockerfile + .dockerignore exist (generated from project type detection)
      if (!Files.exists(workspacePath.resolve("Dockerfile"))) {
        try {
          PreviewProjectType type = dockerfileGen.detect(workspacePath);
          String dockerfile = dockerfileGen.generate(type, workspacePath);
          zos.putNextEntry(new ZipEntry("Dockerfile"));
          zos.write(dockerfile.getBytes(StandardCharsets.UTF_8));
          zos.closeEntry();

          zos.putNextEntry(new ZipEntry(".dockerignore"));
          zos.write(dockerfileGen.dockerIgnore().getBytes(StandardCharsets.UTF_8));
          zos.closeEntry();

          log.info("Generated Dockerfile for repo export (type={})", type);
        } catch (Exception e) {
          log.debug("Skipping Dockerfile generation for run {}: {}", runId, e.getMessage());
        }
      }

      // Walk workspace, skip .git directory and node_modules
      Files.walkFileTree(
          workspacePath,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              String relative = workspacePath.relativize(dir).toString();
              if (shouldExclude(relative)) return FileVisitResult.SKIP_SUBTREE;
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              String relative = workspacePath.relativize(file).toString();
              if (shouldExclude(relative)) return FileVisitResult.CONTINUE;
              if (Files.isSymbolicLink(file)) {
                log.info("[WorkspaceExportService] Skipping symlink from repo ZIP: {}", relative);
                return FileVisitResult.CONTINUE;
              }
              if (isSecretFile(file.getFileName().toString())) {
                log.info(
                    "[WorkspaceExportService] Skipping secret file from repo ZIP: {}", relative);
                return FileVisitResult.CONTINUE;
              }
              publicationGuard.assertFileSafe(workspacePath, file);

              zos.putNextEntry(new ZipEntry(relative));
              budget.copyInto(file, zos); // RR-165: bounded copy
              zos.closeEntry();
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      // RR-165: never leave a partial/over-limit temp artifact behind.
      Files.deleteIfExists(zipPath);
      throw e;
    }

    log.info(
        "Exported repo zip for run {} ({})",
        runId.substring(0, Math.min(8, runId.length())),
        humanReadableSize(Files.size(zipPath)));
    return zipPath;
  }

  private String generateGitignore(Path workspace) {
    StringBuilder sb = new StringBuilder();
    sb.append("# Generated by ExecuteSpec\n\n");
    if (Files.exists(workspace.resolve("pom.xml"))) {
      sb.append("target/\n*.class\n*.jar\n.idea/\n*.iml\n");
    }
    if (Files.exists(workspace.resolve("package.json"))) {
      sb.append("node_modules/\ndist/\n.next/\n");
    }
    if (Files.exists(workspace.resolve("requirements.txt"))
        || Files.exists(workspace.resolve("pyproject.toml"))) {
      sb.append("__pycache__/\n*.pyc\nvenv/\n.venv/\n");
    }
    sb.append("\n.env\n.DS_Store\n.agent-context/\n");
    return sb.toString();
  }

  private String generateReadme(PipelineRun run, String runId) {
    String shortId = runId.substring(0, Math.min(8, runId.length()));
    String goal = run != null && run.getUserRequest() != null ? run.getUserRequest() : "N/A";
    return """
           # %s

           Generated by [ExecuteSpec](https://executespec.ai)

           ## Goal

           %s

           ## Getting Started

           See generated code files for implementation details.

           ---
           *Generated on %s*
           """
        .formatted(shortId, goal, java.time.LocalDate.now());
  }

  /** Per-run workspace root: {@code <agent.workspace>/<runId>/} */
  private Path workspaceRoot(String runId) {
    return Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
  }

  static boolean shouldExclude(String relativePath) {
    if (relativePath.isEmpty()) return false;
    Path relative = Path.of(relativePath);
    String name = relative.getFileName().toString();
    for (String pattern : EXCLUDE_PATTERNS) {
      if (pattern.startsWith("*")) {
        if (name.endsWith(pattern.substring(1))) return true;
      } else {
        for (Path segment : relative) {
          if (segment.toString().equals(pattern)) return true;
        }
      }
    }
    return false;
  }

  private String humanReadableSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
    return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
  }
}
