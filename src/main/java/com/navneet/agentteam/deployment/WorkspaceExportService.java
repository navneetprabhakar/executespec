package com.navneet.agentteam.deployment;

import com.navneet.agentteam.config.AgentProperties;
import com.navneet.agentteam.state.StateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Creates a downloadable zip of a run's isolated workspace directory
 * ({@code <agent.workspace>/<runId>/}).
 *
 * <p>Since every run has its own folder there is no need for git tags or
 * {@code git archive} — we just zip the directory directly.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceExportService {

    private final AgentProperties agentProps;
    private final StateStore      stateStore;
    private final GcsWorkspaceStore gcsStore;

    /**
     * Excluded patterns — internal agent machinery, build artifacts,
     * and VCS metadata that don't belong in a code handoff.
     */
    private static final List<String> EXCLUDE_PATTERNS = List.of(
        ".git",
        ".agent-context",
        "target",
        "build",
        "node_modules",
        ".gradle",
        ".idea",
        ".vscode",
        "*.class",
        "*.jar"
    );

    /**
     * Builds a zip of the run's workspace and returns the path to the temp file.
     * Caller is responsible for streaming and cleanup.
     *
     * <p>If the local workspace directory is absent (run was archived to GCS), the
     * zip is downloaded from GCS and returned as a temp file — same return type,
     * transparent to the caller.
     *
     * @param runId the pipeline run ID
     * @throws IllegalStateException if the workspace is not found locally or in GCS
     * @throws IOException           on filesystem errors
     */
    public Path exportWorkspace(String runId) throws IOException {
        Path workspacePath = workspaceRoot(runId);
        if (!Files.isDirectory(workspacePath)) {
            // Workspace not on local disk — check GCS archive
            return stateStore.findRun(runId)
                .filter(run -> run.getGcsWorkspaceKey() != null)
                .map(run -> {
                    try {
                        log.info("[WorkspaceExportService] Serving run {} from GCS archive: {}",
                            runId, run.getGcsWorkspaceKey());
                        return gcsStore.downloadToTemp(run.getGcsWorkspaceKey(), runId);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to download workspace from GCS: " + e.getMessage(), e);
                    }
                })
                .orElseThrow(() -> new IllegalStateException(
                    "Workspace not found for run " + runId +
                    ". It may still be initialising or has been cleaned up."));
        }

        String zipName = "workspace-" + runId.substring(0, Math.min(8, runId.length()));
        Path   zipPath = Files.createTempFile(zipName, ".zip");

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipPath)))) {

            Files.walkFileTree(workspacePath, new SimpleFileVisitor<>() {
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

                    ZipEntry entry = new ZipEntry(relative);
                    entry.setTime(attrs.lastModifiedTime().toMillis());
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        log.info("Exported workspace for run {} → {} ({})",
            runId.substring(0, Math.min(8, runId.length())),
            zipPath,
            humanReadableSize(Files.size(zipPath)));

        return zipPath;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Per-run workspace root: {@code <agent.workspace>/<runId>/} */
    private Path workspaceRoot(String runId) {
        return Path.of(agentProps.getWorkspace(), runId).toAbsolutePath().normalize();
    }

    private boolean shouldExclude(String relativePath) {
        if (relativePath.isEmpty()) return false;
        String name = Path.of(relativePath).getFileName().toString();
        for (String pattern : EXCLUDE_PATTERNS) {
            if (pattern.startsWith("*")) {
                if (name.endsWith(pattern.substring(1))) return true;
            } else {
                if (name.equals(pattern)) return true;
            }
        }
        return false;
    }

    private String humanReadableSize(long bytes) {
        if (bytes < 1024)          return bytes + " B";
        if (bytes < 1024 * 1024)   return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
