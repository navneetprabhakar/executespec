package com.navneet.executespec.tool;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * V2 gitDiff tool. Returns the working-tree diff (unstaged + staged) since HEAD, optionally limited
 * to a pathspec.
 *
 * <p>Harness-resolution T3 (2026-07-04): the old hard 3,000-char truncation made self-review of any
 * multi-file change physically impossible — a worker (or the critic/verifier) that wanted to
 * inspect its own patch before {@code completeTask} could not see it, while every other bulky tool
 * had the {@link ToolOutput#budget} spill-to-disk treatment. Now: 6KB inline + full diff persisted
 * to {@code .agent-context/tool-output/} with a readFile path, plus an optional {@code path} arg to
 * scope the diff.
 */
@Component
@Slf4j
public class GitDiffTool implements Tool {

  @Override
  public String name() {
    return "gitDiff";
  }

  @Override
  public String description() {
    return "Shows the diff of all changes (staged + unstaged) since the last commit. Pass `path`"
        + " to limit the diff to one file/directory. Large diffs are saved to a file — you get"
        + " an inline preview plus a path you can readFile for the complete diff. Use this to"
        + " review your own changes before completeTask.";
  }

  @Override
  public String inputSchema() {
    return """
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "Optional workspace-relative file or directory to limit the diff to."
    }
  },
  "additionalProperties": false
}
""";
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    try {
      String path = args.hasNonNull("path") ? args.get("path").asText("").trim() : "";
      GitInvoker.GitResult r;
      if (path.isEmpty()) {
        r = GitInvoker.run(ctx.workspaceRoot(), "diff", "HEAD");
      } else {
        // PathSafety keeps the pathspec inside the workspace (defence-in-depth; git would
        // also refuse, but with a confusing error).
        PathSafety.safeResolve(ctx.workspaceRoot(), path);
        r = GitInvoker.run(ctx.workspaceRoot(), "diff", "HEAD", "--", path);
      }
      if (r.exitCode() != 0) {
        return ToolResult.err(
            ToolResult.ErrorKind.IO_ERROR,
            "git diff failed (exit=" + r.exitCode() + "): " + r.output());
      }
      String out = r.output();
      if (out.isBlank()) return ToolResult.ok("(no changes)");
      // T3: budget instead of amputate — inline preview + full spill with readFile path.
      return ToolResult.ok(ToolOutput.budget(ctx, "gitDiff", out, ToolOutput.DEFAULT_INLINE_CAP));
    } catch (IOException ioe) {
      log.warn("gitDiff error", ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return ToolResult.err(ToolResult.ErrorKind.INTERNAL, "interrupted");
    } catch (IllegalArgumentException iae) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, iae.getMessage());
    }
  }
}
