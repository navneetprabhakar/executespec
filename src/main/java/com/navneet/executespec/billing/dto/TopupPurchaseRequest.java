package com.navneet.executespec.billing.dto;

import java.util.UUID;

public record TopupPurchaseRequest(
    UUID packageId, Long amountUsdCents, String gateway, String successUrl, String cancelUrl) {}
