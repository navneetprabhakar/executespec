package com.navneet.executespec.router;

/**
 * Capability tier for model selection.
 * FAST: cheap, fast, good for boilerplate (Haiku / GPT-4o-mini)
 * CAPABLE: strong reasoning for complex tasks (Sonnet / GPT-4o)
 * POWER: maximum capability, expensive, manual trigger only (Opus / GPT-4o)
 */
public enum ModelTier {
    FAST,
    CAPABLE,
    POWER
}
