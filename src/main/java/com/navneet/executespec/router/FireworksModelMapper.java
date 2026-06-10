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
          Map.entry("glm-5.1-fw", P + "glm-5p1"));

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
