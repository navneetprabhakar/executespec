package com.navneet.executespec.api.dto;

public record PricingUpdateRequest(double inputCostPer1m, double outputCostPer1m, String notes) {}
