package com.navneet.executespec.api.dto;

import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.RunRepo;

public record DeploymentDetail(
    String id,
    String runId,
    String versionTag,
    String strategy,
    String status,
    String imageUri,
    String serviceName,
    String revisionName,
    String serviceUrl,
    String gcpProject,
    String gcpRegion,
    Double estimatedCostUsd,
    Double actualCostUsd,
    String errorMessage,
    String buildLog,
    String createdAt,
    String deployedAt,
    String ttlExpiresAt,
    String deletedAt,
    RepoInfo repo
) {
    public record RepoInfo(String repoUrl, String repoMode, String pushedAt) {}

    public static DeploymentDetail from(Deployment d, RunRepo repo) {
        RepoInfo ri = null;
        if (repo != null && repo.getRepoMode() != RunRepo.RepoMode.NONE) {
            ri = new RepoInfo(repo.getRepoUrl(), repo.getRepoMode().name(), repo.getPushedAt());
        }
        return new DeploymentDetail(
            d.getId(), d.getRunId(), d.getVersionTag(),
            d.getStrategy() != null ? d.getStrategy().name() : null,
            d.getStatus().name(), d.getImageUri(), d.getServiceName(),
            d.getRevisionName(), d.getServiceUrl(),
            d.getGcpProject(), d.getGcpRegion(),
            d.getEstimatedCostUsd(), d.getActualCostUsd(),
            d.getErrorMessage(), d.getBuildLog(),
            d.getCreatedAt(), d.getDeployedAt(), d.getTtlExpiresAt(), d.getDeletedAt(),
            ri
        );
    }
}
