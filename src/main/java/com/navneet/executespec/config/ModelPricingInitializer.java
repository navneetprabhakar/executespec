package com.navneet.executespec.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingId;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seeds / refreshes the model_pricing table on every startup.
 *
 * <p>Uses an upsert strategy (always save) so that price or note changes in application.yml are
 * reflected immediately on restart — no manual DB edits required.
 *
 * <p>Reads all model metadata from {@code model-matrix.models} in application.yml via {@link
 * ModelRegistry} — no hardcoded pricing data.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelPricingInitializer {

  private final ModelPricingRepository pricingRepo;
  private final ModelRegistry modelRegistry;

  @PostConstruct
  public void seed() {
    Map<String, ModelMatrixProperties.ModelConfig> allModels = modelRegistry.allModels();

    if (allModels.isEmpty()) {
      log.warn(
          "[ModelPricingInitializer] No models configured in model-matrix.models — skipping pricing"
              + " seed");
      return;
    }

    // Build canonical pricing IDs from config
    Set<ModelPricingId> canonical =
        allModels.entrySet().stream()
            .filter(e -> e.getValue().getPricing() != null)
            .map(e -> new ModelPricingId(e.getValue().getProvider(), e.getKey()))
            .collect(Collectors.toSet());

    // Delete stale rows not in the current config
    List<ModelPricing> stale =
        pricingRepo.findAll().stream().filter(p -> !canonical.contains(p.getId())).toList();
    if (!stale.isEmpty()) {
      pricingRepo.deleteAll(stale);
      log.info("[ModelPricingInitializer] Removed {} stale pricing rows", stale.size());
    }

    // Upsert all models from config
    int count = 0;
    for (var entry : allModels.entrySet()) {
      String modelName = entry.getKey();
      ModelMatrixProperties.ModelConfig config = entry.getValue();
      if (config.getPricing() == null) continue;

      ModelPricingId id = new ModelPricingId(config.getProvider(), modelName);
      pricingRepo.save(
          ModelPricing.builder()
              .id(id)
              .inputCostPer1m(config.getPricing().getInputPer1m())
              .outputCostPer1m(config.getPricing().getOutputPer1m())
              .cachedInputCostPer1m(config.getPricing().getCachedInputPer1m())
              .notes(config.getNotes())
              .updatedAt(Instant.now())
              .build());
      count++;
    }

    log.info("[ModelPricingInitializer] Upserted {} model pricing rows from config", count);
  }
}
