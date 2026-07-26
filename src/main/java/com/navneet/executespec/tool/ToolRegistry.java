package com.navneet.executespec.tool;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.security.ContextSanitizer;
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

  /** Evidence gate for model/provider capability overlays (seeded OFF; V271). */
  public static final String FLAG_CAPABILITY_PROFILES = "worker.capability-profiles.enabled";

  /**
   * RR-538 — secret-redaction + length-bound contract for model/client-visible error text. {@link
   * ContextSanitizer} is a stateless pattern-based helper (no injected dependencies), so it is held
   * statically rather than threaded through {@link ContextBoundToolCallback} and every {@code
   * bind*} + test construction site. Full exception detail stays in server telemetry only.
   */
  static final ContextSanitizer ERROR_SANITIZER = new ContextSanitizer();

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
   * Gates the T1 lean-toolset worker binding ({@code worker.lean-toolset.enabled}, graduated ON by
   * V340). Nullable in unit tests (null ⇒ flag treated as OFF ⇒ legacy full binding).
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
   * Compatibility fallbacks that must never be advertised to a server-side worker. These tools
   * either claim a capability that is not actually wired (LSP/MCP), or silently substitute a
   * regex/edit fallback for a structural operation (AST). Keeping them in the global bean list is
   * intentional for local-hands clients, but exposing them to a coding worker makes the model
   * choose between misleading schemas and the canonical tools. This deny list is unconditional so
   * an older database row with {@code worker.lean-toolset.enabled=false} cannot regress production
   * worker behavior. Remote/local-hands runs explicitly retain the compatibility surface below.
   */
  public static final Set<String> SERVER_WORKER_EXCLUDED_TOOLS =
      Set.of(
          // misleading / unwired capability claims
          "ast_search",
          "astSearch",
          "ast_edit",
          "astEdit",
          "lsp",
          "mcp_client",
          "mcpClient",
          // RR-548 — network egress. A server-side coding worker runs against a cloned workspace
          // (and, for SWE-bench / ProgramBench, a `--network none` cleanroom); it has no legitimate
          // need to fetch a URL, while a spec or an injected repository instruction absolutely can
          // steer it at an internal service. The guarded fetch seam still applies wherever these
          // tools remain bound (local-hands, generic bind), but the server worker is simply never
          // shown them. This exclusion is unconditional and does not depend on
          // `worker.lean-toolset.enabled`, whose default-OFF state is what exposed them before.
          "web_fetch",
          "web_search",
          // RR-1599 — redundant alias spellings of a capability the worker already has under its
          // canonical name. `executeCommand`/`runCommand`/`terminalCommand` ARE `shell`,
          // `build`/`runBuild` ARE `buildProject`, `rename` IS `move`, `deletePath` IS `delete`,
          // `backgroundProcess` IS `background_process`. Every worker safety/evidence gate
          // downstream (WorkerAgent's write/shell/verified-pass classification,
          // SequentialExecutor's
          // repeated-failure and mutation-path signatures) matches on the literal tool NAME, so an
          // alias executed a privileged action while staying invisible to the guard policing it.
          // Until those gates key on ToolCapability, a server worker is never shown a second
          // spelling for a capability it already has. Dispatch compatibility is unaffected —
          // find(String) still resolves the alias for a persisted or replayed call.
          "executeCommand",
          "runCommand",
          "terminalCommand",
          "build",
          "runBuild",
          "rename",
          "deletePath",
          "backgroundProcess");

  /**
   * Harness-resolution T1 (flag {@code worker.lean-toolset.enabled}, V255, graduated ON by V340) —
   * the canonical toolset for SERVER-SIDE workers. The registry also carries ~25 CLI-parity
   * alias/fallback tools ({@code read_file} / {@code bash} / {@code grep} / {@code multiEdit} /
   * {@code executeCommand} / {@code lsp} / stubs — see {@link CommandToolConfiguration}); binding
   * those to server workers duplicates the schema 2-3× (schema-token bloat, the RL-2026-05-061
   * stall class), splits model behavior across aliases with DIFFERENT error semantics ({@code bash}
   * errs on non-zero exit, {@code shell} returns ok+exit=N), and teaches models the tool surface is
   * unreliable when they hit a stub. When the flag is ON, server-side (non-remote) workers bind
   * ONLY this set. Remote workers always use one canonical schema per capability; compatibility
   * aliases remain registered for replay/backward-compatible dispatch but are not advertised.
   *
   * <p>Membership rule: core tools + genuinely ADDITIVE capabilities with no core equivalent (glob,
   * move, delete). Pure duplicates, web tools (cleanroom is network-none; SSRF surface), and
   * unwired fallbacks (lsp, mcp_client, plan/todo stubs) stay out. Flag-gated tools listed here
   * still require their own flag ON to bind.
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

  /**
   * Canonical schemas supported by the released CLI local-hands runtime. This retains genuinely
   * additive client capabilities (structural search/edit, LSP, MCP, web, background jobs, and
   * focused project commands) while removing alternate spellings and compatibility stubs. Legacy
   * aliases remain in {@link #allTools}, so an in-flight/replayed older turn can still dispatch
   * them through {@link #find(String)}.
   */
  public static final Set<String> REMOTE_LOCAL_HANDS_WORKER_TOOLS =
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
          "background_process",
          "web_fetch",
          "web_search",
          "diagnostics",
          "ast_search",
          "ast_edit",
          "lsp",
          "mcp_client",
          "buildProject",
          "runTests",
          "installDependencies",
          "lintProject",
          "formatProject",
          "completeTask");

  /** Canonical schemas implemented by the Terminal-Bench Harbor adapter. */
  public static final Set<String> REMOTE_TERMINAL_BENCH_WORKER_TOOLS =
      Set.of(
          "readFile",
          "writeFile",
          "editFile",
          "listFiles",
          "searchFiles",
          "gitStatus",
          "gitDiff",
          "shell",
          "buildProject",
          "runTests",
          "completeTask");

  /** Names of all registered tools. */
  public List<String> toolNames() {
    return allTools.stream().map(Tool::name).sorted().toList();
  }

  /**
   * Canonical capability behind a tool NAME (RR-1599). Empty when the name is unregistered or
   * unclassified — callers must fail closed on empty, never treat it as harmless.
   */
  public java.util.Optional<ToolCapability> capabilityOf(String toolName) {
    Tool tool = find(toolName);
    if (tool != null && tool.capability() != null) {
      return java.util.Optional.of(tool.capability());
    }
    return ToolCapabilities.of(toolName);
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
   * Binds a deliberately small implementer tool set. Mutation calls are routed through the supplied
   * authorization function after schema parsing; read/test tools execute normally. This prevents a
   * model loop from bypassing the persisted single-writer reservation.
   */
  public List<ToolCallback> bindForImplementer(
      ExecutionContext ctx,
      Set<String> allowedNames,
      BiFunction<String, JsonNode, ToolResult> authorizedMutation) {
    return allTools.stream()
        .filter(this::isToolBound)
        .filter(tool -> allowedNames == null || allowedNames.contains(tool.name()))
        .map(
            tool ->
                (ToolCallback)
                    new ContextBoundToolCallback(
                        tool, ctx, objectMapper, remoteToolDispatcher, authorizedMutation))
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
   * Provider-aware worker bind (task 10). A provider's capability profile may list flag-gated tool
   * NAMES in {@code optionalTools} — those bind for that provider even while the tool's global flag
   * is OFF (per-provider re-enablement; the global OFF continues to protect schema-sensitive
   * providers). Null provider / no profile ⇒ global flag gating only.
   */
  public List<ToolCallback> bindForWorker(ExecutionContext ctx, String provider) {
    return bindForWorker(ctx, provider, null);
  }

  /**
   * Provider/model-aware worker bind. A model profile ({@code provider/model}) takes precedence
   * over a provider profile; this permits safe tuning when models behind one provider have
   * different tool-schema behaviour.
   */
  public List<ToolCallback> bindForWorker(ExecutionContext ctx, String provider, String model) {
    return bindForWorker(ctx, provider, model, null);
  }

  /**
   * Provider/model-aware worker bind with a caller-supplied tighten-only allow-list. The allow-list
   * is applied after every registry, flag, remote/local and provider capability restriction, so it
   * cannot re-enable a forbidden tool.
   */
  public List<ToolCallback> bindForWorker(
      ExecutionContext ctx, String provider, String model, java.util.Set<String> allowedNames) {
    final boolean remote = isRemoteRun(ctx);
    // T1 lean toolset: server-side workers get the canonical set only when enabled. Remote workers
    // are always canonical because transmitting duplicate aliases on every LLM turn is pure schema
    // cost and increases tool-selection ambiguity; alias dispatch compatibility remains below the
    // binding layer.
    final boolean lean =
        featureFlagService != null
            && featureFlagService.isEnabled("worker.lean-toolset.enabled")
            && !remote;
    final Set<String> remoteTools = remote ? remoteWorkerTools(ctx) : Set.of();
    final java.util.Set<String> profileTools = profileOptionalTools(provider, model);
    return allTools.stream()
        .filter(t -> !WORKER_DENY_TOOLS.contains(t.name()))
        // Fallback/structural compatibility tools remain available to explicit local-hands
        // sessions, but are never advertised to a server worker even while the legacy lean flag is
        // OFF. This keeps old persisted flag state from reintroducing misleading schemas.
        .filter(t -> remote || !SERVER_WORKER_EXCLUDED_TOOLS.contains(t.name()))
        .filter(t -> isToolBound(t) || profileTools.contains(t.name()))
        .filter(t -> !remote || remoteTools.contains(t.name()))
        .filter(t -> !lean || LEAN_WORKER_TOOLS.contains(t.name()))
        .filter(t -> allowedNames == null || allowedNames.contains(t.name()))
        .map(
            t ->
                (ToolCallback)
                    new ContextBoundToolCallback(t, ctx, objectMapper, remoteToolDispatcher))
        .collect(Collectors.toList());
  }

  private Set<String> remoteWorkerTools(ExecutionContext ctx) {
    if (remoteToolDispatcher != null
        && remoteToolDispatcher.toolProfile(ctx.runId())
            == RemoteToolDispatcher.ToolProfile.TERMINAL_BENCH) {
      return REMOTE_TERMINAL_BENCH_WORKER_TOOLS;
    }
    return REMOTE_LOCAL_HANDS_WORKER_TOOLS;
  }

  private java.util.Set<String> profileOptionalTools(String provider) {
    return profileOptionalTools(provider, null);
  }

  private java.util.Set<String> profileOptionalTools(String provider, String model) {
    if (provider == null || modelRegistry == null || !capabilityProfilesEnabled())
      return java.util.Set.of();
    try {
      var profile =
          model == null
              ? modelRegistry.capabilityProfileFor(provider)
              : modelRegistry.capabilityProfileFor(provider, model);
      if (profile == null || profile.getOptionalTools() == null) return java.util.Set.of();
      return new java.util.HashSet<>(profile.getOptionalTools());
    } catch (Exception e) {
      return java.util.Set.of();
    }
  }

  private boolean capabilityProfilesEnabled() {
    try {
      return featureFlagService != null && featureFlagService.isEnabled(FLAG_CAPABILITY_PROFILES);
    } catch (RuntimeException e) {
      log.warn("Capability-profile flag lookup failed; optional tools remain disabled", e);
      return false;
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

  /** Deadline-aware variant used by the benchmark worker; local-hands callers keep the old API. */
  public String dispatchRemote(
      ExecutionContext ctx,
      String toolName,
      String argsJson,
      Long absoluteDeadlineEpochMs,
      Long shutdownReserveMs) {
    return remoteToolDispatcher.dispatch(
        ctx.runId(), ctx.taskId(), toolName, argsJson, absoluteDeadlineEpochMs, shutdownReserveMs);
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
      RemoteToolDispatcher remoteToolDispatcher,
      BiFunction<String, JsonNode, ToolResult> authorizedMutation)
      implements ToolCallback {

    ContextBoundToolCallback(
        Tool tool,
        ExecutionContext ctx,
        ObjectMapper objectMapper,
        RemoteToolDispatcher remoteToolDispatcher) {
      this(tool, ctx, objectMapper, remoteToolDispatcher, null);
    }

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
        ToolResult result =
            authorizedMutation != null && requiresMutationAuthorization(tool)
                ? authorizedMutation.apply(tool.name(), args)
                : tool.execute(args, ctx);
        return switch (result) {
          case ToolResult.Ok ok -> okEnvelope(ok.content());
          case ToolResult.Err err -> errorEnvelope(err.kind(), err.message());
        };
      } catch (Exception unexpected) {
        // Full throwable (message + stack) is logged to protected server telemetry only.
        log.error(
            "Tool '{}' threw unexpected exception (ctx run={} task={})",
            tool.name(),
            ctx.runId(),
            ctx.taskId(),
            unexpected);
        // RR-538 — never hand a raw exception message to the model/SSE/local client. Provider,
        // filesystem and process libraries routinely embed credentials, host paths, URLs, headers
        // and response bodies in getMessage(); route it through ContextSanitizer for secret
        // redaction and a hard length bound before it enters the worker context.
        return errorEnvelope(
            ToolResult.ErrorKind.INTERNAL, ERROR_SANITIZER.sanitizeException(unexpected));
      }
    }

    /**
     * RR-1607 — inverted predicate. This used to be {@code name.equals("writeFile") ||
     * name.equals("editFile")}, so adding any other write-capable name to the implementer's
     * allowlist ({@code multiEdit}, {@code applyPatch}, {@code move}, {@code delete}, …) would have
     * silently bypassed the persisted single-writer reservation this seam exists to enforce. The
     * predicate is now capability-derived and fail-closed: any capability that can change files (or
     * git history) is authorized, and an UNCLASSIFIED name is authorized too rather than executed.
     *
     * <p>Build/test capabilities deliberately still execute directly — the reservation is a
     * single-WRITER lease, and the current authorizer only knows how to arbitrate file mutations.
     * Widening it to command execution is a separate change with its own contract.
     */
    private static boolean requiresMutationAuthorization(Tool tool) {
      ToolCapability capability = tool.capability();
      return capability == null
          || capability.mutatesFiles()
          || capability == ToolCapability.GIT_WRITE;
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
