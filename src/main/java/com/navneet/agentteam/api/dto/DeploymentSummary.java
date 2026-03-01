package com.navneet.agentteam.api.dto;

import com.navneet.agentteam.state.entity.Deployment;

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
