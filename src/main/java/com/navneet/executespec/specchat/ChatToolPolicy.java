package com.navneet.executespec.specchat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;

/**
 * Canonical capability and per-turn budget policy for every tool reachable from a chat model.
 * Unknown tools fail closed as {@link Capability#WRITE_REPO}; adding a tool without classifying it
 * must never accidentally grant it read-only authority.
 */
public final class ChatToolPolicy {

  public enum Capability {
    PROJECT_READ,
    PROJECT_ANALYSE,
    SPEND_AI,
    WRITE_REPO
  }

  public enum ConfirmationDisposition {
    /** Implemented by the V292 one-shot Project Chat confirmation endpoint. */
    PROJECT_CHAT_CONFIRMATION,
    /** Implemented by a separate typed action/proposal protocol, never this model loop. */
    EXTERNAL_STRUCTURED_PROTOCOL,
    /** Deliberately absent from model authority until its own scoped protocol exists. */
    PERMANENTLY_UNBOUND
  }

  public static final int MAX_MODEL_ROUNDS = 5;
  public static final int MAX_TOOL_CALLS = 12;
  public static final long MAX_TURN_TOKENS = 40_000;
  public static final double MAX_TURN_COST_USD = 0.50;
  public static final int MAX_CONVERSATION_CHARS = 60_000;

  /**
   * RR-514 — bounds on inline Base64 media carried by the legacy JSON compatibility chat path,
   * which (unlike the canonical attachment store) has no upload-admission gate. Enforced BEFORE any
   * decode so a request cannot allocate many copies of large encoded images and exhaust heap while
   * its declared conversation budget stays empty.
   */
  public static final int MAX_MEDIA_ITEMS = 4;

  /** Maximum decoded bytes for a single inline image. */
  public static final int MAX_MEDIA_ITEM_BYTES = 5 * 1024 * 1024;

  /** Maximum decoded bytes across all inline images on one turn. */
  public static final long MAX_MEDIA_TOTAL_BYTES = 12L * 1024 * 1024;

  private static final Map<String, Capability> CAPABILITIES =
      Map.ofEntries(
          // Run/session reads.
          Map.entry("list_recent_runs", Capability.PROJECT_READ),
          Map.entry("get_run_report", Capability.PROJECT_READ),
          // Project reads.
          Map.entry("list_repos", Capability.PROJECT_READ),
          Map.entry("list_pull_requests", Capability.PROJECT_READ),
          Map.entry("get_project_memory", Capability.PROJECT_READ),
          Map.entry("get_latest_architecture", Capability.PROJECT_READ),
          Map.entry("search_repo_files", Capability.PROJECT_READ),
          // Semantic search may lazily build/query an embedding index, so it is paid analysis.
          Map.entry("search_code", Capability.PROJECT_ANALYSE),
          Map.entry("get_latest_code_review", Capability.PROJECT_READ),
          Map.entry("list_project_documents", Capability.PROJECT_READ),
          Map.entry("read_generated_doc", Capability.PROJECT_READ),
          Map.entry("search_project_docs", Capability.PROJECT_ANALYSE),
          Map.entry("get_tech_debt_summary", Capability.PROJECT_READ),
          // Public-web reads.
          Map.entry("web_search", Capability.PROJECT_READ),
          Map.entry("web_fetch", Capability.PROJECT_READ),
          // Provider-spending actions.
          Map.entry("submit_run", Capability.SPEND_AI),
          Map.entry("run_code_review", Capability.PROJECT_ANALYSE),
          Map.entry("spawn_subagent", Capability.SPEND_AI),
          Map.entry("spawn_subagents", Capability.SPEND_AI),
          // Persistent state mutations.
          Map.entry("approve_plan", Capability.WRITE_REPO),
          Map.entry("reject_plan", Capability.WRITE_REPO),
          Map.entry("cancel_run", Capability.WRITE_REPO),
          Map.entry("regenerate_architecture", Capability.WRITE_REPO),
          Map.entry("generate_project_doc", Capability.SPEND_AI));

  private ChatToolPolicy() {}

  /** Return the explicit capability, failing closed for a missing/unknown name. */
  public static Capability capability(String toolName) {
    if (toolName == null) return Capability.WRITE_REPO;
    return CAPABILITIES.getOrDefault(toolName, Capability.WRITE_REPO);
  }

  public static boolean isExplicitlyClassified(String toolName) {
    return toolName != null && CAPABILITIES.containsKey(toolName);
  }

  public static Map<String, Capability> classifications() {
    return CAPABILITIES;
  }

  /** Exhaustive disposition for every classified non-read chat action. */
  public static ConfirmationDisposition confirmationDisposition(String toolName) {
    return switch (toolName == null ? "" : toolName) {
      case "regenerate_architecture", "generate_project_doc", "run_code_review" ->
          ConfirmationDisposition.PROJECT_CHAT_CONFIRMATION;
      case "submit_run" -> ConfirmationDisposition.EXTERNAL_STRUCTURED_PROTOCOL;
      case "approve_plan",
              "reject_plan",
              "cancel_run",
              "spawn_subagent",
              "spawn_subagents",
              "search_code",
              "search_project_docs" ->
          ConfirmationDisposition.PERMANENTLY_UNBOUND;
      default ->
          capability(toolName) == Capability.PROJECT_READ
              ? null
              : ConfirmationDisposition.PERMANENTLY_UNBOUND;
    };
  }

  /**
   * Full authority set for tools whose real behaviour crosses more than one boundary. The singular
   * {@link #capability} remains the highest-risk primary class used by existing audit envelopes.
   */
  public static Set<Capability> requiredCapabilities(String toolName) {
    return switch (toolName == null ? "" : toolName) {
      case "regenerate_architecture" ->
          Set.of(Capability.PROJECT_ANALYSE, Capability.SPEND_AI, Capability.WRITE_REPO);
      case "run_code_review", "search_code", "search_project_docs" ->
          Set.of(Capability.PROJECT_ANALYSE, Capability.SPEND_AI);
      default -> Set.of(capability(toolName));
    };
  }

  /**
   * Chat models receive pure PROJECT_READ tools only. Analysis, spend, and write actions flow
   * through the structured action protocol, whose confirmation is scoped, durable, and consumed
   * separately from model output.
   */
  public static List<ToolCallback> readOnly(List<ToolCallback> tools) {
    if (tools == null || tools.isEmpty()) return List.of();
    return tools.stream()
        .filter(
            tool ->
                tool != null
                    && tool.getToolDefinition() != null
                    && capability(tool.getToolDefinition().name()) == Capability.PROJECT_READ)
        .toList();
  }

  public static boolean requiresConfirmation(Capability capability) {
    return capability != Capability.PROJECT_READ;
  }
}
