package com.navneet.executespec.router;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Maps a resolved model identifier to its provider-published input context window.
 *
 * <p>The chat surface uses this to render the context-window chip on the project chat header.
 * Driven by the actual resolved model rather than the provider, because providers ship multiple
 * models with very different windows (gpt-4o = 128k vs gpt-5.4 = 256k; sonnet-4-6 = 200k vs the
 * Anthropic 1M-context Opus variant; gemini-pro = 32k vs the flash family at 1M).
 *
 * <p>Numbers reflect the input context window each model is documented to support today
 * (2026-05-02). When a new model lands in the matrix, add it here. The prefix-based fallback keeps
 * the chip from going blank for forward-compat models — graceful degradation, not a substitute for
 * a real entry.
 */
@Component
public class ModelContextWindowMap {

  /** Generic fallback used only when the model is null AND the prefix heuristic misses. */
  public static final int FALLBACK_WINDOW = 128_000;

  private static final Map<String, Integer> WINDOWS =
      Map.ofEntries(
          // Anthropic — every Claude 4.x ships a 200k window by default. The 1M-context
          // variant of Opus is published as a separate model id (see the agent's note on
          // claude-opus-4-7-1m).
          Map.entry("claude-opus-4-6", 200_000),
          Map.entry("claude-opus-4-7", 200_000),
          Map.entry("claude-opus-4-7-1m", 1_000_000),
          Map.entry("claude-sonnet-4-6", 200_000),
          Map.entry("claude-haiku-4-5-20251001", 200_000),
          Map.entry("claude-haiku-4-5", 200_000),

          // Gemini — flash family ships a 1M window. Older gemini-pro
          // (32k) is not in the executespec matrix today; if it returns
          // we'd want a real entry, not the prefix fallback's optimistic 1M.
          Map.entry("gemini-2.5-flash", 1_000_000),
          Map.entry("gemini-3-flash-preview", 1_000_000),
          Map.entry("gemini-3-flash", 1_000_000),

          // OpenAI — gpt-5.x family ships 256k. gpt-4o (now an alias to gpt-5.4 in the
          // matrix's legacy-alias table, but still a possible resolved name on older runs)
          // shipped 128k.
          Map.entry("gpt-5.4", 256_000),
          Map.entry("gpt-5.4-mini", 256_000),
          Map.entry("gpt-4o", 128_000),
          Map.entry("gpt-4o-mini", 128_000));

  /**
   * Return the input context window for {@code model}, or a sensible default. The prefix heuristic
   * is intentionally optimistic for unknowns ("the new model is at least as big as the family's
   * previous frontier") so the chip degrades to a guess instead of a "?" — but every shipping model
   * should have an explicit entry above.
   */
  public int forModel(String model) {
    if (model == null || model.isBlank()) return FALLBACK_WINDOW;
    Integer w = WINDOWS.get(model);
    if (w != null) return w;
    if (model.startsWith("claude-opus-") && model.endsWith("-1m")) return 1_000_000;
    if (model.startsWith("claude-")) return 200_000;
    if (model.startsWith("gemini-")) return 1_000_000;
    if (model.startsWith("gpt-")) return 256_000;
    return FALLBACK_WINDOW;
  }
}
