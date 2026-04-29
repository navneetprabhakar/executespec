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
        boolean webhookInstalled,
        // R-322 — declared monorepo project root (null = repo root).
        String subdir,
        // R-325 — repo size at attach time (KB) + big-repo opt-in flag.
        Long sizeKb,
        boolean attachedAnyway,
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
                r.isWebhookInstalled(),
                r.getSubdir(),
                r.getSizeKb(),
                r.isAttachedAnyway(),
                r.getCreatedAt(),
                r.getUpdatedAt()
        );
    }
}
