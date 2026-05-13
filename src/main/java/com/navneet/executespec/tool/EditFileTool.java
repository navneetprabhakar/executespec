package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 editFile tool. Multi-edit string-replacement on an existing file.
 *
 * <p>Each edit specifies an exact {@code oldStr} to replace with {@code newStr}. Edits apply <b>in
 * order</b>; if any edit's {@code oldStr} is not found, the entire batch is rejected (no partial
 * application). This forces the model to send precise edits rather than guessing — hallucinated
 * patches fail loudly.
 *
 * <p>Each {@code oldStr} must occur exactly once in the (current) file contents at the time that
 * edit is applied. Multiple occurrences require disambiguating context in the {@code oldStr}
 * itself.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EditFileTool implements Tool {

  private final FileSandboxPolicy fileSandboxPolicy;

  /**
   * PR-C (2026-05-13) — pushes {@code FILE_WRITTEN} events to RunDetail SSE subscribers the moment
   * an edit lands; symmetric with the WriteFileTool wiring. See {@link
   * com.navneet.executespec.tool.WriteFileTool} for design rationale.
   */
  private final SseBroadcaster sseBroadcaster;

  @Override
  public String name() {
    return "editFile";
  }

  @Override
  public String description() {
    return "Applies one or more exact-string replacements to an existing file. Each edit "
        + "must specify oldStr (exact text to find — must occur exactly once) and newStr "
        + "(replacement). Edits apply in order; if any edit fails to find its oldStr, the "
        + "entire batch is rejected with no changes written.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "path": {
                 "type": "string",
                 "description": "Workspace-relative path to the existing file"
               },
               "edits": {
                 "type": "array",
                 "minItems": 1,
                 "description": "Ordered list of replacements to apply",
                 "items": {
                   "type": "object",
                   "properties": {
                     "oldStr": {
                       "type": "string",
                       "description": "Exact text to find (must occur exactly once)"
                     },
                     "newStr": {
                       "type": "string",
                       "description": "Replacement text"
                     }
                   },
                   "required": ["oldStr", "newStr"],
                   "additionalProperties": false
                 }
               }
             },
             "required": ["path", "edits"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String path = args.path("path").asText(null);
    if (path == null || path.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "path is required");
    }
    JsonNode editsNode = args.path("edits");
    if (!editsNode.isArray() || editsNode.isEmpty()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "edits must be a non-empty array");
    }
    try {
      Path resolved = PathSafety.safeResolve(ctx.workspaceRoot(), path);
      // Guardrail-level file-sandbox gate — no-op at LOW.
      FileSandboxPolicy.Decision sandbox =
          fileSandboxPolicy.validateWrite(ctx.workspaceRoot(), resolved);
      if (!sandbox.allowed()) {
        log.warn("editFile blocked by FileSandboxPolicy path={} reason={}", path, sandbox.reason());
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, sandbox.reason());
      }
      if (!Files.exists(resolved)) {
        return ToolResult.err(
            ToolResult.ErrorKind.NOT_FOUND,
            "file not found: " + path + " — use writeFile to create new files");
      }
      String working = Files.readString(resolved);
      List<String> appliedSummary = new ArrayList<>();
      int idx = 0;
      for (JsonNode edit : editsNode) {
        String oldStr = edit.path("oldStr").asText(null);
        String newStr = edit.path("newStr").asText(null);
        if (oldStr == null || newStr == null) {
          return ToolResult.err(
              ToolResult.ErrorKind.INVALID_ARGS, "edit #" + idx + " missing oldStr or newStr");
        }
        int firstAt = working.indexOf(oldStr);
        if (firstAt < 0) {
          return ToolResult.err(
              ToolResult.ErrorKind.INVALID_ARGS,
              "edit #" + idx + ": oldStr not found in " + path + " (no changes written)");
        }
        int secondAt = working.indexOf(oldStr, firstAt + 1);
        if (secondAt >= 0) {
          return ToolResult.err(
              ToolResult.ErrorKind.INVALID_ARGS,
              "edit #"
                  + idx
                  + ": oldStr occurs multiple times in "
                  + path
                  + " — add disambiguating context (no changes written)");
        }
        working =
            working.substring(0, firstAt) + newStr + working.substring(firstAt + oldStr.length());
        appliedSummary.add("#" + idx + ": -" + oldStr.length() + " +" + newStr.length() + " chars");
        idx++;
      }
      Files.writeString(resolved, working);
      // PR-C (2026-05-13) — push the edit through SSE; see WriteFileTool for rationale.
      try {
        sseBroadcaster.emit(ctx.runId(), RunEvent.fileWritten(ctx.runId(), ctx.taskId(), path));
      } catch (Exception ssee) {
        log.debug("FILE_WRITTEN SSE broadcast failed path={} runId={}", path, ctx.runId(), ssee);
      }
      return ToolResult.ok(
          "applied "
              + editsNode.size()
              + " edit(s) to "
              + path
              + ": "
              + String.join("; ", appliedSummary));
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("editFile IO error path={}", path, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}
