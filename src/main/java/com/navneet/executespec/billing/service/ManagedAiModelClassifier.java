package com.navneet.executespec.billing.service;

import java.util.Locale;
import java.util.Optional;

import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManagedAiModelClassifier {

  private final ModelMatrixProperties modelMatrixProperties;
  private final ModelRegistry modelRegistry;

  public String costClass(String model) {
    ModelMatrixProperties.ModelConfig config =
        model == null ? null : modelMatrixProperties.getModels().get(model);
    if (config != null && config.getCostClass() != null && !config.getCostClass().isBlank()) {
      return config.getCostClass().trim().toUpperCase(Locale.ROOT);
    }
    String chain = config == null ? "" : safe(config.getChainTier());
    return switch (chain) {
      case "ARCHITECT" -> "FRONTIER";
      case "UTILITY" -> "CHEAP";
      default -> "BALANCED";
    };
  }

  public String qualityClass(String model) {
    ModelMatrixProperties.ModelConfig config =
        model == null ? null : modelMatrixProperties.getModels().get(model);
    if (config != null && config.getQualityClass() != null && !config.getQualityClass().isBlank()) {
      return config.getQualityClass().trim().toUpperCase(Locale.ROOT);
    }
    String chain = config == null ? "" : safe(config.getChainTier());
    return switch (chain) {
      case "ARCHITECT" -> "PREMIUM";
      case "UTILITY" -> "BASIC";
      default -> "HIGH";
    };
  }

  public boolean managedAiEnabled(String model) {
    ModelMatrixProperties.ModelConfig config =
        model == null ? null : modelMatrixProperties.getModels().get(model);
    return config == null || config.getManagedAiEnabled() == null || config.getManagedAiEnabled();
  }

  public Optional<ModelTarget> sameProviderStepDown(String provider, String currentModel) {
    String normalizedProvider = safe(provider).toLowerCase(Locale.ROOT);
    if (normalizedProvider.isBlank()) return Optional.empty();
    ModelMatrixProperties.ProviderModels providerModels =
        modelMatrixProperties.getProviders() == null
            ? null
            : modelMatrixProperties.getProviders().get(normalizedProvider);
    if (providerModels != null) {
      Optional<ModelTarget> lightweight =
          targetIfAllowed(normalizedProvider, providerModels.getLightweight());
      if (lightweight.isPresent()) return lightweight;
      Optional<ModelTarget> workhorse =
          targetIfAllowed(normalizedProvider, providerModels.getWorkhorse());
      if (workhorse.isPresent()) return workhorse;
    }
    return modelMatrixProperties.getModels().entrySet().stream()
        .filter(e -> normalizedProvider.equalsIgnoreCase(safe(e.getValue().getProvider())))
        .filter(e -> !e.getKey().equals(currentModel))
        .filter(e -> "CHEAP".equals(costClass(e.getKey())))
        .filter(e -> managedAiEnabled(e.getKey()))
        .sorted(
            java.util.Comparator.comparingInt(
                    (java.util.Map.Entry<String, ModelMatrixProperties.ModelConfig> e) ->
                        e.getValue().getStepDownPriority() == null
                            ? Integer.MAX_VALUE
                            : e.getValue().getStepDownPriority())
                .thenComparingDouble(e -> outputPrice(e.getValue())))
        .map(e -> new ModelTarget(normalizedProvider, e.getKey()))
        .findFirst();
  }

  public Optional<ModelTarget> sameProviderStepUp(String provider, String currentModel) {
    String normalizedProvider = safe(provider).toLowerCase(Locale.ROOT);
    if (normalizedProvider.isBlank()) return Optional.empty();
    return modelMatrixProperties.getModels().entrySet().stream()
        .filter(e -> normalizedProvider.equalsIgnoreCase(safe(e.getValue().getProvider())))
        .filter(e -> !e.getKey().equals(currentModel))
        .filter(e -> "FRONTIER".equals(costClass(e.getKey())))
        .filter(e -> managedAiEnabled(e.getKey()))
        .sorted(
            java.util.Comparator.comparingDouble(
                    (java.util.Map.Entry<String, ModelMatrixProperties.ModelConfig> e) ->
                        outputPrice(e.getValue()))
                .thenComparingInt(
                    e ->
                        e.getValue().getStepDownPriority() == null
                            ? Integer.MAX_VALUE
                            : e.getValue().getStepDownPriority()))
        .map(e -> new ModelTarget(normalizedProvider, e.getKey()))
        .findFirst();
  }

  private Optional<ModelTarget> targetIfAllowed(String provider, String model) {
    if (model == null || model.isBlank()) return Optional.empty();
    String canonical = modelRegistry.canonicalize(model);
    if (!managedAiEnabled(canonical) || !"CHEAP".equals(costClass(canonical))) {
      return Optional.empty();
    }
    String owner = modelRegistry.providerFor(canonical);
    if (owner != null && !provider.equalsIgnoreCase(owner)) return Optional.empty();
    return Optional.of(new ModelTarget(provider, canonical));
  }

  private static double outputPrice(ModelMatrixProperties.ModelConfig config) {
    return config.getPricing() == null ? Double.MAX_VALUE : config.getPricing().getOutputPer1m();
  }

  private static String safe(String value) {
    return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
  }

  public record ModelTarget(String provider, String model) {}
}
