package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 writeFile tool. Creates a NEW file. Errors if the file already exists.
 *
 * <p><b>Hard rule (V2 design):</b> writeFile is reserved for new files only. To modify an existing
 * file, the LLM must use {@link EditFileTool} instead. This cuts write-phase tokens significantly
 * because the model sends targeted edits rather than re-emitting the entire file contents on every
 * change.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WriteFileTool implements Tool {

  private final FileSandboxPolicy fileSandboxPolicy;

  @Override
  public String name() {
    return "writeFile";
  }

  @Override
  public String description() {
    return "Creates a NEW file with the given content. Errors if the file already exists — "
        + "use editFile to modify existing files. Parent directories are created automatically.";
  }

  @Override
  public String inputSchema() {
    return """
           {
             "type": "object",
             "properties": {
               "path": {
                 "type": "string",
                 "description": "Workspace-relative path of the new file"
               },
               "content": {
                 "type": "string",
                 "description": "Full file contents"
               }
             },
             "required": ["path", "content"],
             "additionalProperties": false
           }
           """;
  }

  @Override
  public ToolResult execute(JsonNode args, ExecutionContext ctx) {
    String path = args.path("path").asText(null);
    String content = args.path("content").asText(null);
    if (path == null || path.isBlank()) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "path is required");
    }
    if (content == null) {
      return ToolResult.err(ToolResult.ErrorKind.INVALID_ARGS, "content is required");
    }
    try {
      Path resolved = PathSafety.safeResolve(ctx.workspaceRoot(), path);
      // Guardrail-level file-sandbox gate — no-op at LOW; at MEDIUM/HIGH
      // rejects writes outside the allowed dir+dotfile set.
      FileSandboxPolicy.Decision sandbox =
          fileSandboxPolicy.validateWrite(ctx.workspaceRoot(), resolved);
      if (!sandbox.allowed()) {
        log.warn(
            "writeFile blocked by FileSandboxPolicy path={} reason={}", path, sandbox.reason());
        return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, sandbox.reason());
      }
      if (Files.exists(resolved)) {
        return ToolResult.err(
            ToolResult.ErrorKind.INVALID_ARGS,
            "file already exists: " + path + " — use editFile to modify existing files");
      }
      Path parent = resolved.getParent();
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
      }
      Files.writeString(resolved, content);
      return ToolResult.ok("wrote " + content.length() + " chars to " + path);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("writeFile IO error path={}", path, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}
