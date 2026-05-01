package com.navneet.executespec.project.dto;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;

public record ProjectRepoLinkDto(
    UUID id,
    String projectId,
    UUID repoId,
    ProjectRepoDto repo,
    String relationships,
    Instant linkedAt,
    String linkedBy) {
  public static ProjectRepoLinkDto from(ProjectRepoLink link, ProjectRepo repo) {
    return new ProjectRepoLinkDto(
        link.getId(),
        link.getProjectId(),
        link.getRepoId(),
        ProjectRepoDto.from(repo),
        link.getRelationships(),
        link.getLinkedAt(),
        link.getLinkedBy());
  }
}
