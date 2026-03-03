package com.navneet.agentteam.harness;

import com.navneet.agentteam.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;

/**
 * Manages per-run workspace lifecycle.
 *
 * <p>Each pipeline run gets its own isolated directory:
 * {@code <agent.workspace>/<runId>/}
 *
 * <p>This directory contains a fresh git repo so runs can never contaminate
 * each other's code, history, or branches.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkspaceInitService {

    private final AgentProperties props;

    /**
     * Creates and initialises a fresh, isolated workspace for {@code runId}.
     *
     * <ul>
     *   <li>Creates {@code <workspace>/<runId>/} if it does not exist.</li>
     *   <li>Runs {@code git init -b main} inside it.</li>
     *   <li>Writes a sensible {@code .gitignore} and makes an initial commit so
     *       workers can branch off {@code main} immediately.</li>
     * </ul>
     *
     * <p>Idempotent: if the directory and git repo already exist this method
     * returns quickly without modifying anything.
     *
     * @param runId the pipeline run ID (full UUID)
     */
    public void ensureRunWorkspace(String runId) {
        Path workspace = runWorkspacePath(runId);
        try {
            if (Files.isDirectory(workspace.resolve(".git"))) {
                // Already initialised — nothing to do
                log.debug("Workspace for run {} already exists: {}", runId, workspace);
                return;
            }

            Files.createDirectories(workspace);
            log.info("Created workspace for run {}: {}", runId, workspace);

            runGit(workspace, "git", "init", "-b", "main");

            // Configure identity so JGit commits work in all environments
            runGit(workspace, "git", "config", "user.email", "agent@executespec.local");
            runGit(workspace, "git", "config", "user.name", "ExecuteSpec Agent");

            // Standard build-artifact exclusions
            Files.writeString(workspace.resolve(".gitignore"),
                "target/\nbuild/\nnode_modules/\n.gradle/\n__pycache__/\n*.class\n*.pyc\n*.log\n");

            runGit(workspace, "git", "add", ".gitignore");
            runGit(workspace, "git", "commit", "--allow-empty", "-m", "init agent workspace");

            log.info("Workspace initialised for run {}", runId);

        } catch (Exception e) {
            log.error("Failed to initialise workspace for run {}: {}", runId, e.getMessage(), e);
            throw new RuntimeException("Workspace init failed for run " + runId + ": " + e.getMessage(), e);
        }
    }

    /** Returns the absolute, normalised path to the per-run workspace directory. */
    public Path runWorkspacePath(String runId) {
        return Path.of(props.getWorkspace(), runId).toAbsolutePath().normalize();
    }

    private void runGit(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p   = pb.start();
        String  out = new String(p.getInputStream().readAllBytes());
        int     exit = p.waitFor();
        if (exit != 0) {
            log.debug("git command [{}] exit {}: {}", String.join(" ", cmd), exit, out);
        }
    }
}
