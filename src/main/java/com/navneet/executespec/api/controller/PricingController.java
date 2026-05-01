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

  /** List all pricing entries. Any authenticated user can view pricing. */
  @GetMapping
  public ResponseEntity<List<PricingRow>> list(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    List<PricingRow> rows =
        pricingRepo.findAll().stream()
            .map(
                p ->
                    new PricingRow(
                        p.getId().getProvider(), p.getId().getModel(),
                        p.getInputCostPer1m(), p.getOutputCostPer1m(),
                        p.getNotes(), p.getUpdatedAt()))
            .toList();
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
        .map(
            p ->
                ResponseEntity.ok(
                    new PricingRow(
                        p.getId().getProvider(), p.getId().getModel(),
                        p.getInputCostPer1m(), p.getOutputCostPer1m(),
                        p.getNotes(), p.getUpdatedAt())))
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
    log.info(
        "PUT /api/pricing/{}/{} — input=${}/1M output=${}/1M",
        provider,
        model,
        req.inputCostPer1m(),
        req.outputCostPer1m());

    ModelPricing saved =
        pricingRepo.save(
            ModelPricing.builder()
                .id(new ModelPricingId(provider, model))
                .inputCostPer1m(req.inputCostPer1m())
                .outputCostPer1m(req.outputCostPer1m())
                .notes(req.notes())
                .updatedAt(Instant.now())
                .build());

    return ResponseEntity.ok(
        new PricingRow(
            saved.getId().getProvider(), saved.getId().getModel(),
            saved.getInputCostPer1m(), saved.getOutputCostPer1m(),
            saved.getNotes(), saved.getUpdatedAt()));
  }
}
