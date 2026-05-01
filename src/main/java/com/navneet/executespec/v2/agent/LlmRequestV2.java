package com.navneet.executespec.v2.agent;

import java.util.List;

import com.navneet.executespec.v2.tool.ExecutionContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import lombok.Builder;

/**
 * V2 LLM request DTO.
 *
 * <p>Provider-agnostic — {@link LlmRouterV2} dispatches based on {@link #provider()} and assembles
 * provider-specific options + tool callbacks internally.
 *
 * <p>Tool registration is implicit: the router calls {@link
 * com.navneet.executespec.v2.tool.ToolRegistryV2#bind(ExecutionContext)} with the supplied {@link
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
 */
@Builder
public record LlmRequestV2(
    String provider,
    String model,
    List<Message> messages,
    ExecutionContext context,
    Double temperature,
    Integer maxTokens,
    List<ToolCallback> toolsOverride) {

  public LlmRequestV2 {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (messages == null || messages.isEmpty())
      throw new IllegalArgumentException("messages required");
    if (context == null) throw new IllegalArgumentException("context required");
    if (temperature == null) temperature = 0.0;
  }
}
