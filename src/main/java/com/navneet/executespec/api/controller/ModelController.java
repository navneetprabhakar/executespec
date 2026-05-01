package com.navneet.executespec.api.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixProperties;

import org.springframework.http.ResponseEntity;
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

  // Task 15 (2026-05-01 fix list): post-V120 the canonical provider key
  // is "gemini" (was "google" pre-migration); the label map lacked the
  // new key so the New Run modal rendered "gemini" lowercase next to
  // "Anthropic" / "OpenAI". Both keys are mapped to "Gemini" until the
  // legacy "google" alias is fully retired.
  private static final Map<String, String> PROVIDER_LABELS =
      Map.of(
          "anthropic", "Anthropic",
          "gemini", "Gemini",
          "google", "Gemini",
          "openai", "OpenAI");

  private static final Map<String, String> MODEL_LABELS =
      Map.ofEntries(
          Map.entry("claude-opus-4-6", "Claude Opus 4.6"),
          Map.entry("claude-sonnet-4-6", "Claude Sonnet 4.6"),
          Map.entry("claude-haiku-4-5-20251001", "Claude Haiku 4.5"),
          Map.entry("gemini-3.1-pro-preview", "Gemini 3.1 Pro"),
          Map.entry("gemini-3-flash-preview", "Gemini 3 Flash Preview"),
          Map.entry("gemini-2.5-flash", "Gemini 2.5 Flash"),
          Map.entry("gpt-5.4", "GPT-5.4"),
          Map.entry("gpt-5.4-mini", "GPT-5.4 Mini"));

  @GetMapping("/available")
  public ResponseEntity<AvailableModelsResponse> available() {
    List<ProviderEntry> providers = new ArrayList<>();

    matrixProps
        .getProviders()
        .forEach(
            (providerKey, models) -> {
              List<ModelEntry> modelEntries = new ArrayList<>();

              // PLANNING tier
              if (models.getPlanning() != null) {
                modelEntries.add(
                    new ModelEntry(
                        models.getPlanning(),
                        "PLANNING",
                        MODEL_LABELS.getOrDefault(models.getPlanning(), models.getPlanning())));
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
                          workhorseId,
                          "WORKHORSE",
                          MODEL_LABELS.getOrDefault(workhorseId, workhorseId)));
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

  public record AvailableModelsResponse(List<ProviderEntry> providers) {}

  public record ProviderEntry(String id, String label, String tag, List<ModelEntry> models) {}

  public record ModelEntry(String id, String tier, String label) {}
}
