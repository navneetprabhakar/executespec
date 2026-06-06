package com.navneet.executespec.agent;

import java.util.List;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Distils a free-form user request into a concise 5-9 word imperative title. Used by the
 * SINGLE_SHOT bypass path so run titles shown in project views read naturally instead of echoing
 * the raw request. Uses the LIGHTWEIGHT tier via the V1 {@link FallbackLlmRouter} (same pattern as
 * {@code RunLedgerAgent}) — cheap + fast + no ExecutionContext plumbing needed.
 *
 * <p>All failures return {@code null} so callers can fall back to deterministic truncation. Title
 * generation is a nice-to-have, never a hard dependency.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunTitleSummarizer {

  private static final String SYSTEM_PROMPT =
      """
      Distil the user's task request into a concise run title.

      Rules:
      - 5 to 9 words, imperative mood ("Add ...", "Fix ...", "Refactor ...").
      - No trailing period, no quotes, no markdown.
      - Keep concrete nouns (endpoint names, repo names, file paths) if they fit.
      - If the request is one line already, tighten it; don't expand.
      - Output the title ONLY — nothing else, no prefix, no explanation.
      """;

  private final FallbackLlmRouter fallbackLlmRouter;
  private final ModelResolver modelResolver;

  /**
   * @param spec the raw user request
   * @param provider the project/run's LLM provider; falls back to OPENAI
   * @param runId for logging/correlation in the FallbackLlmRouter
   * @return a tightened title, or {@code null} if summarization failed
   */
  public String summarize(String spec, LlmProvider provider, String runId) {
    if (spec == null || spec.isBlank()) return null;
    try {
      LlmProvider effective = provider != null ? provider : LlmProvider.OPENAI;
      ResolvedModel resolved = modelResolver.resolve("RunTitleSummarizer", effective);
      Prompt prompt = new Prompt(List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(spec)));
      ChatResponse response =
          fallbackLlmRouter.callWithFallback(
              resolved.providerName(),
              resolved.getModel(),
              prompt,
              runId == null ? "title-" + spec.hashCode() : runId,
              "run-title",
              FallbackLlmRouter.FallbackMode.IMMEDIATE,
              0,
              false);
      String text = response.getResult().getOutput().getText();
      return clean(text);
    } catch (Exception e) {
      log.debug("RunTitleSummarizer failed (will fall back): {}", e.getMessage());
      return null;
    }
  }

  private static String clean(String raw) {
    if (raw == null) return null;
    String s = raw.trim();
    // Strip wrapping quotes if the model added them.
    if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
      s = s.substring(1, s.length() - 1).trim();
    }
    // One line only.
    int nl = s.indexOf('\n');
    if (nl > 0) s = s.substring(0, nl).trim();
    while (!s.isEmpty() && ".,;:".indexOf(s.charAt(s.length() - 1)) >= 0) {
      s = s.substring(0, s.length() - 1);
    }
    if (s.length() > 80) s = s.substring(0, 79) + "…";
    return s.isEmpty() ? null : s;
  }
}
