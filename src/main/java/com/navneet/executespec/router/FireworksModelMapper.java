package com.navneet.executespec.router;

import java.util.Map;

/**
 * Maps an ExecuteSpec model-matrix id to its Fireworks AI model slug ({@code
 * accounts/fireworks/models/…}).
 *
 * <p>Fireworks serves the open-weight Chinese models (DeepSeek / Kimi / GLM / Qwen) on US GPUs —
 * the fast-inference route added 2026-06-09 to test an alternative to OpenRouter's slow default
 * Chinese-model routing. OpenAI-wire-compatible, so it routes through {@link
 * com.navneet.executespec.agent.OpenAiNativeToolClient} via {@code LlmRouter.callFireworks}.
 *
 * <p>The allow-list is explicit (an unmapped id throws rather than sending an unverified slug), but
 * any id that is already a full Fireworks slug passes straight through — handy for ad-hoc testing.
 * Slugs below are the well-known catalogue ids; verify against {@code
 * https://api.fireworks.ai/inference/v1/models} and refine if one 404s post-smoke.
 */
public final class FireworksModelMapper {

  private FireworksModelMapper() {}

  private static final String P = "accounts/fireworks/models/";

  private static final Map<String, String> SLUGS =
      Map.ofEntries(
          Map.entry("deepseek-v4-pro-fw", P + "deepseek-v4-pro"),
          Map.entry("deepseek-v4-flash-fw", P + "deepseek-v4-flash"),
          Map.entry("kimi-k2p6-fw", P + "kimi-k2p6"),
          Map.entry("glm-5.1-fw", P + "glm-5p1"),
          // 2026-06-18 — Fireworks-available subset of the OpenRouter coding-model expansion.
          // Every slug below was live-probed on api.fireworks.ai (2026-06-18): callable AND
          // tool_choice=required emits a valid tool call (the worker bar). The other OpenRouter
          // coding models (devstral-2, codestral-2508, qwen3-coder*, grok-4.20, gpt-5.3-codex,
          // mimo-v2.5-pro) 404 on Fireworks and are deliberately NOT mapped here.
          Map.entry("glm-5.2-fw", P + "glm-5p2"),
          Map.entry("minimax-m3-fw", P + "minimax-m3"),
          Map.entry("minimax-m2.7-fw", P + "minimax-m2p7"),
          Map.entry("kimi-k2.7-code-fw", P + "kimi-k2p7-code"));

  /**
   * @return the Fireworks slug for a matrix model id; an id that already looks like a full
   *     Fireworks slug ({@code accounts/fireworks/models/…}) passes straight through.
   * @throws IllegalArgumentException if the id has no verified Fireworks slug
   */
  public static String toSlug(String matrixModelId) {
    if (matrixModelId != null && matrixModelId.startsWith(P)) {
      return matrixModelId;
    }
    String slug = SLUGS.get(matrixModelId);
    if (slug == null) {
      throw new IllegalArgumentException(
          "No verified Fireworks slug for model '"
              + matrixModelId
              + "'. Allowed: "
              + SLUGS.keySet());
    }
    return slug;
  }

  /** True when {@code matrixModelId} resolves to a Fireworks slug (allow-list or full-slug). */
  public static boolean isSupported(String matrixModelId) {
    return matrixModelId != null
        && (matrixModelId.startsWith(P) || SLUGS.containsKey(matrixModelId));
  }

  /** Immutable view of the verified matrix-id → Fireworks-slug allow-list. */
  public static Map<String, String> allowList() {
    return SLUGS;
  }
}
