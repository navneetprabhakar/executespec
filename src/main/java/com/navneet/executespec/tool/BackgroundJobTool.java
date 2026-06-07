package com.navneet.executespec.tool;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * W4.2 (P6, vix-leverage) — provider-agnostic worker tool for long-running background shell jobs.
 * {@code action=start} launches a command via {@link JobRegistry} and returns a {@code jobId}
 * immediately; {@code action=status|logs|kill} (with {@code jobId}) control it. Lets a worker run a
 * server / long build while doing other work, instead of blocking a foreground {@code shell} call
 * (capped at {@link ShellTool#MAX_TIMEOUT_SEC}s).
 *
 * <p><b>Flag-gated out of the schema.</b> Gated by {@link #FLAG} (V233, default OFF). When off,
 * {@link ToolRegistry#bindForWorker} / {@link ToolRegistry#bind} <b>do not bind it at all</b> (it's
 * absent from the model's tool schema), per the RL-2026-05-061 lesson that a new tool merely
 * <i>present</i> in the schema can cause reasoning-only stalls on schema-sensitive providers
 * (DeepSeek). The in-{@code execute} flag check is belt-and-suspenders.
 *
 * <p>Start commands are sandbox-validated by {@link ShellSandboxPolicy} exactly like {@link
 * ShellTool} (host-side execution; container/remote routing is the documented follow-up).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BackgroundJobTool implements Tool {

  public static final String FLAG = "worker.background-jobs.enabled"; // V233, default OFF

  private final FeatureFlagService flags;
  private final JobRegistry jobs;
  private final ShellSandboxPolicy sandboxPolicy;

  /**
   * Whether this tool is bound into the worker/agent schema (consulted by {@link ToolRegistry}).
   */
  public boolean enabled() {
    return flags.isEnabled(FLAG);
  }

  @Override
  public String name() {
    return "backgroundJob";
  }

  @Override
  public String description() {
    return "Manage a long-running background shell job (a server, a slow build/test) without"
        + " blocking. action=start (with command) launches it and returns a jobId"
        + " immediately; action=status/logs/kill (with jobId) poll or stop it. Use this"
        + " instead of `shell` when a command must keep running while you do other work;"
        + " `shell` is for commands that finish within its timeout.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "action": {
                 "type": "string",
                 "enum": ["start", "status", "logs", "kill"],
                 "description": "start a job, or status/logs/kill an existing one"
               },
               "command": {
                 "type": "string",
                 "description": "Shell command to run (required for action=start)"
               },
               "jobId": {
                 "type": "string",
                 "description": "Job id (required for action=status/logs/kill)"
               }
             },
             "required": ["action"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    if (!flags.isEnabled(FLAG)) {
      return ToolResult.err(
          ToolResult.ErrorKind.FORBIDDEN, "background jobs are not enabled for this run");
    }
    String action = args.path("action").asText("");
    try {
      return switch (action) {
        case "start" -> start(args, ctx);
        case "status" -> withJobId(args, jobs::status);
        case "logs" -> withJobId(args, id -> jobs.tail(id, JobRegistry.DEFAULT_TAIL_CHARS));
        case "kill" -> withJobId(args, jobs::kill);
        default ->
            ToolResult.err(
                ToolResult.ErrorKind.INVALID_ARGS,
                "unknown action: '" + action + "' (use start|status|logs|kill)");
      };
    } catch (IOException e) {
      log.warn("backgroundJob IO error action={} run={}", action, ctx.runId(), e);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, e.getMessage());
    }
  }

  private ToolResult start(JsonNode args, ExecutionContext ctx) throws IOException {
    String command = args.path("command").asText(null);
    if (command == null || command.isBlank()) {
      return ToolResult.err(
          ToolResult.ErrorKind.INVALID_ARGS, "command is required for action=start");
    }
    ShellSandboxPolicy.ValidationResult v =
        sandboxPolicy.validate(command, ctx.workspaceRoot().toString());
    if (!v.permitted()) {
      return ToolResult.err(
          ToolResult.ErrorKind.FORBIDDEN, "command blocked by sandbox: " + v.reason());
    }
    JobRegistry.Job job = jobs.start(ctx, command);
    return ToolResult.ok(
        "started jobId="
            + job.jobId()
            + " (pid="
            + job.process().pid()
            + ") — poll with action=status or action=logs jobId="
            + job.jobId());
  }

  private interface JobOp {
    String apply(String jobId);
  }

  private static ToolResult withJobId(JsonNode args, JobOp op) {
    String jobId = args.path("jobId").asText(null);
    if (jobId == null || jobId.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "jobId is required for this action");
    }
    return ToolResult.ok(op.apply(jobId));
  }
}
