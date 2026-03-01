package com.navneet.agentteam.api.dto;

public record PricingUpdateRequest(
    double inputCostPer1m,
    double outputCostPer1m,
    String notes
) {}
