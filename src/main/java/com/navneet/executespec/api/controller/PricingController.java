package com.navneet.executespec.api.controller;

import java.time.Instant;
import java.util.List;

import com.navneet.executespec.api.dto.PricingRow;
import com.navneet.executespec.api.dto.PricingUpdateRequest;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingId;
import com.navneet.executespec.pricing.ModelPricingRepository;
import com.navneet.executespec.router.ModelMatrixProperties;
import com.navneet.executespec.router.ModelRegistry;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
@Slf4j
public class PricingController {

  private final ModelPricingRepository pricingRepo;
  private final RunAccessGuard guard;
  private final ModelRegistry modelRegistry;

  /**
   * 2026-05-06 — convert a {@link ModelPricing} entity to a {@link PricingRow} DTO. Hydrates
   * cached-input rate, chain-tier, and display-label from the YAML matrix so the frontend pricing
   * table doesn't need a hardcoded model→role map.
   */
  private PricingRow toRow(ModelPricing p) {
    String model = p.getId().getModel();
    ModelMatrixProperties.ModelConfig cfg = modelRegistry.allModels().get(model);
    return new PricingRow(
        p.getId().getProvider(),
        model,
        p.getInputCostPer1m() != null ? p.getInputCostPer1m() : 0,
        p.getCachedInputCostPer1m() != null ? p.getCachedInputCostPer1m() : 0,
        p.getOutputCostPer1m() != null ? p.getOutputCostPer1m() : 0,
        p.getNotes(),
        cfg != null ? cfg.getChainTier() : null,
        modelRegistry.displayLabelFor(model),
        p.getUpdatedAt());
  }

  /** List all pricing entries. Any authenticated user can view pricing. */
  @GetMapping
  public ResponseEntity<List<PricingRow>> list(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    List<PricingRow> rows = pricingRepo.findAll().stream().map(this::toRow).toList();
    return ResponseEntity.ok(rows);
  }

  /** Get pricing for a specific provider/model. Any authenticated user can view pricing. */
  @GetMapping("/{provider}/{model}")
  public ResponseEntity<PricingRow> get(
      @PathVariable String provider,
      @PathVariable String model,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    return pricingRepo
        .findByIdProviderAndIdModel(provider, model)
        .map(p -> ResponseEntity.ok(toRow(p)))
        .orElse(ResponseEntity.notFound().build());
  }

  /** Create or update pricing for a specific provider/model. SUPER_ADMIN only. */
  @PutMapping("/{provider}/{model}")
  public ResponseEntity<PricingRow> upsert(
      @PathVariable String provider,
      @PathVariable String model,
      @RequestBody PricingUpdateRequest req,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireSuperAdmin(userDetails);

    // RR-962 — validate finite, non-negative rates. Omitted required rates are null (boxed
    // Double), not a silent 0 that would understate cost, managed-credit debit and spend caps.
    double inputRate = requireRate(req.inputCostPer1m(), "inputCostPer1m");
    double outputRate = requireRate(req.outputCostPer1m(), "outputCostPer1m");

    // Preserve the existing cache rate when the request omits it; nulling it forced a generic
    // 10%-of-input runtime fallback for providers whose cache economics differ.
    ModelPricingId id = new ModelPricingId(provider, model);
    Double existingCached =
        pricingRepo
            .findByIdProviderAndIdModel(provider, model)
            .map(ModelPricing::getCachedInputCostPer1m)
            .orElse(null);
    Double cachedRate =
        req.cachedInputCostPer1m() != null
            ? requireRate(req.cachedInputCostPer1m(), "cachedInputCostPer1m")
            : existingCached;

    log.info(
        "PUT /api/pricing/{}/{} — input=${}/1M output=${}/1M cached=${}/1M",
        provider,
        model,
        inputRate,
        outputRate,
        cachedRate);

    ModelPricing saved =
        pricingRepo.save(
            ModelPricing.builder()
                .id(id)
                .inputCostPer1m(inputRate)
                .outputCostPer1m(outputRate)
                .cachedInputCostPer1m(cachedRate)
                .notes(req.notes())
                .updatedAt(Instant.now())
                .build());

    return ResponseEntity.ok(toRow(saved));
  }

  /**
   * RR-962 — a rate must be present, finite and non-negative. A missing (null), NaN, infinite or
   * negative value is a 400 rather than a persisted figure that understates cost downstream.
   */
  private static double requireRate(Double value, String field) {
    if (value == null || !Double.isFinite(value) || value < 0) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          field + " must be a finite, non-negative number.");
    }
    return value;
  }
}
