package com.navneet.executespec.v2.tool;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** V2 gitStatus tool. Returns {@code git status --short} output for the run's workspace. */
@Component
@Slf4j
public class GitStatusTool implements Tool {

  @Override
  public String name() {
    return "gitStatus";
  }

  @Override
  public String description() {
    return "Shows the working tree status (modified, untracked, staged files) in short format.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {},
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    try {
      GitInvoker.GitResult r = GitInvoker.run(ctx.workspaceRoot(), "status", "--short");
      if (r.exitCode() != 0) {
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR,
            "git status failed (exit=" + r.exitCode() + "): " + r.output());
      }
      return ToolResult.ok(r.output().isBlank() ? "(clean)" : r.output());
    } catch (IOException ioe) {
      log.warn("gitStatus error", ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }
}
