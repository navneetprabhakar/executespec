package com.navneet.executespec.tool;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.programbench.ProgramBenchContainerStore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 shell tool. Executes a sandboxed shell command in the run's workspace.
 *
 * <p>Reuses V1's {@link ShellSandboxPolicy} for command validation (blocked commands,
 * subshell/backtick prevention, allowed-prefix enforcement). The sandbox's invariants are unchanged
 * in V2 — only the dispatch surface differs.
 *
 * <p>Environment is sanitized to a whitelist ({@code PATH}, {@code HOME}, {@code DOCKER_HOST}) —
 * matches V1 behavior to avoid leaking secrets to subprocesses.
 *
 * <p>Output is budgeted (#6): small output returns inline; larger output is persisted in full to
 * {@code .agent-context/tool-output/} and the worker gets a head+tail preview plus a readFile path
 * for the complete text (see {@link ToolOutput}).
 */
@Component
@Slf4j
public class ShellTool implements Tool {

  static final int DEFAULT_TIMEOUT_SEC = 60;
  static final int MAX_TIMEOUT_SEC = 300;

  private final ShellSandboxPolicy sandboxPolicy;
  // Programbench tasks stash their cleanroom container ID per-runId here. When set,
  // shell commands route via `docker exec <cid> sh -c '...'` instead of host shell.
  // For normal runs, this is always null and behavior is unchanged.
  private final ProgramBenchContainerStore programBenchContainers;

  // SWE-bench tasks stash their container ID here. Same routing semantics as programbench; the
  // in-container workdir is stored per run because classic SWE-bench uses /testbed while
  // SWE-bench Pro uses /app.
  private final com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers;

  @Autowired
  public ShellTool(
      ShellSandboxPolicy sandboxPolicy,
      ProgramBenchContainerStore programBenchContainers,
      com.navneet.executespec.swebench.SwebenchContainerStore swebenchContainers) {
    this.sandboxPolicy = sandboxPolicy;
    this.programBenchContainers = programBenchContainers;
    this.swebenchContainers = swebenchContainers;
  }

  @Override
  public String name() {
    return "shell";
  }

  @Override
  public String description() {
    return "Executes a sandboxed shell command in the run's workspace. Use for build commands, test"
        + " runs, package installs, and running your reproduction script. Subshells ($(...))"
        + " and backticks are blocked. Large output is saved to a file — you'll get a"
        + " preview plus a path you can readFile for the complete output (e.g. a full"
        + " failure trace).";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "command": {
                 "type": "string",
                 "description": "Shell command to execute"
               },
               "timeoutSeconds": {
                 "type": "integer",
                 "description": "Optional timeout (default 60, max 300)",
                 "minimum": 1,
                 "maximum": 300
               }
             },
             "required": ["command"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String command = args.path("command").asText(null);
    if (command == null || command.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "command is required");
    }
    int timeout =
        args.hasNonNull("timeoutSeconds")
            ? Math.min(args.get("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SEC), MAX_TIMEOUT_SEC)
            : DEFAULT_TIMEOUT_SEC;

    // Resolve the cleanroom container FIRST (was done after the sandbox check — see below for why
    // the order matters). When this runId has one (SWE-bench / ProgramBench), the command
    // dispatches via `docker exec` into a `--network none`, ephemeral, workspace-only container —
    // the container itself IS the security boundary.
    String containerId = null;
    String workdir = null;
    if (swebenchContainers != null) {
      String swCid = swebenchContainers.get(ctx.runId());
      if (swCid != null) {
        containerId = swCid;
        workdir = swebenchContainers.getWorkdir(ctx.runId());
      }
    }
    if (containerId == null && programBenchContainers != null) {
      String pbCid = programBenchContainers.get(ctx.runId());
      if (pbCid != null) {
        containerId = pbCid;
        workdir = "/workspace";
      }
    }

    // The host-oriented ShellSandboxPolicy (blocks `cd <abs>`, heredocs, env-prefixes, chained
    // verbs not in its allowlist) is correct for HOST execution (product runs), but is both
    // unnecessary and actively harmful for IN-CONTAINER dispatch: it blocks the worker's own
    // verification (writing + running a repro, running pytest, `cd /testbed && …`), forcing it to
    // edit blind. Inside a `--network none`, ephemeral, workspace-only-mount container a command
    // can neither exfiltrate nor damage the host, so we skip the host policy there and let the
    // container be the sandbox. Host execution keeps the full policy.
    if (containerId == null) {
      String workspacePath = ctx.workspaceRoot().toString();
      ShellSandboxPolicy.ValidationResult validation =
          sandboxPolicy.validate(command, workspacePath);
      if (!validation.permitted()) {
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN, "command blocked by sandbox: " + validation.reason());
      }
    }

    try {
      ProcessBuilder pb;
      if (containerId != null) {
        // Container has --network none + bind-mounted workspace; cd to its workdir on entry.
        pb =
            new ProcessBuilder(
                List.of("docker", "exec", "-w", workdir, containerId, "/bin/sh", "-c", command));
        // pb.directory() left as inherited — docker exec doesn't care about the
        // host-side cwd; the -w flag controls the in-container cwd.
      } else {
        pb = new ProcessBuilder(List.of("/bin/sh", "-c", command));
        pb.directory(ctx.workspaceRoot().toFile());
      }
      // Sanitize env — whitelist only safe vars (matches V1 behavior).
      Map<String, String> env = pb.environment();
      String path = env.get("PATH");
      String home = env.get("HOME");
      String dockerHost = env.get("DOCKER_HOST");
      env.clear();
      if (path != null) env.put("PATH", path);
      if (home != null) env.put("HOME", home);
      if (dockerHost != null) env.put("DOCKER_HOST", dockerHost);
      pb.redirectErrorStream(true);

      Process proc = pb.start();
      boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
      if (!finished) {
        proc.destroyForcibly();
        return ToolResult.err(
            ToolResult.ErrorKind.TIMEOUT, "command exceeded " + timeout + "s timeout: " + command);
      }
      String output = new String(proc.getInputStream().readAllBytes());
      int exit = proc.exitValue();
      // Output budget (#6): large output is persisted to .agent-context/tool-output and the worker
      // gets a preview + a readFile path, so it can see a FULL failure trace instead of a 4 KB
      // fragment (the inline preview alone could not show why a test failed).
      String result =
          "exit="
              + exit
              + "\n"
              + ToolOutput.budget(ctx, "shell", output, ToolOutput.DEFAULT_INLINE_CAP);
      // Non-zero exits are still Ok results — the LLM needs to see stderr to react.
      return ToolResult.ok(result);
    } catch (IOException ioe) {
      log.warn("shell IO error command={}", command, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }
}
