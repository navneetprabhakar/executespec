package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.dto.PricingRow;
import com.navneet.executespec.api.dto.PricingUpdateRequest;
import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingId;
import com.navneet.executespec.pricing.ModelPricingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/pricing")
@RequiredArgsConstructor
@Slf4j
public class PricingController {

    private final ModelPricingRepository pricingRepo;

    /** List all pricing entries. */
    @GetMapping
    public ResponseEntity<List<PricingRow>> list() {
        List<PricingRow> rows = pricingRepo.findAll().stream()
            .map(p -> new PricingRow(
                p.getId().getProvider(), p.getId().getModel(),
                p.getInputCostPer1m(), p.getOutputCostPer1m(),
                p.getNotes(), p.getUpdatedAt()))
            .toList();
        return ResponseEntity.ok(rows);
    }

    /** Get pricing for a specific provider/model. */
    @GetMapping("/{provider}/{model}")
    public ResponseEntity<PricingRow> get(@PathVariable String provider,
                                           @PathVariable String model) {
        return pricingRepo.findByIdProviderAndIdModel(provider, model)
            .map(p -> ResponseEntity.ok(new PricingRow(
                p.getId().getProvider(), p.getId().getModel(),
                p.getInputCostPer1m(), p.getOutputCostPer1m(),
                p.getNotes(), p.getUpdatedAt())))
            .orElse(ResponseEntity.notFound().build());
    }

    /** Create or update pricing for a specific provider/model. */
    @PutMapping("/{provider}/{model}")
    public ResponseEntity<PricingRow> upsert(@PathVariable String provider,
                                              @PathVariable String model,
                                              @RequestBody PricingUpdateRequest req) {
        log.info("PUT /api/pricing/{}/{} — input=${}/1M output=${}/1M", provider, model,
            req.inputCostPer1m(), req.outputCostPer1m());

        ModelPricing saved = pricingRepo.save(ModelPricing.builder()
            .id(new ModelPricingId(provider, model))
            .inputCostPer1m(req.inputCostPer1m())
            .outputCostPer1m(req.outputCostPer1m())
            .notes(req.notes())
            .updatedAt(Instant.now())
            .build());

        return ResponseEntity.ok(new PricingRow(
            saved.getId().getProvider(), saved.getId().getModel(),
            saved.getInputCostPer1m(), saved.getOutputCostPer1m(),
            saved.getNotes(), saved.getUpdatedAt()));
    }
}
