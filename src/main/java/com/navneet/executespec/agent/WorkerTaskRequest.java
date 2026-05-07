package com.navneet.executespec.agent;

import com.navneet.executespec.tool.ExecutionContext;

import lombok.Builder;

/**
 * Input to {@link WorkerAgent#run(WorkerTaskRequest)}.
 *
 * <p>The worker is provider-agnostic: it threads the supplied prompts through {@link LlmRouter}
 * which dispatches based on {@link #provider()}.
 *
 * @param provider {@code "anthropic" | "openai" | "gemini"}
 * @param model explicit model to use (caller's responsibility — tier resolution happens upstream of
 *     the worker)
 * @param systemPrompt system message — typically the worker prompt template with task constraints
 *     injected
 * @param userPrompt user message — typically the task description
 * @param context per-task execution context (workspace + ids)
 * @param maxIterations max LLM calls; 0/null defaults to {@link WorkerAgent#DEFAULT_MAX_ITER}
 * @param temperature sampling temperature; null defaults to 0.0
 * @param maxTokensPerCall max output tokens per LLM call; null defaults per-provider
 * @param stagnationWarnAfter optional override for the stagnation-warn threshold (consecutive
 *     non-write iters before injecting a one-shot PROGRESS CHECK). When null, falls through to
 *     {@link WorkerAgent#STAGNATION_WARN_AFTER_N}. Use to give complex (HIGH-complexity)
 *     reverse-engineering tasks more headroom for legitimate read+plan iterations before the
 *     warning fires.
 * @param stagnationForceAfter optional override for the stagnation-force-terminate threshold. When
 *     null, falls through to {@link WorkerAgent#STAGNATION_FORCE_AFTER_N}. Should be ≥
 *     stagnationWarnAfter + 4 so the worker has at least 4 iterations after the warn to recover.
 */
@Builder
public record WorkerTaskRequest(
    String provider,
    String model,
    String systemPrompt,
    String userPrompt,
    ExecutionContext context,
    Integer maxIterations,
    Double temperature,
    Integer maxTokensPerCall,
    Integer stagnationWarnAfter,
    Integer stagnationForceAfter) {

  public WorkerTaskRequest {
    if (provider == null || provider.isBlank())
      throw new IllegalArgumentException("provider required");
    if (model == null || model.isBlank()) throw new IllegalArgumentException("model required");
    if (userPrompt == null || userPrompt.isBlank())
      throw new IllegalArgumentException("userPrompt required");
    if (context == null) throw new IllegalArgumentException("context required");
  }
}
