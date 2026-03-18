package com.navneet.executespec.config;

/**
 * Single source of truth for all LLM model name strings.
 * Every agent, router, and config class must reference these constants
 * instead of hardcoding model name strings.
 */
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

    // ── Google ────────────────────────────────────────────────
    public static final String GEMINI_ARCHITECT = "gemini-3.1-pro-preview-customtools";
    public static final String GEMINI_CAPABLE   = "gemini-3.1-flash-lite-preview";
    public static final String GEMINI_UTILITY   = "gemini-3-flash-preview";

    // ── OpenAI ────────────────────────────────────────────────
    public static final String GPT4O      = "gpt-4o";
    public static final String GPT4O_MINI = "gpt-4o-mini";
}
