package com.navneet.executespec.project.dto;

import com.navneet.executespec.project.entity.ProjectRepo;

import java.time.Instant;
import java.util.UUID;

public record ProjectRepoDto(
        UUID id,
        String scope,
        String ownerOrgId,
        String ownerUserId,
        String githubRepoFullName,
        String githubOwner,
        String defaultBranch,
        String lastSyncedSha,
        String detectedStack,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectRepoDto from(ProjectRepo r) {
        return new ProjectRepoDto(
                r.getId(),
                r.getScope().name(),
                r.getOwnerOrgId(),
                r.getOwnerUserId(),
                r.getGithubRepoFullName(),
                r.getGithubOwner(),
                r.getDefaultBranch(),
                r.getLastSyncedSha(),
                r.getDetectedStack(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
