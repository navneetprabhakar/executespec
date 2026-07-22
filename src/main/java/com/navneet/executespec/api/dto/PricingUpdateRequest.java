package com.navneet.executespec.api.dto;

/**
 * Admin pricing upsert payload.
 *
 * <p>RR-962 — the rates are boxed {@link Double} (not primitive), so an OMITTED JSON field is
 * {@code null} — distinguishable from an explicit {@code 0} — rather than silently defaulting to
 * zero and understating provider cost / managed-credit debit / spend caps. {@code
 * cachedInputCostPer1m} is now part of the contract so the cache rate is preserved or explicitly
 * updated instead of being nulled on every upsert (which forced a generic 10%-of-input fallback at
 * runtime). {@link com.navneet.executespec.api.controller.PricingController} validates finite,
 * non-negative bounds and preserves any rate the request omits.
 */
public record PricingUpdateRequest(
    Double inputCostPer1m, Double outputCostPer1m, Double cachedInputCostPer1m, String notes) {}
