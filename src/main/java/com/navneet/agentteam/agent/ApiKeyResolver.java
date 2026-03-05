package com.navneet.agentteam.agent;

import com.navneet.agentteam.auth.entity.AppUser;
import com.navneet.agentteam.auth.entity.Org;
import com.navneet.agentteam.auth.repository.AppUserRepository;
import com.navneet.agentteam.auth.repository.OrgRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves per-run AI provider API keys using the multi-tenant hierarchy:
 *
 * <pre>
 *   1. User-level key  (app_user.anthropic_api_key / openai_api_key / gemini_api_key)
 *   2. Org-level key   (org.anthropic_api_key / openai_api_key / gemini_api_key)
 *   3. System key      (application.yml — represented by null in ResolvedKeys)
 * </pre>
 *
 * Each provider key is resolved independently, so a user can override Anthropic only
 * while inheriting the org's OpenAI key and falling back to the system Gemini key.
 *
 * <p>The org is derived from the user's {@code orgId} field — no schema change needed
 * on {@code pipeline_run}.
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

        // ── Level 2: org-level keys (fill in any gaps) ───────────────────────
        if (orgId != null && (anthropic == null || openAi == null || gemini == null)) {
            Org org = orgRepo.findById(orgId).orElse(null);
            if (org != null) {
                if (anthropic == null) anthropic = blankToNull(org.getAnthropicApiKey());
                if (openAi    == null) openAi    = blankToNull(org.getOpenAiApiKey());
                if (gemini    == null) gemini    = blankToNull(org.getGeminiApiKey());
            }
        }

        // ── Level 3: system keys (null = LlmRouter uses the system singleton bean) ─
        ResolvedKeys keys = new ResolvedKeys(anthropic, openAi, gemini);
        log.debug("[ApiKeyResolver] userId={} orgId={} → anthropic={} openai={} gemini={}",
                userId, orgId,
                anthropic != null, openAi != null, gemini != null);
        return keys;
    }

    private static String blankToNull(String s) {
        return (s != null && !s.isBlank()) ? s.strip() : null;
    }
}
