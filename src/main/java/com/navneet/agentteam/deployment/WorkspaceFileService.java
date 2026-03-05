package com.navneet.agentteam.deployment;

import com.navneet.agentteam.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides read-only access to the files produced by a pipeline run.
 *
 * <p>Each run has its own workspace at {@code <agent.workspace>/<runId>/},
 * so files are read directly from the filesystem — no git tags needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceFileService {

    private final AgentProperties agentProps;

    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1 MB

    private static final List<String> SKIP_DIRS = List.of(
        ".git", ".agent-context", "target", "build", "node_modules", ".gradle", ".idea", ".vscode",
        "worktrees"
    );
    private static final List<String> SKIP_EXTENSIONS = List.of(
        ".class", ".jar", ".zip", ".tar", ".gz", ".png", ".jpg", ".jpeg", ".gif", ".ico",
        ".ttf", ".woff", ".woff2", ".eot", ".svg"
    );

    /**
     * Returns a sorted list of relative file paths for the given run's workspace.
     *
     * @throws IOException on filesystem errors
     */
    public List<String> listFiles(String runId) throws IOException {
        Path root = workspaceRoot(runId);
        if (!Files.isDirectory(root)) {
            return List.of(); // run may still be initialising — return empty rather than error
        }

        List<String> paths = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
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

    /**
     * Reads the content of a single file within the run's workspace.
     *
     * @param runId        the pipeline run ID
     * @param relativePath path relative to the workspace root
     * @throws SecurityException   on path traversal attempts
     * @throws NoSuchFileException if the file does not exist
     * @throws IOException         on read errors
     */
    public String readFile(String runId, String relativePath) throws IOException {
        if (relativePath.contains("..")) {
            throw new SecurityException("Path traversal not allowed: " + relativePath);
        }

        Path root = workspaceRoot(runId);
        Path file = root.resolve(relativePath).normalize();

        if (!file.startsWith(root)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            throw new NoSuchFileException(relativePath);
        }

        long size = Files.size(file);
        if (size > MAX_FILE_SIZE) {
            return "// File too large to display inline (" + (size / 1024) +
                   " KB). Use Download to access the full workspace.";
        }
        return Files.readString(file);
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
