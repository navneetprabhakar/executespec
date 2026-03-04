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

    /**
     * Clones an existing GitHub repository into the run workspace.
     * Used when {@code repoMode=EXISTING} so the workspace starts from real code.
     *
     * <p>The PAT is injected into the HTTPS URL as {@code oauth2:{pat}@} so no
     * credential helper configuration is required.
     *
     * <p>Idempotent: no-ops if {@code .git/} already exists in the workspace.
     *
     * @param runId   the pipeline run ID
     * @param repoUrl HTTPS clone URL, e.g. {@code https://github.com/owner/repo}
     * @param pat     personal access token (may be null for public repos)
     */
    public void cloneFromRepo(String runId, String repoUrl, String pat) throws Exception {
        Path workspace = runWorkspacePath(runId);
        if (Files.isDirectory(workspace.resolve(".git"))) {
            log.debug("Workspace for run {} already cloned: {}", runId, workspace);
            return;
        }
        Files.createDirectories(workspace.getParent());

        // Inject PAT into HTTPS URL for authentication
        String authUrl = (pat != null && !pat.isBlank())
                ? repoUrl.replace("https://", "https://oauth2:" + pat + "@")
                : repoUrl;

        log.info("Cloning {} into workspace for run {}", repoUrl, runId);
        // git clone <authUrl> <workspace-path>  — creates the directory
        runGitStrict(workspace.getParent(), "git", "clone", authUrl, workspace.toString());

        // Override commit identity for agent commits
        runGit(workspace, "git", "config", "user.email", "agent@executespec.local");
        runGit(workspace, "git", "config", "user.name",  "ExecuteSpec Agent");
        log.info("Cloned workspace ready for run {}", runId);
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

    /** Like {@link #runGit} but throws a RuntimeException on non-zero exit code (for clone). */
    private void runGitStrict(Path dir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p   = pb.start();
        String  out = new String(p.getInputStream().readAllBytes());
        int     exit = p.waitFor();
        if (exit != 0) {
            // Mask any embedded PAT in the logged command before throwing
            String cmdStr = String.join(" ", cmd).replaceAll("oauth2:[^@]+@", "oauth2:***@");
            throw new RuntimeException("git command [" + cmdStr + "] exited with " + exit + ": " + out);
        }
    }
}
