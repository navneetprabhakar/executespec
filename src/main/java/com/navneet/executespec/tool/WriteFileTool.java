package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.security.CodeAbuseScanner;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

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
  private final CodeAbuseScanner codeAbuseScanner;

  /**
   * PR-C (2026-05-13) — used to push {@code FILE_WRITTEN} SSE events to subscribed RunDetail
   * clients the moment a write lands. Without this, the file tree was relying on a 5s polling loop
   * in {@code RunDetailTabs}; the SSE push collapses the worst-case "wrote a file → see it in the
   * UI" gap from 5 seconds to ~100 ms.
   */
  private final SseBroadcaster sseBroadcaster;

  /**
   * Read-before-write ledger (RL-2026-05-051, harness adoption #1). writeFile only creates new
   * files, so there's nothing to gate here — but recording the written content means a subsequent
   * editFile on this file passes the read-before-edit check without forcing a redundant re-read.
   */
  private final ReadFileStateStore readState;

  /**
   * B1 (RL-2026-06, vix-leverage W1.1) — post-edit syntax diagnostics on the newly written file.
   * Flag-gated (default OFF) + fail-open; null-guarded so unit tests need not wire it.
   */
  private final EditDiagnosticsService editDiagnostics;

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
            ToolResult.ErrorKind.ALREADY_EXISTS,
            "file already exists: " + path + " — use editFile to modify existing files");
      }
      CodeAbuseScanner.ScanResult scanResult = codeAbuseScanner.scan(path, content);
      if (scanResult.flagged()) {
        log.warn(
            "writeFile blocked by CodeAbuseScanner path={} category={} detail={}",
            path,
            scanResult.category(),
            scanResult.detail());
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN,
            "generated content matched a "
                + scanResult.category()
                + " pattern and cannot be written");
      }
      Path parent = resolved.getParent();
      if (parent != null && !Files.exists(parent)) {
        Files.createDirectories(parent);
      }
      Files.writeString(resolved, content);
      // Record the new file's content so a later editFile on it passes the read-before-edit gate.
      readState.recordWrite(ctx.runId(), resolved, content);
      // PR-C (2026-05-13) — push the write through SSE so RunDetail file-tree subscribers can
      // refresh immediately instead of waiting for the 5s poll. Swallow broadcast failures so a
      // transient Redis blip never fails a worker write.
      try {
        sseBroadcaster.emit(ctx.runId(), RunEvent.fileWritten(ctx.runId(), ctx.taskId(), path));
      } catch (Exception ssee) {
        log.debug("FILE_WRITTEN SSE broadcast failed path={} runId={}", path, ctx.runId(), ssee);
      }
      // B1 — fast post-edit syntax diagnostics on the new file (flag-gated, fail-open; null in
      // tests).
      String diag =
          editDiagnostics == null
              ? ""
              : editDiagnostics.diagnose(ctx, path).map(d -> "\n" + d).orElse("");
      return ToolResult.ok("wrote " + content.length() + " chars to " + path + diag);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("writeFile IO error path={}", path, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}
