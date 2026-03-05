package com.navneet.agentteam.agent;

/**
 * Per-run resolved API keys after applying the multi-tenant hierarchy:
 *   user key → org key → system key (application.yml).
 *
 * A null value for any field means "use the system singleton bean" for that provider.
 * Populated once at run-start by {@link ApiKeyResolver} and stored in {@link RunKeyStore}
 * so that agents do not need any signature changes.
 */
public record ResolvedKeys(
        /** Anthropic API key to use, or null to fall back to the system bean. */
        String anthropicKey,
        /** OpenAI API key to use, or null to fall back to the system bean. */
        String openAiKey,
        /** Google Gemini API key to use, or null to fall back to the system bean. */
        String geminiKey
) {
    /** Sentinel: all three providers use the system-configured singleton beans. */
    public static final ResolvedKeys SYSTEM_ONLY = new ResolvedKeys(null, null, null);
}
