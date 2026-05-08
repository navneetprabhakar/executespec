package com.navneet.executespec.agent;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
 *
 * <ul>
 *   <li><b>SYSTEM</b> — force all keys to null (always use platform singletons)
 *   <li><b>OWN_KEYS</b> — resolve user/org keys; null = provider unavailable (no system fallback)
 *   <li><b>HYBRID</b> (default) — resolve user/org keys first, fall back to system
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
    String openAi = null;
    String gemini = null;
    String orgId = null;
    String keySourceMode = "HYBRID";
    Set<String> enabledProviders = null;

    // ── Level 1: user-level keys ─────────────────────────────────────────
    if (userId != null) {
      AppUser user = userRepo.findById(userId).orElse(null);
      if (user != null) {
        anthropic = blankToNull(user.getAnthropicApiKey());
        openAi = blankToNull(user.getOpenAiApiKey());
        gemini = blankToNull(user.getGeminiApiKey());
        orgId = user.getOrgId();
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
        openAi = null;
        gemini = null;
      } else {
        // Fill in gaps from org-level keys (OWN_KEYS and HYBRID both resolve org keys)
        if (anthropic == null) anthropic = blankToNull(org.getAnthropicApiKey());
        if (openAi == null) openAi = blankToNull(org.getOpenAiApiKey());
        if (gemini == null) gemini = blankToNull(org.getGeminiApiKey());
      }
    }

    // ── Level 3: system keys (null = FallbackLlmRouter uses the system singleton bean) ─
    ResolvedKeys keys =
        new ResolvedKeys(anthropic, openAi, gemini, keySourceMode, enabledProviders);
    log.debug(
        "[ApiKeyResolver] userId={} orgId={} mode={} providers={} → anthropic={} openai={}"
            + " gemini={}",
        userId,
        orgId,
        keySourceMode,
        enabledProviders,
        anthropic != null,
        openAi != null,
        gemini != null);
    return keys;
  }

  /**
   * 2026-05-08 — true when the org's keySourceMode is OWN_KEYS, i.e. the customer is paying their
   * own LLM bill (BYOK). Used by the cost-cap path: a BYOK customer shouldn't be hard-stopped at
   * the platform's $4 default cap because the spend isn't on us. Returns false on
   * null/HYBRID/SYSTEM — HYBRID can fall back to platform keys mid-run, so we still cap; SYSTEM is
   * platform-paid by definition. Only OWN_KEYS = pure BYOK = cap exempt.
   *
   * @param userId the run's createdByUserId; null returns false (system runs use platform spend)
   * @return true iff the user's org explicitly set keySourceMode=OWN_KEYS
   */
  public boolean isOwnKeysMode(String userId) {
    if (userId == null) return false;
    AppUser user = userRepo.findById(userId).orElse(null);
    if (user == null || user.getOrgId() == null) return false;
    Org org = orgRepo.findById(user.getOrgId()).orElse(null);
    if (org == null) return false;
    return "OWN_KEYS".equals(org.getKeySourceMode());
  }

  /**
   * Pre-flight validation: in OWN_KEYS mode at least one provider must have a key. Called before
   * submitting a run so the user gets an immediate 400 instead of a runtime failure. HYBRID mode
   * (the default) silently falls back to platform keys when no user/org keys are configured.
   *
   * <p>V165 — the MAX-tier hard-require branch was removed when MAX was deprecated. TEAM customers
   * who want hard-fail-on-missing-keys behavior should set their org's keySourceMode to OWN_KEYS
   * via the admin console.
   *
   * @throws ResponseStatusException 400 if OWN_KEYS mode is on but no keys are configured
   */
  public void validateKeyAvailability(String userId) {
    ResolvedKeys keys = resolve(userId);

    if (!"OWN_KEYS".equals(keys.keySourceMode())) return;

    boolean hasAny = false;
    if (keys.anthropicKey() != null) hasAny = true;
    if (keys.openAiKey() != null) hasAny = true;
    if (keys.geminiKey() != null) hasAny = true;

    if (!hasAny) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Your org is in OWN_KEYS mode but no API keys are configured for any enabled provider. "
              + "Add keys in Settings → AI Keys, or ask an admin to change the key source mode.");
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
