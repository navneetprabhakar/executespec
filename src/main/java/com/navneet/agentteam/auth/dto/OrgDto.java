package com.navneet.agentteam.auth.dto;

import com.navneet.agentteam.auth.entity.Org;

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
        Instant createdAt
) {
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
                org.getCreatedAt()
        );
    }
}
