package com.navneet.executespec.v2.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Central registry of V2 tools. Adapts {@link Tool} beans to Spring AI {@link ToolCallback}s bound
 * to a per-task {@link ExecutionContext}.
 *
 * <p>Each call to {@link #bind(ExecutionContext)} returns a fresh list of callbacks with the
 * supplied context captured — so concurrent runs never see each other's workspaces, even though the
 * underlying {@code Tool} beans are singletons.
 *
 * <p>Result serialization: success → {@code {"ok": true, "content": "..."}}; error → {@code {"ok":
 * false, "kind": "...", "message": "..."}}. Both are valid JSON the model can parse if it cares
 * about structured handling, while still being human-readable in the textContent.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ToolRegistry {

  private final List<Tool> allTools;
  private final ObjectMapper objectMapper;

  /**
   * Tools the worker agent must NOT call directly. Workspace persistence (git commit + push)
   * happens at run-end via {@code ScmPushService.pushMultiRepo} — having gitCommit in the worker's
   * bind set causes runaways: the worker observes that its writeFile didn't auto-commit, tries to
   * fix git state, gets confused by untracked files, and burns its iteration budget on git plumbing
   * instead of code.
   *
   * <p>Diagnosed on run {@code a076c876} task T2 (2026-05-03): a LOW task hit the 60-iter cap, with
   * 22+ iterations spent in a {@code git status} / {@code git add} / {@code gitCommit} loop after
   * the actual code change had already been applied. Removing gitCommit from the worker's
   * authorities makes that runaway structurally impossible.
   *
   * <p>Read-only git tools ({@code gitStatus}, {@code gitDiff}) stay in the worker bind — they're
   * useful for reading-but-not-mutating state. Reviewer / orchestration callers that legitimately
   * need to commit can still go through {@link #bind(ExecutionContext)}, which is unchanged.
   */
  public static final Set<String> WORKER_DENY_TOOLS = Set.of("gitCommit");

  /** Names of all registered tools. */
  public List<String> toolNames() {
    return allTools.stream().map(Tool::name).sorted().toList();
  }

  /** Look up a tool by name; null if not registered. */
  public Tool find(String name) {
    return allTools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
  }

  /**
   * Bind every registered tool to the given context, returning a list of Spring AI {@link
   * ToolCallback}s ready to pass to a {@code ChatOptions.toolCallbacks(...)} builder.
   */
  public List<ToolCallback> bind(ExecutionContext ctx) {
    return allTools.stream()
        .map(t -> (ToolCallback) new ContextBoundToolCallback(t, ctx, objectMapper))
        .collect(Collectors.toList());
  }

  /**
   * Worker-restricted bind — same as {@link #bind(ExecutionContext)} but excludes any tool listed
   * in {@link #WORKER_DENY_TOOLS} (currently just {@code gitCommit}). Use this instead of {@code
   * bind} when constructing the worker's LLM request so the model can't get itself stuck in a
   * per-task git-plumbing loop.
   */
  public List<ToolCallback> bindForWorker(ExecutionContext ctx) {
    return allTools.stream()
        .filter(t -> !WORKER_DENY_TOOLS.contains(t.name()))
        .map(t -> (ToolCallback) new ContextBoundToolCallback(t, ctx, objectMapper))
        .collect(Collectors.toList());
  }

  /**
   * Map view: tool name → tool definition. Useful for prompt injection (when native tool calling
   * isn't being used and the schemas need to live in the prompt).
   */
  public Map<String, ToolDefinition> toolDefinitions() {
    return allTools.stream()
        .collect(
            Collectors.toMap(
                Tool::name,
                t ->
                    DefaultToolDefinition.builder()
                        .name(t.name())
                        .description(t.description())
                        .inputSchema(t.inputSchema())
                        .build()));
  }

  /**
   * Spring AI {@link ToolCallback} adapter — parses the LLM's JSON args, dispatches to the
   * underlying {@link Tool} with the captured {@link ExecutionContext}, and serializes the {@link
   * ToolResult} as a JSON envelope.
   */
  record ContextBoundToolCallback(Tool tool, ExecutionContext ctx, ObjectMapper objectMapper)
      implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
      return DefaultToolDefinition.builder()
          .name(tool.name())
          .description(tool.description())
          .inputSchema(tool.inputSchema())
          .build();
    }

    @Override
    public String call(String toolInput) {
      JsonNode args;
      try {
        args =
            (toolInput == null || toolInput.isBlank())
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(toolInput);
      } catch (Exception parseEx) {
        return errorEnvelope(
            ToolResult.ErrorKind.INVALID_ARGS,
            "tool input is not valid JSON: " + parseEx.getMessage());
      }
      try {
        ToolResult result = tool.execute(args, ctx);
        return switch (result) {
          case ToolResult.Ok ok -> okEnvelope(ok.content());
          case ToolResult.Err err -> errorEnvelope(err.kind(), err.message());
        };
      } catch (Exception unexpected) {
        log.error(
            "Tool '{}' threw unexpected exception (ctx run={} task={})",
            tool.name(),
            ctx.runId(),
            ctx.taskId(),
            unexpected);
        return errorEnvelope(
            ToolResult.ErrorKind.INTERNAL,
            unexpected.getClass().getSimpleName() + ": " + unexpected.getMessage());
      }
    }

    private String okEnvelope(String content) {
      ObjectNode env = objectMapper.createObjectNode();
      env.put("ok", true);
      env.put("content", content);
      return env.toString();
    }

    private String errorEnvelope(ToolResult.ErrorKind kind, String message) {
      ObjectNode env = objectMapper.createObjectNode();
      env.put("ok", false);
      env.put("kind", kind.name());
      env.put("message", message);
      return env.toString();
    }
  }
}
