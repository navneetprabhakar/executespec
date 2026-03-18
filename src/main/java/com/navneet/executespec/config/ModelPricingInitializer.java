package com.navneet.executespec.config;

import com.navneet.executespec.pricing.ModelPricing;
import com.navneet.executespec.pricing.ModelPricingId;
import com.navneet.executespec.pricing.ModelPricingRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Seeds / refreshes the model_pricing table on every startup.
 *
 * <p>Uses an upsert strategy (always save) so that price or note changes here
 * are reflected immediately on restart — no manual DB edits required.
 *
 * <p>Prices are per-million tokens (Anthropic / OpenAI public pricing, Feb 2026).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ModelPricingInitializer {

    private final ModelPricingRepository pricingRepo;

    @PostConstruct
    public void seed() {
        // Each entry: provider, model, input $/1M, output $/1M, notes
        // Notes describe the role the model plays in this pipeline.
        // Fallback order: Anthropic (1st) → Google Gemini (2nd) → OpenAI (3rd)
        List<Object[]> defaults = List.of(
            // ── Anthropic — primary provider (Anthropic public pricing Mar 2026) ─────────
            new Object[]{
                "anthropic", "claude-opus-4-6", 15.00, 75.00,
                "✅ PRIMARY — Planner (ARCHITECT chain). With prompt caching: writes 1.25x, reads 0.10x."
            },
            new Object[]{
                "anthropic", "claude-sonnet-4-6", 3.00, 15.00,
                "✅ PRIMARY — Workers (CAPABLE chain). With prompt caching: writes 1.25x, reads 0.10x."
            },
            new Object[]{
                "anthropic", "claude-haiku-4-5-20251001", 0.80, 4.00,
                "✅ PRIMARY — Reviewer + context agent + optimiser (UTILITY chain)."
            },
            // ── Google Gemini — 2nd fallback (Google AI pricing Mar 2026) ────────────────
            new Object[]{
                "google", "gemini-3.1-pro-preview-customtools", 2.00, 12.00,
                "2nd fallback — Anthropic outage/rate-limit auto-switch (ARCHITECT chain). 1M ctx, 66K output. Released Feb 2026."
            },
            new Object[]{
                "google", "gemini-3.1-flash-lite-preview", 0.25, 1.50,
                "2nd fallback — Anthropic outage/rate-limit auto-switch (CAPABLE chain). Released Mar 2026."
            },
            new Object[]{
                "google", "gemini-3-flash-preview", 0.15, 0.60,
                "2nd fallback — Anthropic outage/rate-limit auto-switch (UTILITY chain). Gemini 3.0 flash. Released Mar 2026."
            },
            // ── OpenAI — 3rd fallback (OpenAI public pricing Mar 2026) ───────────────────
            new Object[]{
                "openai", "gpt-4o", 2.50, 10.00,
                "3rd fallback — Gemini outage/rate-limit auto-switch (CAPABLE + ARCHITECT chains)"
            },
            new Object[]{
                "openai", "gpt-4o-mini", 0.15, 0.60,
                "3rd fallback — Gemini outage/rate-limit auto-switch (UTILITY chain)"
            }
        );

        // Delete any rows not in the current canonical list (removes stale models from old runs)
        Set<ModelPricingId> canonical = defaults.stream()
            .map(r -> new ModelPricingId((String) r[0], (String) r[1]))
            .collect(java.util.stream.Collectors.toSet());
        List<ModelPricing> stale = pricingRepo.findAll().stream()
            .filter(p -> !canonical.contains(p.getId()))
            .toList();
        if (!stale.isEmpty()) {
            pricingRepo.deleteAll(stale);
            log.info("[ModelPricingInitializer] Removed {} stale pricing rows", stale.size());
        }

        for (Object[] row : defaults) {
            ModelPricingId id = new ModelPricingId((String) row[0], (String) row[1]);
            pricingRepo.save(ModelPricing.builder()
                .id(id)
                .inputCostPer1m((Double) row[2])
                .outputCostPer1m((Double) row[3])
                .notes((String) row[4])
                .updatedAt(Instant.now())
                .build());
        }

        log.info("[ModelPricingInitializer] Upserted {} model pricing rows", defaults.size());
    }
}
