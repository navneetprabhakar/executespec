package com.navneet.executespec.router;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * F3 — per-org overrides of the {@code application.yml model-matrix}. A BYOK/TEAM/ENTERPRISE org
 * admin can pin a specific model for any {@code (provider, tier)} cell (e.g. {@code
 * anthropic:planning → claude-opus-4-6}); unset cells fall back to the YAML default. Overrides are
 * stored sparsely as a JSON map on {@link Org#getModelOverridesJson()} and bound to the run thread
 * via {@link RunModelOverrides} at pipeline start.
 *
 * <p>Validation is strict on write (reject the whole request if any cell is malformed) and lenient
 * on read (skip a now-invalid cell rather than break a run — e.g. if a model was retired from the
 * matrix after the override was saved).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgModelOverrideService {

  private final OrgRepository orgRepo;
  private final ModelRegistry modelRegistry;
  private final ObjectMapper objectMapper;

  /** RL-2026-05-041 (audit #11) — model-matrix overrides are a compliance-relevant admin action. */
  private final ActivityAuditService auditService;

  /** Short-TTL cache so the per-run binding doesn't hit the DB on every run start. */
  private final Cache<String, Map<String, String>> cache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).maximumSize(10_000).build();

  /**
   * The validated override map for a run, keyed by {@code "<provider>:<tier>"}. Cached; lenient
   * (drops cells that no longer validate). Empty when the org has no overrides.
   */
  public Map<String, String> overridesForRun(String orgId) {
    if (orgId == null || orgId.isBlank()) {
      return Map.of();
    }
    return cache.get(orgId, this::loadAndValidate);
  }

  private Map<String, String> loadAndValidate(String orgId) {
    Org org = orgRepo.findById(orgId).orElse(null);
    if (org == null) {
      return Map.of();
    }
    Map<String, String> parsed = parse(org.getModelOverridesJson());
    Map<String, String> valid = new LinkedHashMap<>();
    for (var e : parsed.entrySet()) {
      try {
        String canonical = validateCell(e.getKey(), e.getValue());
        valid.put(e.getKey().toLowerCase(), canonical);
      } catch (RuntimeException ex) {
        log.warn(
            "[OrgModelOverride] org {} dropping invalid override cell {}={} at run time: {}",
            orgId,
            e.getKey(),
            e.getValue(),
            ex.getMessage());
      }
    }
    return valid;
  }

  /** Raw stored overrides for the settings UI (org has already validated them on write). */
  public Map<String, String> currentOverrides(String orgId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    return parse(org.getModelOverridesJson());
  }

  /**
   * Replaces the org's full override set (sparse — only the cells the admin wants overridden). An
   * empty map clears all overrides. BYOK/TEAM/ENTERPRISE-only; every cell is validated against the
   * registered model matrix.
   */
  public void updateOverrides(String orgId, Map<String, String> overrides, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    if (!canEditMatrix(org.getTier())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Per-org model overrides require BYOK, Team, or Enterprise");
    }
    Map<String, String> clean = new LinkedHashMap<>();
    if (overrides != null) {
      for (var e : overrides.entrySet()) {
        if (e.getValue() == null || e.getValue().isBlank()) {
          continue; // omitted/blank cell = "use the default"
        }
        String canonical = validateCell(e.getKey(), e.getValue());
        clean.put(e.getKey().toLowerCase(), canonical);
      }
    }
    try {
      org.setModelOverridesJson(clean.isEmpty() ? null : objectMapper.writeValueAsString(clean));
    } catch (Exception ex) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to serialize model overrides");
    }
    org.setUpdatedAt(Instant.now());
    orgRepo.save(org);
    cache.invalidate(orgId);
    // RL-2026-05-041 (audit #11) — immutable audit row: who changed which (provider,tier)→model
    // cells. ENTERPRISE-only knob that affects every run's cost + behavior, so it belongs in the
    // compliance trail. Best-effort: never fail the update on an audit-write hiccup.
    try {
      auditService.record(
          ActivityAudit.builder()
              .id(java.util.UUID.randomUUID().toString())
              .eventType("ORG_MODEL_MATRIX_CHANGED")
              .entityType("ORG")
              .entityId(orgId)
              .orgId(orgId)
              .actorId(actorId)
              .newValue(clean.isEmpty() ? "(cleared all model overrides)" : clean.toString())
              .createdAt(Instant.now())
              .build());
    } catch (Exception ex) {
      log.warn(
          "[OrgModelOverrideService] audit-write failed for org {}: {}", orgId, ex.getMessage());
    }
    log.info(
        "[OrgModelOverride] org {} set {} override cell(s) by {}", orgId, clean.size(), actorId);
  }

  /**
   * Validates one {@code "<provider>:<tier>" -> model} cell. Returns the canonicalised model id.
   * Throws 400 on any malformed provider/tier/model.
   */
  private String validateCell(String key, String model) {
    if (key == null || !key.contains(":")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Override key must be '<provider>:<tier>': " + key);
    }
    String[] parts = key.split(":", 2);
    LlmProvider provider;
    AgentTier tier;
    try {
      provider = LlmProvider.valueOf(parts[0].trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + parts[0]);
    }
    try {
      tier = AgentTier.valueOf(parts[1].trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tier: " + parts[1]);
    }
    String canonical = modelRegistry.canonicalize(model.trim());
    if (!modelRegistry.isKnownModel(canonical)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown model: " + model);
    }
    if (provider == LlmProvider.OPENROUTER) {
      // OpenRouter fronts every vendor, but only the verified allow-list is routable.
      if (!OpenRouterModelMapper.isSupported(canonical)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Model not routable via OpenRouter: " + model);
      }
    } else {
      String owner = modelRegistry.providerFor(canonical);
      if (owner == null || !owner.equalsIgnoreCase(provider.name())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Model " + model + " is not an " + provider.name().toLowerCase() + " model");
      }
    }
    return canonical;
  }

  private Map<String, String> parse(String json) {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, String> m = objectMapper.readValue(json, new TypeReference<>() {});
      return m != null ? m : Map.of();
    } catch (Exception ex) {
      log.warn(
          "[OrgModelOverride] failed to parse override json, treating as empty: {}",
          ex.getMessage());
      return Map.of();
    }
  }

  public boolean canEditMatrix(String tier) {
    if (tier == null) return false;
    String t = tier.trim().toUpperCase();
    return "BYOK".equals(t) || "TEAM".equals(t) || "ENTERPRISE".equals(t);
  }
}
