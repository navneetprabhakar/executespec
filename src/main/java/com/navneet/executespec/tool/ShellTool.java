package com.navneet.executespec.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShellTool {

    @Value("${agent.workspace}")
    private String workspaceRoot;

    @Value("${agent.shell.path-prepend:}")
    private String pathPrepend;

    /** Maximum execution time enforced on every command — 5 minutes. */
    private static final int MAX_TIMEOUT_SECONDS = 300;

    private final ShellSandboxPolicy sandboxPolicy;

    /**
     * Runs a shell command inside the run's isolated workspace directory.
     *
     * <p>
     * Before execution the command is validated by {@link ShellSandboxPolicy}.
     * Secret environment variables are stripped from the child process environment.
     * A hard 5-minute timeout is enforced regardless of the {@code timeoutSeconds}
     * parameter (the smaller of the two values wins).
     *
     * @param runId          identifies the per-run workspace (workspace/{runId}/)
     * @param command        shell command to execute
     * @param timeoutSeconds caller-requested timeout; capped at
     *                       {@value #MAX_TIMEOUT_SECONDS}s
     */
    @Tool(description = "Run a shell command in the workspace directory. Allowed: git, ls, cat, grep, find, echo, mkdir, cp, mv, curl. Typical timeout is 120s or 300s for builds/tests.")
    public String run(
            @ToolParam(description = "runId", required = true) String runId,
            @ToolParam(description = "Shell command to execute", required = true) String command,
            @ToolParam(description = "Timeout in seconds (e.g. 120 for normal, 300 for builds)", required = true) int timeoutSeconds) {
        // Use worktree path if one is active for this task thread; else fall back to run root.
        String worktreeOverride = WorkspaceContext.getWorkDir(runId);
        Path workspaceDir = worktreeOverride != null
                ? Path.of(worktreeOverride).toAbsolutePath().normalize()
                : Path.of(workspaceRoot, runId).toAbsolutePath().normalize();

        // 1. Sandbox validation — reject before even creating a process
        ShellSandboxPolicy.ValidationResult validation = sandboxPolicy.validate(command, workspaceDir.toString());

        if (!validation.permitted()) {
            log.warn("Shell command blocked for run {}: {}", runId, validation.reason());
            return "BLOCKED: " + validation.reason();
        }

        // 2. Enforce timeout ceiling
        int effectiveTimeout = Math.min(timeoutSeconds, MAX_TIMEOUT_SECONDS);

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workspaceDir.toFile());
            pb.redirectErrorStream(true);

            // 3. Strip secrets from child process environment
            Map<String, String> env = pb.environment();
            env.remove("GITHUB_PAT");
            env.remove("POSTGRES_PASSWORD");
            env.remove("POSTGRES_USER");
            env.remove("SPRING_AI_ANTHROPIC_API_KEY");
            env.remove("ANTHROPIC_API_KEY");
            env.remove("SPRING_AI_OPENAI_API_KEY");
            env.remove("OPENAI_API_KEY");

            env.remove("JIRA_API_TOKEN");
            env.remove("CONFLUENCE_API_TOKEN");
            env.remove("PD_API_TOKEN");
            env.remove("SNOW_PASSWORD");

            // Ensure JAVA_HOME is set so mvn/javac are discoverable in worker shells
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null && !javaHome.isBlank()) {
                env.put("JAVA_HOME", javaHome);
            }

            // Augment PATH so worker shells can find npm, node, go, python, etc.
            if (pathPrepend != null && !pathPrepend.isBlank()) {
                String current = env.getOrDefault("PATH", "/usr/bin:/bin");
                env.put("PATH", pathPrepend + ":" + current);
            }

            // 4. Execute with enforced timeout
            Process process = pb.start();
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes());

            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after " + effectiveTimeout + "s: " + command;
            }

            int exitCode = process.exitValue();
            String result = "Exit code: " + exitCode + "\n" + output;
            return result; // returning text representation instead of ToolResult

        } catch (Exception e) {
            return "Execution failed: " + e.getMessage();
        }
    }
}
