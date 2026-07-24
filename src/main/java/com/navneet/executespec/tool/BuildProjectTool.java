package com.navneet.executespec.tool;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.programbench.ProgramBenchContainerStore;
import com.navneet.executespec.sandbox.SandboxExecResult;
import com.navneet.executespec.sandboxroute.SandboxCommandRoutingService;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;
import com.navneet.executespec.testgen.BoundedProcess;

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
  // SWE-bench tasks stash their container ID here. Same docker-exec routing as programbench; the
  // in-container workdir is stored per run because classic SWE-bench uses /testbed while
  // SWE-bench Pro uses /app. The no-op compile.sh detection wrapper does NOT apply (SWE-bench has
  // no compile.sh contract — the tests are the truth).
  private final com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers;

  /**
   * RR-281 — {@code buildProject} used to be an unrestricted shell-execution tool: the resolved
   * command (which can originate from a model-authored {@code plannerCommand} / {@code command}
   * argument) went straight to {@code /bin/sh -c} with no policy check, so anything the shell tool
   * refused could be re-submitted here. Host dispatch now validates through the same {@link
   * ShellSandboxPolicy}. Container dispatch skips it for the same reason {@link ShellTool} does —
   * the {@code --network none} cleanroom is the boundary there.
   */
  private final ShellSandboxPolicy sandboxPolicy;

  /**
   * RR-282 / RR-1674 — the resolved build command is generated repository content (npm scripts,
   * Maven/Gradle plugins, Cargo build scripts). Running it on the application host gives it the
   * service account's filesystem, credentials and network reach, which no textual policy can take
   * back. When {@link SandboxCommandRoutingService#routingEnabled()} is on, the build is dispatched
   * into the run's sandbox instead; when the sandbox cannot run it, the build is reported
   * unavailable and is NOT retried on the host. Both flags are seeded false, so the default path is
   * unchanged.
   */
  private final SandboxCommandRoutingService sandboxRouting;

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
    },
    "command": {
      "type": "string",
      "description": "Optional explicit build command. Alias for plannerCommand, used by local clients."
    }
  },
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String plannerCmd =
        args.hasNonNull("plannerCommand")
            ? args.get("plannerCommand").asText()
            : args.hasNonNull("command") ? args.get("command").asText() : null;
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
        workdir = swebenchContainers.getWorkdir(ctx.runId());
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

    // RR-281 — validate the resolved command before it reaches /bin/sh. Only the host path is
    // checked: the ProgramBench compile-check wrapper below intentionally uses subshells the
    // policy forbids, and it is only ever applied for container dispatch.
    if (containerId == null && sandboxPolicy != null) {
      ShellSandboxPolicy.ValidationResult validation =
          sandboxPolicy.validate(bc.command(), ctx.workspaceRoot().toString());
      if (!validation.permitted()) {
        log.warn(
            "buildProject blocked by sandbox command={} source={} reason={}",
            bc.command(),
            bc.sourceDescription(),
            validation.reason());
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN,
            "build command blocked by sandbox: " + validation.reason());
      }
    }

    // RR-282 / RR-1674 — sandbox dispatch, ahead of any host ProcessBuilder. Bench cleanrooms keep
    // their own container boundary and are left exactly as they were.
    if (containerId == null && sandboxRouting != null && sandboxRouting.routingEnabled()) {
      SandboxExecResult sandboxed =
          sandboxRouting.executeShell(
              ctx.runId(),
              ctx.workspaceRoot(),
              bc.command(),
              Duration.ofSeconds(bc.timeoutSeconds()));
      return switch (sandboxed.status()) {
        case COMPLETED ->
            ToolResult.ok(
                buildSummary(
                    ctx,
                    bc,
                    sandboxed.exitCode(),
                    SandboxCommandRoutingService.combinedOutput(sandboxed)));
        case TIMED_OUT ->
            ToolResult.err(
                ToolResult.ErrorKind.TIMEOUT,
                "build exceeded "
                    + bc.timeoutSeconds()
                    + "s timeout inside the sandbox: "
                    + bc.command());
          // Never a host retry: "the sandbox could not run this" is the containment working, and
          // falling back would reinstate exactly the host execution this routing exists to remove.
        case UNAVAILABLE, REFUSED ->
            ToolResult.err(
                ToolResult.ErrorKind.UNAVAILABLE,
                "build unavailable — the sandbox could not run it ("
                    + sandboxed.detail()
                    + "). It was NOT executed on the application host.");
      };
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

      BoundedProcess.Outcome outcome =
          BoundedProcess.run(pb, java.time.Duration.ofSeconds(bc.timeoutSeconds()));
      if (!outcome.finished()) {
        return ToolResult.err(
            ToolResult.ErrorKind.TIMEOUT,
            "build exceeded " + bc.timeoutSeconds() + "s timeout: " + bc.command());
      }
      String output = outcome.output();
      int exit = outcome.exitCode();
      // Return Ok regardless of exit code — the LLM/harness reads exit and decides.
      return ToolResult.ok(buildSummary(ctx, bc, exit, output));
    } catch (IOException ioe) {
      log.warn("buildProject IO error command={}", bc.command(), ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (Exception e) {
      log.warn("buildProject process error command={}", bc.command(), e);
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, e.getMessage());
    }
  }

  /**
   * The tool's success envelope. Extracted so the host and sandbox dispatch paths cannot drift: the
   * worker and the harness parse this text, and a routed build must be indistinguishable from a
   * host build in everything except where it ran.
   *
   * <p>Output budget (#6): the full build log is persisted to disk and the worker gets a preview
   * plus a {@code readFile} path, so a long compiler/Maven failure is not lost to truncation.
   */
  private String buildSummary(ExecutionContext ctx, BuildCommand bc, int exit, String output) {
    boolean noOp = "fallback:no-op".equals(bc.sourceDescription());
    return "command="
        + bc.command()
        + "\nsource="
        + bc.sourceDescription()
        + "\nsmokeSignal="
        + (noOp ? "UNAVAILABLE" : "EXECUTED")
        + "\nnoOp="
        + noOp
        + "\nexit="
        + exit
        + "\noutput:\n"
        + ToolOutput.budget(ctx, "build", output, ToolOutput.DEFAULT_INLINE_CAP);
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
}
