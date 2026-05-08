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
  // SWE-bench tasks stash their container ID here. Same docker-exec routing as programbench but
  // the in-container working directory is /testbed rather than /workspace, AND the no-op
  // compile.sh detection wrapper does NOT apply (SWE-bench has no compile.sh contract — the
  // tests are the truth).
  private final com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers;

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

    // Bench-mode branch — resolve which adapter (if any) registered a container for this run.
    // Programbench: workdir=/workspace, applies the compile.sh hash-check wrapper.
    // SWE-bench:    workdir=/testbed,    NO compile.sh wrapper (tests are truth, not compile).
    String containerId = null;
    String workdir = null;
    boolean applyCompileCheckWrapper = false;
    if (swebenchContainers != null) {
      String swCid = swebenchContainers.get(ctx.runId());
      if (swCid != null) {
        containerId = swCid;
        workdir = com.navneet.executespec.swebench.SwebenchContainerStore.WORKDIR;
        applyCompileCheckWrapper = false;
      }
    }
    if (containerId == null && programBenchContainers != null) {
      String pbCid = programBenchContainers.get(ctx.runId());
      if (pbCid != null) {
        containerId = pbCid;
        workdir = "/workspace";
        applyCompileCheckWrapper = true; // see Bug G — programbench-only no-op compile.sh guard
      }
    }

    String effectiveCmd =
        applyCompileCheckWrapper ? wrapForExecutableCompileCheck(bc.command()) : bc.command();

    try {
      ProcessBuilder pb;
      if (containerId != null) {
        pb =
            new ProcessBuilder(
                List.of(
                    "docker", "exec", "-w", workdir, containerId, "/bin/sh", "-c", effectiveCmd));
      } else {
        pb = new ProcessBuilder(List.of("/bin/sh", "-c", effectiveCmd));
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

  /**
   * Wrap the build command with a "did compile.sh actually compile" guard. Captures the executable
   * hash before, runs the command, captures the hash after; fails the build if executable is
   * missing post-run OR if the hash is unchanged (= no new compilation happened, agent is gaming
   * the smoke against the cleanroom's pre-existing binary).
   *
   * <p>Implementation note: built as a single shell pipeline so the original command's exit code is
   * preserved (passing through unless one of our guards fires). The {@code stash-restore-trap}
   * gives the agent's mid-task buildProject calls a chance to recover — if compile.sh fails to
   * produce a binary, the original is restored so the next iteration can still run {@code
   * ./executable --help} for behavior inspection.
   *
   * <p>Visible-for-test so the wrapping logic can be pinned by unit tests without standing up a
   * full docker container.
   */
  static String wrapForExecutableCompileCheck(String userCommand) {
    return "ORIG_HASH=$(sha256sum ./executable 2>/dev/null | awk '{print $1}'); "
        + "USER_RC=0; { "
        + userCommand
        + "; } || USER_RC=$?; "
        + "if [ ! -f ./executable ]; then "
        + "  echo '[programbench] BUILD FAIL: compile.sh did not produce ./executable. Your "
        + "compile.sh must compile your source files into a fresh /workspace/executable.'; "
        + "  exit 1; "
        + "fi; "
        + "NEW_HASH=$(sha256sum ./executable 2>/dev/null | awk '{print $1}'); "
        + "if [ -n \"$ORIG_HASH\" ] && [ \"$NEW_HASH\" = \"$ORIG_HASH\" ]; then "
        + "  echo '[programbench] BUILD FAIL: compile.sh did not produce a NEW ./executable "
        + "(hash unchanged). compile.sh must compile your source code into a fresh executable, "
        + "not just verify the existing one.'; "
        + "  exit 1; "
        + "fi; "
        + "exit $USER_RC";
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
