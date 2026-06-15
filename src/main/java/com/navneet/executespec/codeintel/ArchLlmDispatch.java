package com.navneet.executespec.codeintel;

import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.agent.FallbackLlmRouter;
import com.navneet.executespec.agent.LlmRequest;
import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * Shared LLM dispatch for the architecture-doc generators ({@link RepoArchitectureGenerator} +
 * {@link ArchitectureDocGenerator}).
 *
 * <p><b>Why this exists (RL-2026-06-002).</b> Both generators historically dispatched only through
 * the legacy {@link FallbackLlmRouter}, whose provider switch handles ONLY {@code
 * anthropic|openai|gemini}. So a project pinned to {@code DEEPSEEK} (or {@code OPENROUTER}) could
 * not generate its codemap/architecture context on the SAME provider as its worker — the docs
 * silently had to come from a different (and ~25x more expensive) model. This helper routes the
 * OpenAI-wire-compatible non-legacy providers through the canonical {@link LlmRouter} (which has
 * {@code deepseek} / {@code openrouter} / {@code fireworks} dispatch cases and also records cost
 * via {@code RunCostTracker} — closing the untracked-arch-gen-cost gap), while leaving the existing
 * {@code anthropic|openai|gemini} path on {@link FallbackLlmRouter} byte-identical (zero regression
 * for current providers).
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
      FallbackLlmRouter fallbackLlmRouter,
      LlmRouter llmRouter,
      ResolvedModel resolved,
      List<Message> messages,
      String source,
      String label) {
    String prov = resolved.providerName() == null ? "" : resolved.providerName().toLowerCase();
    if (prov.equals("deepseek") || prov.equals("openrouter") || prov.equals("fireworks")) {
      return llmRouter.call(
          LlmRequest.builder()
              .provider(resolved.providerName())
              .model(resolved.getModel())
              .messages(messages)
              .context(new ExecutionContext(source, label, Path.of("/tmp")))
              .agentRole("ArchLlmDispatch")
              .stepId(label)
              .toolsOverride(List.of())
              .maxTokens(MAX_OUTPUT_TOKENS)
              .build());
    }
    return fallbackLlmRouter.callWithFallback(
        resolved.providerName(),
        resolved.getModel(),
        new Prompt(messages),
        source,
        label,
        FallbackLlmRouter.FallbackMode.IMMEDIATE,
        0,
        false);
  }
}
