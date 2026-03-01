package com.navneet.agentteam.config;

import com.navneet.agentteam.pricing.ModelPricing;
import com.navneet.agentteam.pricing.ModelPricingId;
import com.navneet.agentteam.pricing.ModelPricingRepository;
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
        List<Object[]> defaults = List.of(
            // ── Anthropic ────────────────────────────────────────────────────
            new Object[]{
                "anthropic", "claude-sonnet-4-5-20250929", 3.00, 15.00,
                "✅ ACTIVE — ALL workers, planner, reviewer, context agent, optimiser"
            },
            new Object[]{
                "anthropic", "claude-opus-4-5-20251101", 15.00, 75.00,
                "Recovery escalation fallback only (capable-models config)"
            },
            // ── OpenAI ───────────────────────────────────────────────────────
            new Object[]{
                "openai", "gpt-4o-mini", 0.15, 0.60,
                "OpenAI fallback — provider outage / rate-limit auto-switch"
            },
            new Object[]{
                "openai", "gpt-4o", 2.50, 10.00,
                "OpenAI capable fallback — provider outage / rate-limit auto-switch"
            },
            // ── Google / Gemini ──────────────────────────────────────────────
            new Object[]{
                "gemini", "gemini-2.0-flash", 0.10, 0.40,
                "Cheap chain tertiary fallback (needs spring.ai.google.api-key to activate)"
            },
            new Object[]{
                "gemini", "gemini-2.5-pro", 1.25, 10.00,
                "Capable chain tertiary fallback (needs spring.ai.google.api-key to activate)"
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
