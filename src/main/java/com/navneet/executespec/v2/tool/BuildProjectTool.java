package com.navneet.executespec.v2.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.v2.smoketest.BuildCommand;
import com.navneet.executespec.v2.smoketest.BuildCommandResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * V2 buildProject tool. Runs the project's detected build command and returns
 * pass/fail + output.
 *
 * <p>This is the heart of V2's "ground truth during execution" principle. The harness
 * calls it automatically after every task merge (smoke test); the worker can also call
 * it proactively to self-verify before declaring a task complete.
 *
 * <p>Build command resolution is delegated to {@link BuildCommandResolver}. A planner-
 * declared build command (passed via {@code plannerCommand} arg) takes precedence over
 * framework inference.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BuildProjectTool implements Tool {

    private final BuildCommandResolver resolver;

    @Override
    public String name() {
        return "buildProject";
    }

    @Override
    public String description() {
        return "Runs the project's build command (detected from project markers like package.json, "
                + "pom.xml, Cargo.toml, or planner-declared) and returns pass/fail + output. Use to "
                + "verify your changes compile before declaring the task complete.";
    }

    @Override
    public String inputSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "plannerCommand": {
                      "type": "string",
                      "description": "Optional explicit build command (overrides framework inference)"
                    }
                  },
                  "additionalProperties": false
                }
                """;
    }

    @Override
    public ToolResult execute(JsonNode args, ExecutionContext ctx) {
        String plannerCmd = args.hasNonNull("plannerCommand") ? args.get("plannerCommand").asText() : null;
        BuildCommand bc = resolver.resolve(ctx.workspaceRoot(), plannerCmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of("/bin/sh", "-c", bc.command()));
            pb.directory(ctx.workspaceRoot().toFile());
            Map<String, String> env = pb.environment();
            String inheritedPath = env.get("PATH");
            String home = env.get("HOME");
            String javaHome = env.get("JAVA_HOME");
            env.clear();
            // Augment PATH with common toolchain locations. On macOS/Linux the JVM's
            // inherited PATH often omits Homebrew + /usr/local/bin, which made smoke
            // tests fail with exit=127 (`npm: command not found`, `mvn: not found`)
            // even though the code compiled fine outside the harness.
            String extraPath = "/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
            String mergedPath = inheritedPath == null || inheritedPath.isBlank()
                    ? extraPath
                    : inheritedPath + ":" + extraPath;
            env.put("PATH", mergedPath);
            if (home != null) env.put("HOME", home);
            if (javaHome != null) env.put("JAVA_HOME", javaHome);
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            boolean finished = proc.waitFor(bc.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return ToolResult.err(ToolResult.ErrorKind.TIMEOUT,
                        "build exceeded " + bc.timeoutSeconds() + "s timeout: " + bc.command());
            }
            String output = new String(proc.getInputStream().readAllBytes());
            int exit = proc.exitValue();
            String truncated = truncate(output);
            String summary = "command=" + bc.command()
                    + "\nsource=" + bc.sourceDescription()
                    + "\nexit=" + exit
                    + "\noutput:\n" + truncated;
            // Return Ok regardless of exit code — the LLM/harness reads exit and decides.
            return ToolResult.ok(summary);
        } catch (IOException ioe) {
            log.warn("buildProject IO error command={}", bc.command(), ioe);
            return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
        }
    }

    private static String truncate(String s) {
        int max = 4_000;
        if (s.length() <= max) return s;
        int tail = 1_000;
        int head = max - tail;
        int omitted = s.length() - max;
        return s.substring(0, head)
                + "\n...[" + omitted + " chars omitted from build output]...\n"
                + s.substring(s.length() - tail);
    }
}
