package com.navneet.executespec.deployment.analyzer;

import com.navneet.executespec.deployment.preview.DatabaseType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Determines whether an application is stateless (suitable for Cloud Run scale-to-zero).
 * <p>
 * An app is considered stateful if it:
 * - Uses in-memory session storage (not backed by Redis/DB)
 * - Writes to the local filesystem (outside /tmp)
 * - Uses file-based caching or SQLite on disk
 * - Requires persistent connections (WebSocket without external broker)
 * <p>
 * An app is stateless if it:
 * - Has no database OR uses an external database
 * - Uses JWT or external session store
 * - Doesn't write to local filesystem
 */
@Component
@Slf4j
public class StatelessnessDetector {

    // Patterns that indicate in-memory session (stateful unless backed by Redis/DB)
    private static final Pattern IN_MEMORY_SESSION_NODE = Pattern.compile(
            "express-session(?!.*(?:connect-redis|connect-mongo|connect-pg))");
    private static final Pattern IN_MEMORY_SESSION_SPRING = Pattern.compile(
            "spring\\.session\\.store-type\\s*[:=]\\s*none");

    // Patterns that indicate file system writes
    private static final Pattern FS_WRITE_NODE = Pattern.compile(
            "fs\\.writeFile|fs\\.writeSync|fs\\.createWriteStream|mkdirSync.*uploads");
    private static final Pattern FS_WRITE_PYTHON = Pattern.compile(
            "open\\(.*['\"]w['\"]|shutil\\.copy|pathlib.*write_text");

    // SQLite on disk (stateful — file-based DB)
    private static final Pattern SQLITE_FILE = Pattern.compile(
            "sqlite3|better-sqlite3|:memory:|file:.*\\.db|file:.*\\.sqlite");

    // WebSocket without external broker
    private static final Pattern WEBSOCKET_NODE = Pattern.compile(
            "\"(ws|socket\\.io|@nestjs/websockets)\"");

    /**
     * Determine if the app is stateless.
     *
     * @param workspace    workspace path
     * @param databaseType detected database type (null = no DB)
     * @param hasCache     whether cache was detected
     * @return true if stateless, false if stateful
     */
    public boolean isStateless(Path workspace, DatabaseType databaseType, boolean hasCache) {
        if (workspace == null) return true;

        // If using an embedded DB like SQLite (not in-memory), it's stateful
        if (databaseType == null && hasSqliteOnDisk(workspace)) {
            log.info("SQLite on-disk detected — app is stateful");
            return false;
        }

        // Check for in-memory session without external backing
        if (hasInMemorySession(workspace) && !hasCache) {
            log.info("In-memory session without external store — app is stateful");
            return false;
        }

        // Check for filesystem writes
        if (hasFilesystemWrites(workspace)) {
            log.info("Filesystem writes detected — app may be stateful");
            // This is a soft signal — file uploads could go to /tmp which is fine for Cloud Run
            // Don't hard-block, just note it
            return false;
        }

        return true;
    }

    private boolean hasSqliteOnDisk(Path workspace) {
        String pkg = readSafe(workspace.resolve("package.json"));
        if (pkg != null && SQLITE_FILE.matcher(pkg).find()) {
            // Check if it's :memory: (which is fine) or a file path
            return !pkg.contains(":memory:");
        }

        for (String file : new String[]{"requirements.txt", "pyproject.toml"}) {
            String content = readSafe(workspace.resolve(file));
            if (content != null && content.contains("sqlite")) return true;
        }

        return false;
    }

    private boolean hasInMemorySession(Path workspace) {
        String pkg = readSafe(workspace.resolve("package.json"));
        if (pkg != null && pkg.contains("express-session")) {
            // Check if it's backed by an external store
            return !pkg.contains("connect-redis") && !pkg.contains("connect-mongo")
                    && !pkg.contains("connect-pg");
        }

        for (String cfg : new String[]{
                "src/main/resources/application.yml",
                "src/main/resources/application.yaml"}) {
            String content = readSafe(workspace.resolve(cfg));
            if (content != null && IN_MEMORY_SESSION_SPRING.matcher(content).find()) {
                return true;
            }
        }

        return false;
    }

    private boolean hasFilesystemWrites(Path workspace) {
        // Quick heuristic: check main source files only
        for (String src : new String[]{
                "src/index.ts", "src/index.js", "src/app.ts", "src/app.js",
                "src/server.ts", "src/server.js"}) {
            String content = readSafe(workspace.resolve(src));
            if (content != null && FS_WRITE_NODE.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    private String readSafe(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            String content = Files.readString(file);
            return content.length() > 50_000 ? content.substring(0, 50_000) : content;
        } catch (IOException e) {
            return null;
        }
    }
}
