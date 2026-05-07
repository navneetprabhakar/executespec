package com.navneet.executespec.batch;

import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Per-task result returned from a provider batch API.
 *
 * <p>{@link #assistantMessage} carries the LLM's first-turn assistant output (may include {@code
 * completeTask} or other tool calls). The resume service dispatches tool calls locally and — if
 * {@code completeTask} wasn't hit — hands the accumulated history to {@code
 * WorkerAgent.resumeFromBatch(...)} for realtime continuation.
 */
public record BatchTaskResult(
    String taskId,
    AssistantMessage assistantMessage,
    int inputTokens,
    int outputTokens,
    String errorMessage) {

  public boolean success() {
    return errorMessage == null && assistantMessage != null;
  }
}
