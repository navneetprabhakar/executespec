package com.navneet.executespec.api.dto;

import com.navneet.executespec.state.entity.Deployment;

public record DeploymentSummary(
    String id,
    String runId,
    String versionTag,
    String strategy,
    String status,
    String serviceUrl,
    String revisionName,
    String createdAt,
    String ttlExpiresAt
) {
    public static DeploymentSummary from(Deployment d) {
        return new DeploymentSummary(
            d.getId(),
            d.getRunId(),
            d.getVersionTag(),
            d.getStrategy() != null ? d.getStrategy().name() : null,
            d.getStatus().name(),
            d.getServiceUrl(),
            d.getRevisionName(),
            d.getCreatedAt(),
            d.getTtlExpiresAt()
        );
    }
}
