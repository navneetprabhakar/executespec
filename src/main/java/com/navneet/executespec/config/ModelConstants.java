package com.navneet.executespec.config;

/**
 * @deprecated All model name lookups are now config-driven via
 * {@link com.navneet.executespec.router.ModelRegistry} backed by
 * {@code model-matrix.models} in {@code application.yml}.
 * <p>
 * This class is retained only for backward compatibility with test code
 * and will be removed in a future release.
 *
 * @see com.navneet.executespec.router.ModelRegistry
 */
@Deprecated(forRemoval = true)
public final class ModelConstants {

    private ModelConstants() {}

    // ── Anthropic ─────────────────────────────────────────────
    public static final String OPUS    = "claude-opus-4-6";
    public static final String SONNET  = "claude-sonnet-4-6";
    public static final String HAIKU   = "claude-haiku-4-5-20251001";

    // Legacy aliases (for MODEL_CHAIN lookups from user-submitted model names)
    public static final String OPUS_LEGACY   = "claude-opus-4-5";
    public static final String SONNET_LEGACY = "claude-sonnet-4-5";
    public static final String HAIKU_LEGACY  = "claude-haiku-4-5";

    // ── Gemini (preview models — fallback chain handles unavailability) ──
    public static final String GEMINI_PRO   = "gemini-3.1-pro-preview";
    public static final String GEMINI_FLASH = "gemini-3-flash-preview";

    // Legacy aliases for Gemini (preview model strings still in MODEL_CHAIN for backward compat)
    public static final String GEMINI_ARCHITECT = GEMINI_PRO;
    public static final String GEMINI_CAPABLE   = GEMINI_PRO;
    public static final String GEMINI_UTILITY   = GEMINI_FLASH;

    // ── OpenAI ────────────────────────────────────────────────
    public static final String GPT5_4      = "gpt-5.4";
    public static final String GPT5_4_MINI = "gpt-5.4-mini";

    /** @deprecated Use {@link #GPT5_4} — kept for MODEL_CHAIN backward compatibility */
    @Deprecated
    public static final String GPT4O       = "gpt-4o";
    /** @deprecated Use {@link #GPT5_4_MINI} — kept for MODEL_CHAIN backward compatibility */
    @Deprecated
    public static final String GPT4O_MINI  = "gpt-4o-mini";
}
