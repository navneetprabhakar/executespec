package com.navneet.executespec.codeintel;

import java.util.List;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Shared LLM dispatch for the architecture-doc generators ({@link RepoArchitectureGenerator} +
 * {@link ArchitectureDocGenerator}).
 *
 * <p><b>Why this exists (RL-2026-06-002).</b> Architecture generation must use the same canonical
 * provider transport as the worker so a project pinned to any supported provider keeps one
 * auditable routing, key-resolution, retry, and cost-accounting path. The shared dispatch seam
 * keeps the generators consistent while preserving their no-tool text contract.
 *
 * <p>No tools are bound ({@code toolsOverride=[]}) — arch-gen is a single prompt→text completion. A
 * synthetic {@link ExecutionContext} (workspace {@code /tmp}, never touched since no tool runs)
 * carries the cost/key-lookup id; an unknown run id falls through to PLATFORM keys.
 */
final class ArchLlmDispatch {

  /**
   * DeepSeek max-output is 16384; stay just under it. Arch docs are ~3k output tokens in practice.
   */
  private static final int MAX_OUTPUT_TOKENS = 16_000;

  private ArchLlmDispatch() {}

  static ChatResponse dispatch(
      LlmRouter llmRouter,
      ResolvedModel resolved,
      List<Message> messages,
      String source,
      String label) {
    return llmRouter.callText(
        resolved.providerName(),
        resolved.getModel(),
        messages,
        source,
        "ArchLlmDispatch",
        label,
        MAX_OUTPUT_TOKENS);
  }
}
