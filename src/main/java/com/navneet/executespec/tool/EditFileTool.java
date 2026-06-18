package com.navneet.executespec.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.navneet.executespec.security.CodeAbuseScanner;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * V2 editFile tool. Multi-edit string-replacement on an existing file.
 *
 * <p>Each edit specifies an {@code oldStr} to replace with {@code newStr}. Edits apply <b>in
 * order</b>; if any edit cannot be applied, the entire batch is rejected (no partial application).
 * This forces the model to send precise edits rather than guessing — hallucinated patches fail
 * loudly.
 *
 * <p>Matching is delegated to {@link StringEditMatcher}: exact match first, then (only when exact
 * finds nothing) a whitespace-tolerant / indent-flexible fallback so an edit doesn't fail purely on
 * trailing whitespace or indentation drift (RL-2026-06 — the truncation+exact-match fix). When
 * {@code oldStr} occurs multiple times, pass {@code replaceAll=true} or {@code occurrence=k}
 * instead of failing.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EditFileTool implements Tool {

  private final FileSandboxPolicy fileSandboxPolicy;
  private final CodeAbuseScanner codeAbuseScanner;

  /**
   * PR-C (2026-05-13) — pushes {@code FILE_WRITTEN} events to RunDetail SSE subscribers the moment
   * an edit lands; symmetric with the WriteFileTool wiring. See {@link
   * com.navneet.executespec.tool.WriteFileTool} for design rationale.
   */
  private final SseBroadcaster sseBroadcaster;

  /**
   * Read-before-write ledger (RL-2026-05-051, harness adoption #1). Enforces that the worker has
   * read this file in the current run before editing it, and that the file hasn't drifted on disk
   * since that read.
   */
  private final ReadFileStateStore readState;

  /**
   * B1 (RL-2026-06, vix-leverage W1.1) — post-edit syntax diagnostics. Itself flag-gated (default
   * OFF) and fail-open. Null in unit tests that construct the tool directly (null-guarded at the
   * call site); a real bean in production.
   */
  private final EditDiagnosticsService editDiagnostics;

  @Override
  public String name() {
    return "editFile";
  }

  @Override
  public String description() {
    return "Applies one or more string replacements to an existing file. Each edit specifies "
        + "oldStr (text to find) and newStr (replacement). Matching tries exact first, then "
        + "tolerates trailing-whitespace/indentation drift. If oldStr occurs more than once, set "
        + "replaceAll=true or occurrence=<k> (1-indexed). Edits apply in order; if any edit cannot "
        + "be applied the entire batch is rejected with no changes written.";
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
            "description": "Text to find. Exact match preferred; trailing-whitespace and indentation drift are tolerated as a fallback"
          },
          "newStr": {
            "type": "string",
            "description": "Replacement text"
          },
          "replaceAll": {
            "type": "boolean",
            "description": "Replace every occurrence of oldStr (default false)"
          },
          "occurrence": {
            "type": "integer",
            "minimum": 1,
            "description": "Replace only the k-th (1-indexed) occurrence of oldStr"
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
      // Read-before-write gate (RL-2026-05-051, harness adoption #1): the worker must have read
      // this file in the current run, and it must not have drifted since. Grounds edits in real
      // content + catches a file rewritten under the worker (the lint-pollution class of bug).
      ReadFileStateStore.Status rbw = readState.check(ctx.runId(), resolved, working);
      if (rbw == ReadFileStateStore.Status.NOT_READ) {
        return ToolResult.err(
            ToolResult.ErrorKind.STALE_READ,
            "read-before-edit: you have not readFile "
                + path
                + " in this run. Call readFile "
                + path
                + " first so your edit is grounded in the actual current contents, then editFile.");
      }
      if (rbw == ReadFileStateStore.Status.STALE) {
        return ToolResult.err(
            ToolResult.ErrorKind.STALE_READ,
            path
                + " changed on disk since you last read it — your view is stale (no changes"
                + " written). readFile "
                + path
                + " again, then re-issue your edit against the current contents.");
      }
      List<String> appliedSummary = new ArrayList<>();
      int idx = 0;
      for (JsonNode edit : editsNode) {
        String oldStr = edit.path("oldStr").asText(null);
        String newStr = edit.path("newStr").asText(null);
        if (oldStr == null || newStr == null) {
          return ToolResult.err(
              ToolResult.ErrorKind.INVALID_ARGS, "edit #" + idx + " missing oldStr or newStr");
        }
        boolean replaceAll = edit.path("replaceAll").asBoolean(false);
        Integer occurrence = edit.hasNonNull("occurrence") ? edit.get("occurrence").asInt() : null;
        StringEditMatcher.Result result =
            StringEditMatcher.apply(working, oldStr, newStr, replaceAll, occurrence);
        if (result instanceof StringEditMatcher.Failed f) {
          return ToolResult.err(
              ToolResult.ErrorKind.INVALID_ARGS,
              "edit #" + idx + " in " + path + ": " + f.message());
        }
        StringEditMatcher.Applied applied = (StringEditMatcher.Applied) result;
        working = applied.updated();
        appliedSummary.add(
            "#"
                + idx
                + ": -"
                + oldStr.length()
                + " +"
                + newStr.length()
                + " chars ("
                + applied.how()
                + ")");
        idx++;
      }
      CodeAbuseScanner.ScanResult scanResult = codeAbuseScanner.scan(path, working);
      if (scanResult.flagged()) {
        log.warn(
            "editFile blocked by CodeAbuseScanner path={} category={} detail={}",
            path,
            scanResult.category(),
            scanResult.detail());
        return ToolResult.err(
            ToolResult.ErrorKind.FORBIDDEN,
            "edited content matched a " + scanResult.category() + " pattern and cannot be written");
      }
      Files.writeString(resolved, working);
      // Ledger now reflects the post-edit content, so consecutive edits to this file don't trip the
      // read-before-write gate (the worker just edited it — its view is current).
      readState.recordWrite(ctx.runId(), resolved, working);
      // PR-C (2026-05-13) — push the edit through SSE; see WriteFileTool for rationale.
      try {
        sseBroadcaster.emit(ctx.runId(), RunEvent.fileWritten(ctx.runId(), ctx.taskId(), path));
      } catch (Exception ssee) {
        log.debug("FILE_WRITTEN SSE broadcast failed path={} runId={}", path, ctx.runId(), ssee);
      }
      String summary =
          "applied "
              + editsNode.size()
              + " edit(s) to "
              + path
              + ": "
              + String.join("; ", appliedSummary);
      // B1 — append fast post-edit syntax diagnostics (flag-gated, fail-open; null in unit tests).
      String diag =
          editDiagnostics == null
              ? ""
              : editDiagnostics.diagnose(ctx, path).map(d -> "\n" + d).orElse("");
      return ToolResult.ok(summary + diag);
    } catch (SecurityException se) {
      return ToolResult.err(ToolResult.ErrorKind.FORBIDDEN, se.getMessage());
    } catch (IOException ioe) {
      log.warn("editFile IO error path={}", path, ioe);
      return ToolResult.err(ToolResult.ErrorKind.IO_ERROR, ioe.getMessage());
    }
  }
}
