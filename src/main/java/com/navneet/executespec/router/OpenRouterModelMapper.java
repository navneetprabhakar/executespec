package com.navneet.executespec.router;

import java.util.Map;

/**
 * Maps an ExecuteSpec model-matrix id to its OpenRouter slug ({@code vendor/model}).
 *
 * <p>OpenRouter exposes the same models as our matrix, but under vendor-prefixed, dot-versioned
 * slugs with no date suffix. The mapping below was verified against the live catalogue
 * (https://openrouter.ai/api/v1/models), most recently on 2026-07-24 — every entry has an exact
 * account-visible OpenRouter slug and advertises function tools.
 *
 * <p>The allow-list is <strong>explicit</strong>: a model id with no verified OpenRouter equivalent
 * throws rather than silently sending an unverified slug.
 */
public final class OpenRouterModelMapper {

  private OpenRouterModelMapper() {}

  private static final Map<String, String> SLUGS =
      Map.ofEntries(
          Map.entry("claude-opus-4-6", "anthropic/claude-opus-4.6"),
          Map.entry("claude-opus-4-7", "anthropic/claude-opus-4.7"),
          Map.entry("claude-opus-4-8", "anthropic/claude-opus-4.8"),
          Map.entry("claude-sonnet-4-6", "anthropic/claude-sonnet-4.6"),
          Map.entry("claude-sonnet-5", "anthropic/claude-sonnet-5"),
          Map.entry("claude-opus-5", "anthropic/claude-opus-5"),
          Map.entry("claude-fable-5", "anthropic/claude-fable-5"),
          Map.entry("claude-haiku-4-5-20251001", "anthropic/claude-haiku-4.5"),
          Map.entry("gpt-5.4", "openai/gpt-5.4"),
          Map.entry("gpt-5.4-mini", "openai/gpt-5.4-mini"),
          Map.entry("gpt-5.5", "openai/gpt-5.5"),
          Map.entry("gpt-5.6-sol", "openai/gpt-5.6-sol"),
          Map.entry("gpt-5.6-terra", "openai/gpt-5.6-terra"),
          Map.entry("gpt-5.6-luna", "openai/gpt-5.6-luna"),
          Map.entry("gemini-2.5-flash", "google/gemini-2.5-flash"),
          Map.entry("gemini-3.1-flash-lite", "google/gemini-3.1-flash-lite"),
          Map.entry("gemini-3.1-pro-preview", "google/gemini-3.1-pro-preview"),
          Map.entry("gemini-3.5-flash", "google/gemini-3.5-flash"),
          Map.entry("gemini-3.5-flash-lite", "google/gemini-3.5-flash-lite"),
          Map.entry("gemini-3.6-flash", "google/gemini-3.6-flash"),
          // RL-2026-05-048 — DeepSeek + Kimi (OpenRouter-only, ENTERPRISE BYOK). Slugs verified
          // against the live catalogue 2026-05-26. These have no native ExecuteSpec provider
          // client;
          // they route only via OpenRouter (OpenAI-wire-compatible through OpenAiNativeToolClient).
          Map.entry("deepseek-v4-pro", "deepseek/deepseek-v4-pro"),
          Map.entry("deepseek-v4-flash", "deepseek/deepseek-v4-flash"),
          Map.entry("kimi-k2.6", "moonshotai/kimi-k2.6"),
          // 2026-05-28 (RL-2026-05-052) — Qwen3-Coder added for the 5-provider eval cohort.
          // OpenRouter-only (no native client), ENTERPRISE BYOK. Slug per the OpenRouter catalog.
          Map.entry("qwen3-coder", "qwen/qwen3-coder"),
          // 2026-06-09 — Xiaomi MiMo v2.5 Pro for a TB 2.0 benchmark (OpenRouter-only,
          // tools-capable, 1M ctx). Slug verified vs the OpenRouter catalogue 2026-06-09.
          Map.entry("mimo-v2.5-pro", "xiaomi/mimo-v2.5-pro"),
          // 2026-06-17 — Mistral coding model (OpenRouter-only, ENTERPRISE BYOK); exposes
          // tools + tool_choice (verified vs the OpenRouter /endpoints API 2026-06-17).
          // codestral-2508 is the cheaper code model (256K ctx).
          // devstral-2 (mistralai/devstral-2512) was removed 2026-08-06: absent from the live
          // OpenRouter catalogue, so every dispatch to it 404s at transport.
          Map.entry("codestral-2508", "mistralai/codestral-2508"),
          // 2026-06-17 — Top coding-model expansion (OpenRouter, ENTERPRISE BYOK). All verified to
          // expose tools+tool_choice vs the OpenRouter /endpoints API 2026-06-17.
          // OpenRouter's default latency routing handles tool requests for these models.
          Map.entry("minimax-m3", "minimax/minimax-m3"),
          Map.entry("gpt-5.3-codex", "openai/gpt-5.3-codex"),
          Map.entry("gpt-5.1-codex-max", "openai/gpt-5.1-codex-max"),
          Map.entry("gpt-5.1-codex", "openai/gpt-5.1-codex"),
          Map.entry("gpt-5.2-codex", "openai/gpt-5.2-codex"),
          Map.entry("grok-4.20", "x-ai/grok-4.20"),
          Map.entry("glm-5.2", "z-ai/glm-5.2"),
          Map.entry("kimi-k2.7-code", "moonshotai/kimi-k2.7-code"),
          Map.entry("minimax-m2.7", "minimax/minimax-m2.7"),
          Map.entry("qwen3-coder-next", "qwen/qwen3-coder-next"),
          // 2026-07-24 catalogue refresh. These coding/agentic models are account-visible and
          // advertise tools + tool_choice; they remain selectable BYOK routes, not tier defaults.
          Map.entry("kimi-k3", "moonshotai/kimi-k3"),
          Map.entry("qwen3.7-plus", "qwen/qwen3.7-plus"),
          Map.entry("qwen3.7-max", "qwen/qwen3.7-max"),
          Map.entry("grok-4.5", "x-ai/grok-4.5"));

  /**
   * @return the OpenRouter slug for a matrix model id
   * @throws IllegalArgumentException if the id has no verified OpenRouter slug (fail-fast: never
   *     send an unverified model to OpenRouter)
   */
  public static String toSlug(String matrixModelId) {
    String slug = SLUGS.get(matrixModelId);
    if (slug == null) {
      throw new IllegalArgumentException(
          "No verified OpenRouter slug for model '"
              + matrixModelId
              + "'. Allowed matrix models: "
              + SLUGS.keySet());
    }
    return slug;
  }

  /** True when {@code matrixModelId} has a verified OpenRouter slug. */
  public static boolean isSupported(String matrixModelId) {
    return matrixModelId != null && SLUGS.containsKey(matrixModelId);
  }

  /** Immutable view of the verified matrix-id → OpenRouter-slug allow-list. */
  public static Map<String, String> allowList() {
    return SLUGS;
  }
}
