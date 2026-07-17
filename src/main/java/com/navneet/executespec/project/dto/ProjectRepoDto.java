package com.navneet.executespec.project.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.project.entity.ProjectRepo;

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
    String sourceReadinessStatus,
    String sourceReadinessReason,
    Long sourceFileCount,
    Instant sourceReadinessCheckedAt,
    String executespecFolderStatus,
    String executespecBootstrapPrUrl,
    boolean webhookInstalled,
    // R-322 — declared monorepo project root (null = repo root).
    String subdir,
    // R-325 — repo size at attach time (KB) + big-repo opt-in flag.
    Long sizeKb,
    boolean attachedAnyway,
    // V278 — additive neutral source identity. Legacy GitHub fields above remain unchanged.
    String sourceType,
    String scmProvider,
    String sourceKey,
    String sourceDisplayName,
    UUID localFolderId,
    UUID localInstallationId,
    String sourceRevision,
    List<String> sourceCapabilities,
    Map<String, String> sourceMetadata,
    Instant sourceLastSeenAt,
    Instant sourceLeaseExpiresAt,
    String localConnectionStatus,
    Instant createdAt,
    Instant updatedAt) {
  public static ProjectRepoDto from(ProjectRepo r) {
    return from(r, Instant.now());
  }

  public static ProjectRepoDto from(ProjectRepo r, Instant now) {
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
        r.getSourceReadinessStatus(),
        r.getSourceReadinessReason(),
        r.getSourceFileCount(),
        r.getSourceReadinessCheckedAt(),
        r.folderStatusEnum().name(),
        r.getExecutespecBootstrapPrUrl(),
        r.isWebhookInstalled(),
        r.getSubdir(),
        r.getSizeKb(),
        r.isAttachedAnyway(),
        r.getSourceType() == null ? ProjectRepo.SourceType.SCM.name() : r.getSourceType(),
        r.getScmProvider() == null && r.getGithubRepoFullName() != null
            ? "GITHUB"
            : r.getScmProvider(),
        r.getSourceKey() == null ? r.getGithubRepoFullName() : r.getSourceKey(),
        r.getSourceDisplayName() == null ? r.getGithubRepoFullName() : r.getSourceDisplayName(),
        r.getLocalFolderId(),
        r.getLocalInstallationId(),
        r.getSourceRevision(),
        r.getSourceCapabilities() == null ? List.of() : List.copyOf(r.getSourceCapabilities()),
        r.getSourceMetadata() == null ? Map.of() : Map.copyOf(r.getSourceMetadata()),
        r.getSourceLastSeenAt(),
        r.getSourceLeaseExpiresAt(),
        r.localConnectionStatus(now),
        r.getCreatedAt(),
        r.getUpdatedAt());
  }
}
