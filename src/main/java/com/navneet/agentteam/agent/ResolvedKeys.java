package com.navneet.agentteam.agent;

import java.util.Set;

/**
 * Per-run resolved API keys after applying the multi-tenant hierarchy:
 *   user key → org key → system key (application.yml).
 *
 * A null value for any field means "use the system singleton bean" for that provider.
 * Populated once at run-start by {@link ApiKeyResolver} and stored in {@link RunKeyStore}
 * so that agents do not need any signature changes.
 *
 * @param keySourceMode     SYSTEM | OWN_KEYS | HYBRID — controls fallback behaviour
 * @param enabledProviders  null = all providers allowed; non-null = only these (lowercase)
 */
public record ResolvedKeys(
        /** Anthropic API key to use, or null to fall back to the system bean. */
        String anthropicKey,
        /** OpenAI API key to use, or null to fall back to the system bean. */
        String openAiKey,
        /** Google Gemini API key to use, or null to fall back to the system bean. */
        String geminiKey,
        /** Key source mode from the org: SYSTEM | OWN_KEYS | HYBRID. */
        String keySourceMode,
        /** Enabled providers (lowercase). Null = all providers allowed. */
        Set<String> enabledProviders
) {
    /** Sentinel: all three providers use the system-configured singleton beans. */
    public static final ResolvedKeys SYSTEM_ONLY =
            new ResolvedKeys(null, null, null, "SYSTEM", null);

    /** Backward-compatible constructor: defaults to HYBRID mode, all providers. */
    public ResolvedKeys(String anthropicKey, String openAiKey, String geminiKey) {
        this(anthropicKey, openAiKey, geminiKey, "HYBRID", null);
    }

    /** Returns true if the given provider (lowercase) is allowed by org policy. */
    public boolean isProviderAllowed(String provider) {
        if (enabledProviders == null) return true;
        return enabledProviders.contains(provider.toLowerCase());
    }
}
