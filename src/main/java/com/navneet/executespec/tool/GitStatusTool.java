package com.navneet.executespec.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/** V2 gitStatus tool. Returns {@code git status --short} for every repository in the workspace. */
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
      List<String> sections = new ArrayList<>();
      for (GitWorkspaceView.Repository repository :
          GitWorkspaceView.repositories(ctx.workspaceRoot())) {
        GitInvoker.GitResult r = GitInvoker.run(repository.root(), "status", "--short");
        if (r.exitCode() != 0) {
          return ToolResult.err(
              ToolResult.ErrorKind.IO_ERROR,
              "git status failed in "
                  + repository.workspacePrefix()
                  + " (exit="
                  + r.exitCode()
                  + "): "
                  + r.output());
        }
        if (!r.output().isBlank()) {
          sections.add(GitWorkspaceView.section(repository, r.output().stripTrailing()));
        }
      }
      return ToolResult.ok(sections.isEmpty() ? "(clean)" : String.join("\n", sections));
    } catch (IOException ioe) {
      log.warn("gitStatus error", ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    }
  }
}
