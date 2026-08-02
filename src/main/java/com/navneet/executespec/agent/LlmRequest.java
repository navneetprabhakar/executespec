package com.navneet.executespec.agent;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.navneet.executespec.executioncontext.LlmExecutionOperationAuthority;
import com.navneet.executespec.executioncontext.LlmExecutionScope;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import lombok.Builder;

/**
 * V2 LLM request DTO.
 *
 * <p>Provider-agnostic — {@link LlmRouter} dispatches based on {@link #provider()} and assembles
 * provider-specific options + tool callbacks internally.
 *
 * <p>Tool registration is implicit: the router calls {@link
 * com.navneet.executespec.tool.ToolRegistry#bind(ExecutionContext)} with the supplied {@link
 * #context()} to bind every registered V2 tool. There is no "select tools per call" knob in Phase 2
 * — every tool is always available, matching Claude Code's "model picks what it needs" pattern. A
 * future per-task constraint could narrow this if a real need arises.
 *
 * @param provider {@code "anthropic" | "openai" | "gemini"}
 * @param model explicit model name (e.g. {@code "claude-haiku-4-5-20251001"})
 * @param messages full message history (system + user + assistant + tool results). For first-turn
 *     calls this is just {@code [SystemMessage?, UserMessage]}.
 * @param context per-task execution context (binds tool callbacks)
 * @param temperature optional sampling temperature (default 0.0 for determinism)
 * @param maxTokens optional output cap; provider-specific defaults apply if null
 * @param readTimeoutSeconds optional per-call HTTP read-timeout override; provider clients use
 *     their default if null. Gemini and all OpenAI-wire routes use this value; Anthropic's Spring
 *     AI transport retains its configured client timeout.
 * @param toolChoice optional OpenAI-wire {@code tool_choice} override ({@code "auto"} | {@code
 *     "required"} | {@code "none"}). Null ⇒ the client's default ({@code "auto"}). Honoured only by
 *     OpenAI-wire clients and Gemini; {@code required} is also implemented through Anthropic's
 *     forced-tool seam. Set to {@code "required"} by the WorkerAgent Lever-B salvage call
 *     (RL-2026-05-059) to force a tool call out of a model that would otherwise return
 *     reasoning-only text and silently fail the salvage.
 * @param agentRole stable producer role for W0 usage attribution, e.g. {@code WorkerAgent}
 * @param stepId stable phase/step identifier inside the role, e.g. {@code worker.iteration}
 * @param retryOf optional parent step/call id when this request is a retry/failover attempt
 * @param llmExecutionContextId exact durable execution-context id used for tenant-safe usage
 *     attribution. Must be paired with {@code llmExecutionScope}; null preserves legacy callers.
 * @param llmExecutionScope tenant scope used to load {@code llmExecutionContextId}. The router and
 *     usage recorder never infer a durable context from run/source/correlation strings.
 * @param llmExecutionOperationAuthority process-local operation capability derived only from {@code
 *     context}; never serialized, logged, or reconstructed from durable request fields
 * @param strictProvider when true, transient failures are not routed to another provider
 * @param runHardCapUsd optional run-wide dollar ceiling propagated by a cost-bounded worker so the
 *     router can recheck the exact physical route after managed-AI substitution and on failover
 * @param taskCostCapUsd optional task-local dollar ceiling with the same exact-route semantics
 * @param usageLogicalCallId stable identity shared by retries/failovers of one logical LLM call;
 *     generated once when omitted and preserved by {@link #toBuilder()}
 */
@Builder(toBuilder = true)
public record LlmRequest(
    String provider,
    String model,
    List<Message> messages,
    ExecutionContext context,
    Double temperature,
    Integer maxTokens,
    List<ToolCallback> toolsOverride,
    Integer readTimeoutSeconds,
    String toolChoice,
    String agentRole,
    String stepId,
    String retryOf,
    String llmExecutionContextId,
    LlmExecutionScope llmExecutionScope,
    @JsonIgnore LlmExecutionOperationAuthority llmExecutionOperationAuthority,
    /**
     * RR-322 — optional fencing token: the {@code attempt} generation of the execution-context
     * claim this caller believes it holds. When supplied, {@code LlmExecutionDispatchGuard} rejects
     * the dispatch if the context's attempt has since advanced, so a worker whose lease was stolen
     * cannot keep spending against the same context. Null preserves every existing caller.
     */
    Integer llmExecutionAttempt,
    String usageLogicalCallId,
    boolean strictProvider,
    Double runHardCapUsd,
    Double taskCostCapUsd,
    PromptCacheStrategy promptCacheStrategy) {

  /**
   * Provider-neutral prompt-cache intent. Providers that do not support prompt caching ignore the
   * value. {@link #DEFAULT} preserves the canonical router's normal multi-turn behaviour; {@link
   * #SYSTEM_ONLY} is for metadata calls whose system prompt is stable while the user input changes
   * (for example Apply-to-Run suggestion planning).
   */
  public enum PromptCacheStrategy {
    DEFAULT,
    SYSTEM_ONLY,
    CONVERSATION_HISTORY,
    NONE
  }

  public LlmRequest {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (messages == null || messages.isEmpty())
      throw new IllegalArgumentException("messages required");
    if (context == null) throw new IllegalArgumentException("context required");
    if ((llmExecutionContextId == null) != (llmExecutionScope == null)) {
      throw new IllegalArgumentException(
          "llmExecutionContextId and llmExecutionScope must be supplied together");
    }
    String contextId = context.llmExecutionContextId();
    LlmExecutionScope contextScope = context.llmExecutionScope();
    if (llmExecutionContextId == null && contextId != null) {
      llmExecutionContextId = contextId;
      llmExecutionScope = contextScope;
    } else if (llmExecutionContextId != null
        && contextId != null
        && (!llmExecutionContextId.equals(contextId) || !llmExecutionScope.equals(contextScope))) {
      throw new IllegalArgumentException(
          "LLM request durable execution reference must match its tool context");
    }
    LlmExecutionOperationAuthority contextAuthority = context.llmExecutionOperationAuthority();
    if (llmExecutionOperationAuthority == null) {
      llmExecutionOperationAuthority = contextAuthority;
    } else if (llmExecutionOperationAuthority != contextAuthority) {
      throw new IllegalArgumentException(
          "LLM request operation authority must be derived from its tool context");
    }
    if (llmExecutionOperationAuthority != null && llmExecutionContextId == null) {
      throw new IllegalArgumentException(
          "llmExecutionOperationAuthority requires a durable execution reference");
    }
    if (usageLogicalCallId == null || usageLogicalCallId.isBlank()) {
      usageLogicalCallId = UUID.randomUUID().toString();
    }
    validateCostCap(runHardCapUsd, "runHardCapUsd");
    validateCostCap(taskCostCapUsd, "taskCostCapUsd");
    if (temperature == null) temperature = 0.0;
    if (promptCacheStrategy == null) promptCacheStrategy = PromptCacheStrategy.DEFAULT;
  }

  private static void validateCostCap(Double value, String field) {
    if (value != null && (!Double.isFinite(value) || value <= 0.0)) {
      throw new IllegalArgumentException(field + " must be finite and positive when supplied");
    }
  }

  /** Omits the opaque operation authority and prompt contents from diagnostic rendering. */
  @Override
  public String toString() {
    return "LlmRequest[provider="
        + provider
        + ", model="
        + model
        + ", messageCount="
        + messages.size()
        + ", context="
        + context
        + ", agentRole="
        + agentRole
        + ", stepId="
        + stepId
        + ", llmExecutionContextId="
        + llmExecutionContextId
        + ", llmExecutionScope="
        + llmExecutionScope
        + ", strictProvider="
        + strictProvider
        + "]";
  }
}
