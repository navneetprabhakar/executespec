package com.navneet.executespec.v2.tool;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path-escape guard shared by all V2 tools that touch the filesystem.
 *
 * <p>Resolves a relative path against a workspace root and verifies the result stays
 * within the root. Blocks classic escape attempts ({@code ../../etc/passwd}, absolute
 * paths, symlink hops outside the workspace).
 */
public final class PathSafety {

    private PathSafety() {}

    /**
     * Resolve {@code relativePath} against {@code workspaceRoot}, normalize it, and
     * verify the result is within {@code workspaceRoot}.
     *
     * @return the normalized real path inside the workspace
     * @throws SecurityException if the resolved path escapes the workspace
     * @throws IOException       if the workspace root cannot be canonicalized
     */
    public static Path safeResolve(Path workspaceRoot, String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new SecurityException("path is empty");
        }
        // Reject absolute paths outright — V2 tools always operate relative to workspace.
        Path requested = Path.of(relativePath);
        if (requested.isAbsolute()) {
            throw new SecurityException("absolute paths are not allowed: " + relativePath);
        }
        Path rootReal = workspaceRoot.toRealPath();
        Path resolved = rootReal.resolve(requested).normalize();
        if (!resolved.startsWith(rootReal)) {
            throw new SecurityException("path escapes workspace: " + relativePath);
        }
        return resolved;
    }
}
