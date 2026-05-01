package com.navneet.executespec.v2.tool;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 gitCommit tool. Stages all changes and commits with the supplied message.
 *
 * <p>Sequential V2 has no per-task branch — all commits land on whatever branch is currently
 * checked out (the run's main workspace branch).
 */
@Component
@Slf4j
public class GitCommitTool implements Tool {

  @Override
  public String name() {
    return "gitCommit";
  }

  @Override
  public String description() {
    return "Stages all working-tree changes (git add -A) and creates a commit with the given"
        + " message.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "message": {
                 "type": "string",
                 "description": "Commit message",
                 "minLength": 1
               }
             },
             "required": ["message"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String message = args.path("message").asText(null);
    if (message == null || message.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "message is required");
    }
    try {
      GitInvoker.GitResult addRes = GitInvoker.run(ctx.workspaceRoot(), "add", "-A");
      if (addRes.exitCode() != 0) {
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR,
            "git add failed (exit=" + addRes.exitCode() + "): " + addRes.output());
      }
      GitInvoker.GitResult commitRes =
          GitInvoker.run(ctx.workspaceRoot(), "commit", "-m", message, "--allow-empty");
      if (commitRes.exitCode() != 0) {
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR,
            "git commit failed (exit=" + commitRes.exitCode() + "): " + commitRes.output());
      }
      return ToolResult.ok(commitRes.output());
    } catch (IOException ioe) {
      log.warn("gitCommit error", ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }
}
