package com.navneet.executespec.api.dto;

import java.time.Instant;

public record PricingRow(
    String provider,
    String model,
    double inputCostPer1m,
    double cachedInputCostPer1m,
    double outputCostPer1m,
    String notes,
    /**
     * 2026-05-06 — chain-tier from {@code model-matrix.models.<id>.chain-tier} (ARCHITECT /
     * CAPABLE / UTILITY). Lets the frontend pricing table render a "role" label without
     * hardcoding a model→role map. Null when the model isn't in the YAML matrix (legacy DB
     * row that hasn't been re-seeded).
     */
    String chainTier,
    /** Display label from {@code model-matrix.models.<id>.display-label}. Null fallback to model id. */
    String displayLabel,
    Instant updatedAt) {}
