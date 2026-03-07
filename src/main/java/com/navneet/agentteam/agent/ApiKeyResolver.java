package com.navneet.agentteam.agent;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.Org;
import com.navneet.agentteam.auth.repository.AppUserRepository;
import com.navneet.agentteam.auth.repository.OrgRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves per-run AI provider API keys using the multi-tenant hierarchy:
 *
 * <pre>
 *   1. User-level key  (app_user.anthropic_api_key / openai_api_key / gemini_api_key)
 *   2. Org-level key   (org.anthropic_api_key / openai_api_key / gemini_api_key)
 *   3. System key      (application.yml — represented by null in ResolvedKeys)
 * </pre>
 *
 * Respects org-level {@code keySourceMode} and {@code enabledProviders} settings:
 * <ul>
 *   <li><b>SYSTEM</b> — force all keys to null (always use platform singletons)</li>
 *   <li><b>OWN_KEYS</b> — resolve user/org keys; null = provider unavailable (no system fallback)</li>
 *   <li><b>HYBRID</b> (default) — resolve user/org keys first, fall back to system</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyResolver {

    private final AppUserRepository userRepo;
    private final OrgRepository orgRepo;

    /**
     * Resolves API keys for the user who created the run.
     *
     * @param userId the run's {@code createdByUserId} (may be null for system-generated runs)
     * @return resolved keys; null values mean "use the system singleton bean"
     */
    public ResolvedKeys resolve(String userId) {
        String anthropic = null;
        String openAi    = null;
        String gemini    = null;
        String orgId     = null;
        String keySourceMode = "HYBRID";
        Set<String> enabledProviders = null;

        // ── Level 1: user-level keys ─────────────────────────────────────────
        if (userId != null) {
            AppUser user = userRepo.findById(userId).orElse(null);
            if (user != null) {
                anthropic = blankToNull(user.getAnthropicApiKey());
                openAi    = blankToNull(user.getOpenAiApiKey());
                gemini    = blankToNull(user.getGeminiApiKey());
                orgId     = user.getOrgId();
            }
        }

        // ── Level 2: org-level keys + policy ─────────────────────────────────
        Org org = null;
        if (orgId != null) {
            org = orgRepo.findById(orgId).orElse(null);
        }
        if (org != null) {
            keySourceMode = org.getKeySourceMode() != null ? org.getKeySourceMode() : "HYBRID";
            enabledProviders = parseProviders(org.getEnabledProviders());

            if ("SYSTEM".equals(keySourceMode)) {
                // Platform keys only — ignore any user/org keys
                anthropic = null;
                openAi    = null;
                gemini    = null;
            } else {
                // Fill in gaps from org-level keys (OWN_KEYS and HYBRID both resolve org keys)
                if (anthropic == null) anthropic = blankToNull(org.getAnthropicApiKey());
                if (openAi    == null) openAi    = blankToNull(org.getOpenAiApiKey());
                if (gemini    == null) gemini    = blankToNull(org.getGeminiApiKey());
            }
        }

        // ── Level 3: system keys (null = LlmRouter uses the system singleton bean) ─
        ResolvedKeys keys = new ResolvedKeys(anthropic, openAi, gemini, keySourceMode, enabledProviders);
        log.debug("[ApiKeyResolver] userId={} orgId={} mode={} providers={} → anthropic={} openai={} gemini={}",
                userId, orgId, keySourceMode, enabledProviders,
                anthropic != null, openAi != null, gemini != null);
        return keys;
    }

    /**
     * Pre-flight validation: in OWN_KEYS mode, at least one enabled provider must have a key.
     * Called before submitting a run so the user gets an immediate 400 instead of a runtime failure.
     *
     * @throws ResponseStatusException 400 if no keys are available for any enabled provider
     */
    public void validateKeyAvailability(String userId) {
        ResolvedKeys keys = resolve(userId);
        if (!"OWN_KEYS".equals(keys.keySourceMode())) return;

        boolean hasAny = false;
        if (keys.isProviderAllowed("anthropic") && keys.anthropicKey() != null) hasAny = true;
        if (keys.isProviderAllowed("openai")    && keys.openAiKey()    != null) hasAny = true;
        if (keys.isProviderAllowed("google")    && keys.geminiKey()    != null) hasAny = true;

        if (!hasAny) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Your org is in OWN_KEYS mode but no API keys are configured for any enabled provider. " +
                    "Add keys in Settings → AI Keys, or ask an admin to change the key source mode.");
        }
    }

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.strip() : null;
    }

    private static Set<String> parseProviders(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
