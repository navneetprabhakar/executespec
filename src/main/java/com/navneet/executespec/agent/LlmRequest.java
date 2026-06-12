package com.navneet.executespec.agent;

import java.util.List;

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
 *     their default if null. Currently plumbed only into Gemini (where the default 60s read-timeout
 *     trips up large single-shot OutlinePlanner calls on gemini-3-pro-preview). Anthropic + OpenAI
 *     clients ignore the field today — Spring AI manages their HTTP timeouts.
 * @param toolChoice optional OpenAI-wire {@code tool_choice} override ({@code "auto"} | {@code
 *     "required"} | {@code "none"}). Null ⇒ the client's default ({@code "auto"}). Honoured only by
 *     the OpenAI-wire clients (OpenAI / DeepSeek / OpenRouter via {@code OpenAiNativeToolClient});
 *     Anthropic (Spring AI) + Gemini ignore it today. Set to {@code "required"} by the WorkerAgent
 *     Lever-B salvage call (RL-2026-05-059) to force a tool call out of a model that would
 *     otherwise return reasoning-only text and silently fail the salvage.
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
    String toolChoice) {

  public LlmRequest {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (messages == null || messages.isEmpty())
      throw new IllegalArgumentException("messages required");
    if (context == null) throw new IllegalArgumentException("context required");
    if (temperature == null) temperature = 0.0;
  }
}
