package com.navneet.executespec.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.deployment.WorkspaceFileService;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only tool factory for the post-run chat copilot ({@link ChatService}). Companion to the
 * project-level {@link com.navneet.executespec.memory.chattools.ProjectChatTools} but scoped to a
 * single completed run's workspace.
 *
 * <p><b>Scope — strictly read-only.</b> Every tool here only READS run state so the copilot can
 * ground its suggestions in the actual generated code instead of the truncated file-tree snapshot
 * baked into the system prompt. There is deliberately NO write / shell / push / re-run tool — the
 * run chat is advisory only. To actually change code the user submits a new run (the "Iterate as
 * new run" / "Apply to Run" flows), which keep their own gates. This mirrors the user's direction
 * (2026-05-26): "only limited capabilities (give suggestions)".
 *
 * <p><b>Errors.</b> Every tool returns a JSON envelope ({@code {"ok": true|false, ...}}); we never
 * throw out of {@code call()} so a tool failure narrates as a result rather than 500-ing the turn.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RunChatTools {

  /** Per-file read cap — mirrors the worker {@code readFile} 8k-char budget. */
  private static final int MAX_FILE_CHARS = 8_000;

  private final WorkspaceFileService workspaceFileService;
  private final ObjectMapper json;

  /** Caller-scoped state every run tool needs. */
  public record ToolContext(String runId) {}

  /**
   * Build the per-turn read-only tool list. Pass the result to {@code
   * LlmRequest.toolsOverride(...)} so the router exposes the tools to the model and dispatches
   * calls back into this class.
   */
  public List<ToolCallback> bind(ToolContext ctx) {
    return List.of(listRunFiles(ctx), readRunFile(ctx));
  }

  // Tool 1: list every file in the run workspace

  private ToolCallback listRunFiles(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("list_run_files")
            .description(
                "List every file in this run's generated workspace (full relative paths). The "
                    + "system prompt only shows a truncated top-of-tree snapshot — call this when "
                    + "you need the complete manifest before reading a specific file. Read-only.")
            .inputSchema("{ \"type\": \"object\", \"properties\": {} }")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          List<String> files = workspaceFileService.listFiles(ctx.runId());
          ArrayNode arr = json.createArrayNode();
          files.forEach(arr::add);
          ObjectNode out = json.createObjectNode();
          out.put("ok", true);
          out.put("count", arr.size());
          out.set("files", arr);
          return out;
        });
  }

  // Tool 2: read one file's content

  private ToolCallback readRunFile(ToolContext ctx) {
    ToolDefinition def =
        DefaultToolDefinition.builder()
            .name("read_run_file")
            .description(
                "Read the full content of ONE file from this run's generated workspace (capped at "
                    + MAX_FILE_CHARS
                    + " characters). Use this to ground an answer or a suggested fix in the actual "
                    + "code rather than guessing. Pass a relative path exactly as returned by "
                    + "list_run_files. Read-only — you cannot edit; describe changes as a diff the "
                    + "user can apply via a new run.")
            .inputSchema(
                """
{
  "type": "object",
  "properties": {
    "path": {
      "type": "string",
      "description": "Relative path of the file to read, e.g. 'src/index.ts' or 'pom.xml'."
    }
  },
  "required": ["path"]
}
""")
            .build();
    return new SimpleToolCallback(
        def,
        args -> {
          String path =
              args.has("path") && !args.get("path").isNull() ? args.get("path").asText(null) : null;
          if (path == null || path.isBlank()) {
            return errorEnvelope("path is required (a relative path from list_run_files)");
          }
          String content = workspaceFileService.readFile(ctx.runId(), path);
          ObjectNode out = json.createObjectNode();
          if (content == null) {
            out.put("ok", false);
            out.put("message", "File not found or unreadable: " + path);
            return out;
          }
          boolean truncated = content.length() > MAX_FILE_CHARS;
          out.put("ok", true);
          out.put("path", path);
          out.put("truncated", truncated);
          out.put(
              "content",
              truncated ? content.substring(0, MAX_FILE_CHARS) + "\n…[truncated]" : content);
          return out;
        });
  }

  private ObjectNode errorEnvelope(String message) {
    ObjectNode out = json.createObjectNode();
    out.put("ok", false);
    out.put("message", message);
    return out;
  }

  /**
   * Adapter that turns a lambda into a Spring AI {@link ToolCallback}. Centralises JSON parsing +
   * error handling so each tool body only needs the happy path. Mirrors {@code
   * ProjectChatTools.SimpleToolCallback}.
   */
  private final class SimpleToolCallback implements ToolCallback {
    private final ToolDefinition def;
    private final ToolBody body;

    SimpleToolCallback(ToolDefinition def, ToolBody body) {
      this.def = def;
      this.body = body;
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return def;
    }

    @Override
    public String call(String toolInput) {
      JsonNode args;
      try {
        args =
            (toolInput == null || toolInput.isBlank())
                ? json.createObjectNode()
                : json.readTree(toolInput);
      } catch (Exception parseEx) {
        return errorEnvelope("invalid JSON input: " + parseEx.getMessage()).toString();
      }
      try {
        return body.run(args).toString();
      } catch (Exception ex) {
        log.warn(
            "Run chat tool '{}' threw {}: {}",
            def.name(),
            ex.getClass().getSimpleName(),
            ex.getMessage());
        return errorEnvelope(ex.getClass().getSimpleName() + ": " + ex.getMessage()).toString();
      }
    }
  }

  @FunctionalInterface
  private interface ToolBody {
    ObjectNode run(JsonNode args) throws Exception;
  }
}
