package com.navneet.executespec.harness;

import java.util.Locale;

import com.navneet.executespec.router.ModelMatrixProperties;

/** Resolves a task's desired output budget against the selected model's YAML capability. */
final class ModelOutputTokenBudget {

  private ModelOutputTokenBudget() {}

  static int clamp(ModelMatrixProperties matrix, String model, int desired) {
    if (desired <= 0 || matrix == null || matrix.getModels() == null || model == null) {
      return desired;
    }
    String canonical = model;
    if (matrix.getLegacyAliases() != null) {
      canonical = matrix.getLegacyAliases().getOrDefault(model.toLowerCase(Locale.ROOT), model);
    }
    ModelMatrixProperties.ModelConfig config = matrix.getModels().get(canonical);
    if (config == null) {
      String selected = canonical;
      config =
          matrix.getModels().entrySet().stream()
              .filter(entry -> entry.getKey().equalsIgnoreCase(selected))
              .map(java.util.Map.Entry::getValue)
              .findFirst()
              .orElse(null);
    }
    return config == null || config.getMaxOutputTokens() <= 0
        ? desired
        : Math.min(desired, config.getMaxOutputTokens());
  }
}
