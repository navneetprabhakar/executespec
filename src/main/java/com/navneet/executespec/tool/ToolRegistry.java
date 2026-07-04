package com.navneet.executespec.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.tool.remote.RemoteToolDispatcher;

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
   * Local-hands/remote-brain (RL-2026-05-043). When a run is bound to a remote tool session, tool
   * calls are dispatched to the connected client instead of executed on the server. Inert for every
   * other run, so the default pipeline is unaffected. Nullable in unit tests that don't exercise
   * it.
   */
  private final RemoteToolDispatcher remoteToolDispatcher;

  /**
   * Gates the T1 lean-toolset worker binding ({@code worker.lean-toolset.enabled}). Nullable in
   * unit tests (null ⇒ flag treated as OFF ⇒ legacy full binding).
   */
  private final com.navneet.executespec.config.featureflag.FeatureFlagService featureFlagService;

  /**
   * Task 10 — capability-profile lookups for provider-aware worker binds. Nullable in unit tests
   * (null ⇒ no profile tools).
   */
  private final com.navneet.executespec.router.ModelRegistry modelRegistry;

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

  /**
   * Harness-resolution T1 (flag {@code worker.lean-toolset.enabled}, V255, default OFF) — the
   * canonical toolset for SERVER-SIDE workers. The registry also carries ~25 CLI-parity
   * alias/fallback tools ({@code read_file} / {@code bash} / {@code grep} / {@code multiEdit} /
   * {@code executeCommand} / {@code lsp} / stubs — see {@link CommandToolConfiguration}); binding
   * those to server workers duplicates the schema 2-3× (schema-token bloat, the RL-2026-05-061
   * stall class), splits model behavior across aliases with DIFFERENT error semantics ({@code
   * bash} errs on non-zero exit, {@code shell} returns ok+exit=N), and teaches models the tool
   * surface is unreliable when they hit a stub. When the flag is ON, server-side (non-remote)
   * workers bind ONLY this set; remote/local-hands runs keep the full parity set — the aliases
   * exist for those clients.
   *
   * <p>Membership rule: core tools + genuinely ADDITIVE capabilities with no core equivalent
   * (glob, move, delete). Pure duplicates, web tools (cleanroom is network-none; SSRF surface),
   * and unwired fallbacks (lsp, spawn_subagent, mcp_client, plan/todo stubs) stay out. Flag-gated
   * tools listed here still require their own flag ON to bind.
   */
  public static final Set<String> LEAN_WORKER_TOOLS =
      Set.of(
          "readFile",
          "writeFile",
          "editFile",
          "listFiles",
          "searchFiles",
          "glob",
          "move",
          "delete",
          "gitStatus",
          "gitDiff",
          "shell",
          "buildProject",
          "runTests",
          "install",
          "test",
          "lint",
          "format",
          "completeTask",
          "backgroundJob",
          "readCodeOutline",
          "runWorkspaceTests",
          "verifyBoot");

  /** Names of all registered tools. */
  public List<String> toolNames() {
    return allTools.stream().map(Tool::name).sorted().toList();
  }

  /** Look up a tool by name; null if not registered. */
  public Tool find(String name) {
    return allTools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
  }

  /**
   * Flag-gated tool binding (W4.2/W3.1) — a {@link FlagGatedTool} that is OFF for this run is NOT
   * bound at all (absent from the model's tool schema), not bound-but-inert, per the RL-2026-05-061
   * lesson that a tool merely <i>present</i> in the schema can cause reasoning-only stalls on
   * schema-sensitive providers (DeepSeek). Opted in: {@link BackgroundJobTool}, {@link
   * ReadCodeOutlineTool}, {@link WorkspaceRunTestsTool}, {@link VerifyBootTool}; every non-{@code
   * FlagGatedTool} is always bound.
   */
  private boolean isToolBound(Tool t) {
    return !(t instanceof FlagGatedTool fg) || fg.enabled();
  }

  /**
   * Bind every registered tool to the given context, returning a list of Spring AI {@link
   * ToolCallback}s ready to pass to a {@code ChatOptions.toolCallbacks(...)} builder.
   */
  public List<ToolCallback> bind(ExecutionContext ctx) {
    return allTools.stream()
        .filter(this::isToolBound)
        .map(
            t ->
                (ToolCallback)
                    new ContextBoundToolCallback(t, ctx, objectMapper, remoteToolDispatcher))
        .collect(Collectors.toList());
  }

  /**
   * Worker-restricted bind — same as {@link #bind(ExecutionContext)} but excludes any tool listed
   * in {@link #WORKER_DENY_TOOLS} (currently just {@code gitCommit}). Use this instead of {@code
   * bind} when constructing the worker's LLM request so the model can't get itself stuck in a
   * per-task git-plumbing loop.
   */
  public List<ToolCallback> bindForWorker(ExecutionContext ctx) {
    return bindForWorker(ctx, null);
  }

  /**
   * Provider-aware worker bind (task 10). A provider's capability profile may list flag-gated
   * tool NAMES in {@code optionalTools} — those bind for that provider even while the tool's
   * global flag is OFF (per-provider re-enablement; the global OFF continues to protect
   * schema-sensitive providers). Null provider / no profile ⇒ global flag gating only.
   */
  public List<ToolCallback> bindForWorker(ExecutionContext ctx, String provider) {
    // T1 lean toolset: server-side workers get the canonical set only; remote/local-hands runs
    // keep the full alias set for client parity. Flag default OFF ⇒ byte-identical binding.
    final boolean lean =
        featureFlagService != null
            && featureFlagService.isEnabled("worker.lean-toolset.enabled")
            && !isRemoteRun(ctx);
    final java.util.Set<String> profileTools = profileOptionalTools(provider);
    return allTools.stream()
        .filter(t -> !WORKER_DENY_TOOLS.contains(t.name()))
        .filter(t -> isToolBound(t) || profileTools.contains(t.name()))
        .filter(t -> !lean || LEAN_WORKER_TOOLS.contains(t.name()))
        .map(
            t ->
                (ToolCallback)
                    new ContextBoundToolCallback(t, ctx, objectMapper, remoteToolDispatcher))
        .collect(Collectors.toList());
  }

  private java.util.Set<String> profileOptionalTools(String provider) {
    if (provider == null || modelRegistry == null) return java.util.Set.of();
    try {
      var profile = modelRegistry.capabilityProfileFor(provider);
      if (profile == null || profile.getOptionalTools() == null) return java.util.Set.of();
      return new java.util.HashSet<>(profile.getOptionalTools());
    } catch (Exception e) {
      return java.util.Set.of();
    }
  }

  /**
   * True when this run is bound to a remote tool session (local-hands/remote-brain). The V2 {@code
   * WorkerAgent} dispatches tools MANUALLY (calls {@link Tool#execute} directly rather than through
   * the Spring-AI {@link ContextBoundToolCallback}), so the manual-dispatch path must consult this
   * + {@link #dispatchRemote} to honor remote mode — the remote branch inside {@code
   * ContextBoundToolCallback.call} only covers Spring-AI internal tool execution. RL-2026-06
   * (terminal-bench).
   */
  public boolean isRemoteRun(ExecutionContext ctx) {
    return remoteToolDispatcher != null
        && ctx != null
        && remoteToolDispatcher.isRemote(ctx.runId());
  }

  /**
   * Dispatch a tool call to the connected client and return its result envelope verbatim (same
   * {@code {"ok":...}} shape the local path produces). Caller must have checked {@link
   * #isRemoteRun}. Blocks (bounded) inside the dispatcher for the client to reply.
   */
  public String dispatchRemote(ExecutionContext ctx, String toolName, String argsJson) {
    return remoteToolDispatcher.dispatch(ctx.runId(), ctx.taskId(), toolName, argsJson);
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
  record ContextBoundToolCallback(
      Tool tool,
      ExecutionContext ctx,
      ObjectMapper objectMapper,
      RemoteToolDispatcher remoteToolDispatcher)
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
      // Local-hands/remote-brain: if this run executes tools on a connected client, dispatch there
      // and return the client's result envelope. No-op for the default server-executed pipeline
      // (remoteToolDispatcher null in tests, or isRemote false for every server-run).
      if (remoteToolDispatcher != null && remoteToolDispatcher.isRemote(ctx.runId())) {
        return remoteToolDispatcher.dispatch(ctx.runId(), ctx.taskId(), tool.name(), toolInput);
      }
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
      // B4 (RL-2026-06, vix-leverage W1.3) — same conservative required-field check as the manual
      // worker dispatch path (WorkerAgent.dispatchOne), centralized in ToolArgValidator.
      var argErr = ToolArgValidator.validate(tool.inputSchema(), args);
      if (argErr.isPresent()) {
        return errorEnvelope(ToolResult.ErrorKind.INVALID_ARGS, argErr.get());
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
