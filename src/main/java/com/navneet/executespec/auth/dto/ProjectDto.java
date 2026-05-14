package com.navneet.executespec.auth.dto;

import java.time.Instant;

import com.navneet.executespec.auth.entity.Project;

public record ProjectDto(
    String id,
    String orgId,
    String name,
    String description,
    String status,
    String aiProvider,
    Instant createdAt,
    String branchNamingPattern,
    String targetBranch,
    String defaultReviewers,
    String githubOwner,
    /** V191 (PR-B-part-2) — when this project was archived (null = ACTIVE). */
    Instant archivedAt,
    /**
     * V191 (PR-B-part-2) — when this project was soft-deleted (null = not deleted). Soft-deleted
     * rows are normally filtered from listings; this field is only populated when an admin tool or
     * a tombstone-restore view requests the raw row.
     */
    Instant deletedAt) {
  public static ProjectDto from(Project p) {
    return new ProjectDto(
        p.getId(),
        p.getOrgId(),
        p.getName(),
        p.getDescription(),
        p.getStatus(),
        p.getAiProvider() != null ? p.getAiProvider().name() : "ANTHROPIC",
        p.getCreatedAt(),
        p.getBranchNamingPattern(),
        p.getTargetBranch(),
        p.getDefaultReviewers(),
        p.getGithubOwner(),
        p.getArchivedAt(),
        p.getDeletedAt());
  }
}
