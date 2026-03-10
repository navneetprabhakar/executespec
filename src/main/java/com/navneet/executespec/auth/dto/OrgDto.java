package com.navneet.executespec.auth.dto;

import com.navneet.executespec.auth.entity.Org;

import java.time.Instant;

public record OrgDto(
        String id,
        String name,
        String domainPatterns,
        String tier,
        String status,
        int monthlyRunCount,
        boolean isPaymentVerified,
        Integer monthlyRunLimitOverride,
        boolean apiKeysProvided,
        Instant planExpiresAt,
        Instant createdAt,
        // Per-org AI provider key flags (raw keys never exposed)
        boolean hasAnthropicKey,
        boolean hasOpenAiKey,
        boolean hasGeminiKey,
        // Key source mode & provider restrictions
        String keySourceMode,
        java.util.List<String> enabledProviders
) {
    private static boolean present(String s) { return s != null && !s.isBlank(); }

    public static OrgDto from(Org org) {
        return new OrgDto(
                org.getId(),
                org.getName(),
                org.getDomainPatterns(),
                org.getTier(),
                org.getStatus(),
                org.getMonthlyRunCount(),
                org.isPaymentVerified(),
                org.getMonthlyRunLimitOverride(),
                org.isApiKeysProvided(),
                org.getPlanExpiresAt(),
                org.getCreatedAt(),
                present(org.getAnthropicApiKey()),
                present(org.getOpenAiApiKey()),
                present(org.getGeminiApiKey()),
                org.getKeySourceMode(),
                parseProviders(org.getEnabledProviders())
        );
    }

    private static java.util.List<String> parseProviders(String csv) {
        if (csv == null || csv.isBlank()) return null;
        return java.util.Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
