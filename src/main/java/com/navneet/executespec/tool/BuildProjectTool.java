package com.navneet.executespec.tool;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.programbench.ProgramBenchContainerStore;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 buildProject tool. Runs the project's detected build command and returns pass/fail + output.
 *
 * <p>This is the heart of V2's "ground truth during execution" principle. The harness calls it
 * automatically after every task merge (smoke test); the worker can also call it proactively to
 * self-verify before declaring a task complete.
 *
 * <p>Build command resolution is delegated to {@link BuildCommandResolver}. A planner- declared
 * build command (passed via {@code plannerCommand} arg) takes precedence over framework inference.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BuildProjectTool implements Tool {

  private final BuildCommandResolver resolver;
  private final MavenDependencyValidator mavenDependencyValidator;
  // Programbench tasks stash the cleanroom container ID per-runId; when set, build
  // dispatches via `docker exec` so language toolchains in the cleanroom image are used.
  private final ProgramBenchContainerStore programBenchContainers;

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
    String plannerCmd =
        args.hasNonNull("plannerCommand") ? args.get("plannerCommand").asText() : null;
    BuildCommand bc = resolver.resolve(ctx.workspaceRoot(), plannerCmd);

    // 2026-05-04 — preflight Maven dep version check. The 9-cell eval matrix
    // showed Sonnet 4.6 hallucinating a non-existent flyway-database-h2:10.10.0
    // version on the HIGH-band library spec, then the worker + reviewer burned
    // 79k tokens on the JUnit-tests task chasing downstream symptoms before
    // realising the actual error was outside the code (in the pom). Failing
    // fast with a precise "this dep doesn't exist on Maven Central" message
    // lets the worker fix the pom in 1-2 iterations instead of hunting for
    // ghost compile errors. No-op on non-Maven projects.
    MavenDependencyValidator.ValidationResult depCheck =
        mavenDependencyValidator.validate(ctx.workspaceRoot());
    if (!depCheck.isPassed()) {
      log.warn(
          "buildProject preflight: {} hallucinated/missing dep(s) in pom.xml — failing fast"
              + " before mvn invocation",
          depCheck.getMissingDeps().size());
      String msg = mavenDependencyValidator.formatError(depCheck);
      return ToolResult.ok(
          "command="
              + bc.command()
              + "\nsource=preflight (skipped mvn invocation — pom is broken)"
              + "\nexit=1\noutput:\n"
              + msg);
    }

    // Programbench branch — when this runId has a cleanroom container registered,
    // dispatch via `docker exec` so the build hits the container's toolchain.
    String containerId =
        programBenchContainers != null ? programBenchContainers.get(ctx.runId()) : null;

    try {
      ProcessBuilder pb;
      if (containerId != null) {
        pb =
            new ProcessBuilder(
                List.of(
                    "docker",
                    "exec",
                    "-w",
                    "/workspace",
                    containerId,
                    "/bin/sh",
                    "-c",
                    bc.command()));
      } else {
        pb = new ProcessBuilder(List.of("/bin/sh", "-c", bc.command()));
        pb.directory(ctx.workspaceRoot().toFile());
      }
      Map<String, String> env = pb.environment();
      String inheritedPath = env.get("PATH");
      String home = env.get("HOME");
      String javaHome = env.get("JAVA_HOME");
      env.clear();
      // Augment PATH with common toolchain locations. On macOS/Linux the JVM's
      // inherited PATH often omits Homebrew + /usr/local/bin, which made smoke
      // tests fail with exit=127 (`npm: command not found`, `mvn: not found`)
      // even though the code compiled fine outside the harness.
      String extraPath =
          "/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
      String mergedPath =
          inheritedPath == null || inheritedPath.isBlank()
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
        return ToolResult.err(
            ToolResult.ErrorKind.TIMEOUT,
            "build exceeded " + bc.timeoutSeconds() + "s timeout: " + bc.command());
      }
      String output = new String(proc.getInputStream().readAllBytes());
      int exit = proc.exitValue();
      String truncated = truncate(output);
      String summary =
          "command="
              + bc.command()
              + "\nsource="
              + bc.sourceDescription()
              + "\nexit="
              + exit
              + "\noutput:\n"
              + truncated;
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
        + "\n...["
        + omitted
        + " chars omitted from build output]...\n"
        + s.substring(s.length() - tail);
  }
}
