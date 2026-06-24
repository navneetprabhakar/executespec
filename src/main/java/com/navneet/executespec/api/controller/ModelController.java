package com.navneet.executespec.api.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelAccessService;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * Exposes the model matrix to the frontend so model/provider dropdowns stay in sync with {@code
 * application.yml} configuration.
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

  private final ModelMatrixProperties matrixProps;
  private final ModelRegistry modelRegistry;
  private final ModelAccessService modelAccess;

  // Task 15 (2026-05-01 fix list): post-V120 the canonical provider key
  // is "gemini" (was "google" pre-migration); the label map lacked the
  // new key so the New Run modal rendered "gemini" lowercase next to
  // "Anthropic" / "OpenAI". Both keys are mapped to "Gemini" until the
  // legacy "google" alias is fully retired.
  //
  // Provider-level labels stay as a small in-class map because there are only 3 providers and
  // they don't otherwise live in the YAML matrix. Model-level labels were moved to
  // model-matrix.models.<id>.display-label per the 2026-05-06 single-source consolidation.
  private static final Map<String, String> PROVIDER_LABELS =
      Map.of(
          "anthropic", "Anthropic",
          "gemini", "Gemini",
          "google", "Gemini",
          "openai", "OpenAI",
          // 2026-05-28 (RL-2026-05-052) — register the Enterprise Beta providers' display labels so
          // the model-selector dropdown doesn't render the raw lowercase enum key. The provider
          // entries themselves auto-appear via matrixProps.getProviders() iteration above; without
          // these labels the New Run modal would show raw lowercase enum keys.
          "openrouter", "OpenRouter",
          "deepseek", "DeepSeek",
          "fireworks", "Fireworks");

  @GetMapping("/available")
  public ResponseEntity<AvailableModelsResponse> available() {
    List<ProviderEntry> providers = new ArrayList<>();

    matrixProps
        .getProviders()
        .forEach(
            (providerKey, models) -> {
              if (ModelAccessService.isDisabledProvider(providerKey)) {
                return;
              }
              List<ModelEntry> modelEntries = new ArrayList<>();

              // PLANNING tier
              if (models.getPlanning() != null) {
                modelEntries.add(
                    new ModelEntry(
                        models.getPlanning(),
                        "PLANNING",
                        modelRegistry.displayLabelFor(models.getPlanning())));
              }
              // WORKHORSE tier
              if (models.getWorkhorse() != null) {
                String workhorseId = models.getWorkhorse();
                // Skip duplicate if workhorse == planning (e.g. OpenAI uses gpt-5.4 for both)
                boolean alreadyListed =
                    modelEntries.stream().anyMatch(m -> m.id().equals(workhorseId));
                if (!alreadyListed) {
                  modelEntries.add(
                      new ModelEntry(
                          workhorseId, "WORKHORSE", modelRegistry.displayLabelFor(workhorseId)));
                }
              }

              String tag = "";
              try {
                tag = LlmProvider.valueOf(providerKey.toUpperCase()).getTag();
              } catch (IllegalArgumentException ignored) {
              }

              providers.add(
                  new ProviderEntry(
                      providerKey.toUpperCase(),
                      PROVIDER_LABELS.getOrDefault(providerKey, providerKey),
                      tag,
                      modelEntries));
            });

    return ResponseEntity.ok(new AvailableModelsResponse(providers));
  }

  /**
   * The catalog as seen by the authenticated user: every model flagged {@code available} against
   * the caller's tier. The whole list is returned so the client picker can show locked models too;
   * the free tier gets only its provider's models marked available (others are selectable in the UI
   * but rejected at run/session submit).
   */
  @GetMapping("/me")
  public ResponseEntity<List<UserModel>> myModels(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    String userId = userDetails.getUser().getId();
    List<UserModel> out = new ArrayList<>();
    // Enumerate the full configured catalog (every entry under model-matrix.models), not just each
    // provider's planning/workhorse/lightweight slots — otherwise models not wired into a tier slot
    // (e.g. the OpenRouter coding models) never reach the picker.
    modelRegistry
        .allModels()
        .forEach(
            (id, cfg) -> {
              String provider =
                  cfg.getProvider() != null ? cfg.getProvider().toUpperCase() : "OTHER";
              out.add(
                  new UserModel(
                      id,
                      provider,
                      modelRegistry.displayLabelFor(id),
                      modelAccess.minTierFor(id),
                      modelAccess.isAllowed(userId, id)));
            });
    out.sort(Comparator.comparing(UserModel::provider).thenComparing(UserModel::label));
    return ResponseEntity.ok(out);
  }

  public record AvailableModelsResponse(List<ProviderEntry> providers) {}

  public record ProviderEntry(String id, String label, String tag, List<ModelEntry> models) {}

  public record ModelEntry(String id, String tier, String label) {}

  public record UserModel(
      String id, String provider, String label, String minTier, boolean available) {}
}
