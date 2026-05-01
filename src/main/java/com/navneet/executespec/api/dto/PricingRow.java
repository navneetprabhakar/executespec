package com.navneet.executespec.api.dto;

import java.time.Instant;

public record PricingRow(
    String provider,
    String model,
    double inputCostPer1m,
    double outputCostPer1m,
    String notes,
    Instant updatedAt) {}
