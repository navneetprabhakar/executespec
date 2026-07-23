package com.navneet.executespec.config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingId;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p>The seed is a read-then-write over a composite primary key, so two instances starting
 * concurrently against a fresh database would both find the table empty and both insert — and the
 * loser would fail to boot on {@code model_pricing_pkey}. That is invisible with one instance and
 * breaks scale-out, rolling deploys, and blue/green onto a fresh schema. Seeding therefore runs
 * inside a transaction that first takes a Postgres advisory lock, following the same pattern the
 * activity-audit hash chain uses to serialize its appends across JVMs.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelPricingInitializer {

  /** Namespaced so it cannot collide with the other advisory locks in the application. */
  static final String SEED_LOCK_SQL =
      "SELECT pg_advisory_xact_lock(hashtext('executespec'), hashtext('model_pricing_seed'))";

  private final ModelPricingRepository pricingRepo;
  private final ModelRegistry modelRegistry;
  private final TransactionTemplate transactionTemplate;

  @PersistenceContext private EntityManager entityManager;

  @PostConstruct
  public void seed() {
    transactionTemplate.executeWithoutResult(tx -> seedWithinTransaction());
  }

  /**
   * Visible for testing. The lock is transaction-scoped: it is released on commit or rollback, so
   * there is no lock row to clean up and a crashed instance cannot hold it.
   */
  void seedWithinTransaction() {
    entityManager.createNativeQuery(SEED_LOCK_SQL).getSingleResult();

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
